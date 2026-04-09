use crate::low_level::reactor::{Reactor, Token, Fd, Event as ReactorEvent};
use crate::low_level::spawn::{SysError, spawn_start, SpawnOptions, SpawnBackend, Process};
use crate::low_level::sys::{ExecContext, ProcessGroup};
use crate::low_level::io::DrainState;
use crate::core::{Event, Effect, LogLevel, LogEvent, ControlSignal, ProcessHandle, IoHandle};
use std::collections::HashMap;

pub trait LogSink {
    fn write(&mut self, level: LogLevel, msg: String);
}

pub struct StdoutSink;
impl LogSink for StdoutSink {
    fn write(&mut self, level: LogLevel, msg: String) {
        println!("[{:?}] {}", level, msg);
    }
}

pub struct EffectExecutor {
    pub reactor: Reactor,
    pub fd_map: HashMap<Token, u64>, // Maps reactor Token -> JobId directly as requested
    pub processes: HashMap<ProcessHandle, Process>,
    pub drains: HashMap<IoHandle, DrainState<fn(&[u8]) -> bool>>,
    pub sink: Box<dyn LogSink>,
    next_handle: u64,
}

impl EffectExecutor {
    pub fn new(reactor: Reactor) -> Self {
        Self {
            reactor,
            fd_map: HashMap::new(),
            processes: HashMap::new(),
            drains: HashMap::new(),
            sink: Box::new(StdoutSink),
            next_handle: 1,
        }
    }

    fn gen_handle(&mut self) -> u64 {
        let h = self.next_handle;
        self.next_handle += 1;
        h
    }

    pub fn process_reactor_events(&mut self, events: &mut Vec<ReactorEvent>, timeout_ms: i32) -> Result<Vec<Event>, SysError> {
        let nevents = self.reactor.wait(events, 64, timeout_ms)?;
        let mut sys_events = Vec::new();

        for ev in events.iter().take(nevents) {
            if let Some(&job_id) = self.fd_map.get(&ev.token) {
                sys_events.push(Event::JobFdReady {
                    job_id,
                    readable: ev.readable,
                    writable: ev.writable,
                    error: ev.error
                });
            }
        }
        Ok(sys_events)
    }

    pub fn apply(&mut self, effect: Effect) -> Option<Event> {
        match effect {
            Effect::Log { level, event } => {
                let msg = match event {
                    LogEvent::Submit { id } => format!("Submit id={}", id),
                    LogEvent::Spawn { id, pid } => format!("Spawn id={}, pid={}", id, pid),
                    LogEvent::Cancel { id } => format!("Cancel id={}", id),
                    LogEvent::ForceKill { id } => format!("ForceKill id={}", id),
                    LogEvent::Exit { id, status } => format!("Exit id={}, status={:?}", id, status),
                    LogEvent::Timeout { id } => format!("Timeout id={}", id),
                    LogEvent::Error { id, err } => format!("Error id={}, err={}", id, err),
                };
                self.sink.write(level, msg);
                None
            }
            Effect::WatchStream { id, io, stream } => {
                if let Some(drain) = self.drains.get(&io) {
                    let raw_fd = match stream {
                        crate::core::IoStream::Stdout => drain.stdout_slot.as_ref().map(|s| s.fd.raw()),
                        crate::core::IoStream::Stderr => drain.stderr_slot.as_ref().map(|s| s.fd.raw()),
                        crate::core::IoStream::Stdin => drain.stdin_slot.as_ref().map(|s| s.fd.raw()),
                    };

                    if let Some(fd) = raw_fd {
                        let token = Token(fd as u64); // Safe fallback without duplicate map logic if unique.
                        if let Ok(_) = self.reactor.add_with_token(fd, token, true, true) {
                            self.fd_map.insert(token, id);
                        }
                    }
                }
                None
            }
            Effect::UnwatchStream { id: _, io, stream } => {
                if let Some(drain) = self.drains.get(&io) {
                    let raw_fd = match stream {
                        crate::core::IoStream::Stdout => drain.stdout_slot.as_ref().map(|s| s.fd.raw()),
                        crate::core::IoStream::Stderr => drain.stderr_slot.as_ref().map(|s| s.fd.raw()),
                        crate::core::IoStream::Stdin => drain.stdin_slot.as_ref().map(|s| s.fd.raw()),
                    };

                    if let Some(fd) = raw_fd {
                        self.reactor.del_raw(fd);
                        self.fd_map.remove(&Token(fd as u64));
                    }
                }
                None
            }
            Effect::StartProcess { id, exec, policy } => {
                let ctx = ExecContext::new(exec.argv, None, None);
                let stdin_buf = exec.stdin.map(|v| v.into_boxed_slice());

                let opts = SpawnOptions {
                    ctx,
                    stdin: stdin_buf,
                    capture_stdout: exec.capture_stdout,
                    capture_stderr: exec.capture_stderr,
                    wait: false,
                    pgroup: ProcessGroup::default(),
                    max_output: exec.max_output,
                    timeout_ms: None,
                    kill_grace_ms: policy.kill_grace_ms,
                    cancel: match policy.cancel {
                        crate::core::CancelPolicy::None => crate::low_level::sys::CancelPolicy::None,
                        crate::core::CancelPolicy::Graceful => crate::low_level::sys::CancelPolicy::Graceful,
                        crate::core::CancelPolicy::Kill => crate::low_level::sys::CancelPolicy::Kill,
                    },
                    backend: SpawnBackend::Auto,
                    early_exit: None,
                };

                match spawn_start(id, opts) {
                    Ok(running) => {
                        let proc_h = ProcessHandle(self.gen_handle());
                        let io_h = IoHandle(self.gen_handle());
                        self.processes.insert(proc_h, running.process);
                        self.drains.insert(io_h, running.drain);

                        Some(Event::ProcessStarted { id, process: proc_h, io: io_h })
                    }
                    Err(e) => {
                        Some(Event::ProcessSpawnFailed { id, err: format!("spawn_failed: {}", e) })
                    }
                }
            }
            Effect::KillProcess { id: _, signal, process, is_group } => {
                if let Some(proc) = self.processes.get_mut(&process) {
                    match signal {
                        ControlSignal::GracefulStop => {
                            let _ = if is_group { proc.kill_pgroup(libc::SIGTERM) } else { proc.kill(libc::SIGTERM) };
                        }
                        ControlSignal::ForceKill => {
                            let _ = if is_group { proc.kill_pgroup(libc::SIGKILL) } else { proc.kill(libc::SIGKILL) };
                        }
                    }
                }
                None
            }
            Effect::PollProcess { id, process } => {
                if let Some(proc) = self.processes.get_mut(&process) {
                    let status_res = proc.wait_step();
                    match status_res {
                        Ok(Some(status)) => {
                            let s = match status {
                                crate::low_level::spawn::ExitStatus::Exited(c) => c,
                                crate::low_level::spawn::ExitStatus::Signaled(sig) => -sig,
                            };
                            return Some(Event::ProcessExited { id, status: Some(s) });
                        }
                        Ok(None) => return None,
                        Err(_) => {
                            return Some(Event::ProcessExited { id, status: None });
                        }
                    }
                }
                None
            }
            Effect::PerformIo { id, io } => {
                // Here we actually perform the io on the drain and handle unregistration of tokens
                // but io should not be performed directly by runtime inside apply blindly without being told what stream.
                // Or we can just read what's available.
                // However, since user stated that DrainState does syscalls, it must be invoked by runtime.
                // Core maps FdReady into `PerformIo` so runtime handles the read/write logic natively.
                if let Some(drain) = self.drains.get_mut(&io) {
                    // For brevity, we simply try to read/write all valid slots
                    if drain.stdout_slot.is_some() { let _ = drain.read_fd(true); }
                    if drain.stderr_slot.is_some() { let _ = drain.read_fd(false); }
                    if drain.stdin_slot.is_some() { let _ = drain.write_stdin(); }
                }
                None
            }
        }
    }
}
