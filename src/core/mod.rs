use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub mod policy;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum CancelPolicy {
    None,
    Graceful,
    Kill,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ProcessHandle(pub u64);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct IoHandle(pub u64);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecSpec {
    pub argv: Vec<String>,
    pub stdin: Option<Vec<u8>>,
    pub capture_stdout: bool,
    pub capture_stderr: bool,
    pub max_output: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecPolicy {
    pub timeout_ms: Option<u32>,
    pub kill_grace_ms: u32,
    pub cancel: CancelPolicy,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ExecResult {
    pub status: Option<i32>,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub timed_out: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub enum ExecError {
    SpawnFailed,
    RuntimeError,
    Internal(String),
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ExecOutcome {
    pub id: u64,
    pub result: Result<ExecResult, ExecError>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Info,
    Warn,
    Error,
}

#[derive(Clone)]
pub enum LogEvent {
    Submit { id: u64 },
    Spawn { id: u64, pid: i32 },
    Cancel { id: u64 },
    ForceKill { id: u64 },
    Exit { id: u64, status: Option<i32> },
    Timeout { id: u64 },
    Error { id: u64, err: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ControlSignal {
    GracefulStop,
    ForceKill,
}

#[derive(Clone)]
pub enum Event {
    ProcessStarted { id: u64, process: ProcessHandle, io: IoHandle },
    ProcessSpawnFailed { id: u64, err: String },
    ProcessExited { id: u64, status: Option<i32> },
    JobFdReady { job_id: u64, readable: bool, writable: bool, error: bool },
}

#[derive(Clone)]
pub enum Action {
    Submit { id: u64, owner: u32, exec: ExecSpec, policy: ExecPolicy },

    // State Transitions
    Admitted { id: u64, owner: u32, exec: ExecSpec, policy: ExecPolicy },
    Rejected { id: u64 },
    Started { id: u64 },
    Controlled { id: u64 },
    Finished { id: u64, result: Result<ExecResult, ExecError> },
    QueryResult { id: u64, result: Option<ExecOutcome> },

    // Semantic Intents
    StartProcess { id: u64, exec: ExecSpec, policy: ExecPolicy },
    SignalProcess { id: u64, signal: ControlSignal },
    PollProcess { id: u64 },
    PerformIo { id: u64 },
    RegisterInterest { id: u64, stream: IoStream },
    RemoveInterest { id: u64, stream: IoStream },
    EmitLog { level: LogLevel, event: LogEvent },

    // Input actions
    Control { id: u64, signal: ControlSignal },
    Query  { id: u64 },
    TimeoutReached { id: u64 },
    KillDeadlineReached { id: u64 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IoStream {
    Stdout,
    Stderr,
    Stdin,
}

pub enum Effect {
    Log { level: LogLevel, event: LogEvent },
    WatchStream { id: u64, io: IoHandle, stream: IoStream },
    UnwatchStream { id: u64, io: IoHandle, stream: IoStream },
    StartProcess { id: u64, exec: ExecSpec, policy: ExecPolicy },
    KillProcess { id: u64, signal: ControlSignal, process: ProcessHandle, is_group: bool },
    PollProcess { id: u64, process: ProcessHandle },
    PerformIo { id: u64, io: IoHandle },
}

pub trait Module {
    fn handle(&mut self, state: &mut ExecutionState, action: Action) -> Vec<Action>;
    fn handle_event(&mut self, state: &mut ExecutionState, event: Event) -> Vec<Action>;
}

pub mod lifecycle;
pub mod process;
pub mod io;
pub mod result;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JobLifecycle {
    Submitted,
    Admitted,
    Running,
    Terminating,
    Killed,
    Finished,
}

pub struct JobState {
    pub id: u64,
    pub owner: u32,
    pub process: Option<ProcessHandle>,
    pub io: Option<IoHandle>,
    pub timed_out: bool,
    pub pgroup: Option<i32>,
    pub isolated: bool,
}

pub struct StoredResult {
    pub result: Result<ExecResult, ExecError>,
    pub owner: u32,
    pub created: std::time::Instant,
}

pub struct ExecutionState {
    pub jobs: HashMap<u64, JobState>,
    pub lifecycle: HashMap<u64, JobLifecycle>,
    pub results: HashMap<u64, StoredResult>,
}

impl ExecutionState {
    pub fn new() -> Self {
        Self {
            jobs: HashMap::new(),
            lifecycle: HashMap::new(),
            results: HashMap::new(),
        }
    }
}

pub struct Dispatcher {
    pub admission: crate::core::policy::AdmissionControlModule,
    pub lifecycle: crate::core::lifecycle::LifecycleModule,
    pub process: crate::core::process::ProcessModule,
    pub io: crate::core::io::IoModule,
    pub result: crate::core::result::ResultModule,
    pub timeout: crate::core::policy::TimeoutPolicyModule,
}

impl Dispatcher {
    pub fn new() -> Self {
        Self {
            admission: crate::core::policy::AdmissionControlModule::default(),
            lifecycle: crate::core::lifecycle::LifecycleModule,
            process: crate::core::process::ProcessModule,
            io: crate::core::io::IoModule,
            result: crate::core::result::ResultModule,
            timeout: crate::core::policy::TimeoutPolicyModule::new(),
        }
    }

    pub fn dispatch(&mut self, state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Submit { .. } => {
                actions.extend(self.admission.handle(state, action));
            }
            Action::Admitted { .. } => {
                actions.extend(self.lifecycle.handle(state, action.clone()));
                actions.extend(self.timeout.handle(state, action));
            }
            Action::StartProcess { .. } => {
                actions.extend(self.process.handle(state, action.clone()));
                actions.extend(self.io.handle(state, action));
            }
            Action::SignalProcess { .. } => {
                actions.extend(self.lifecycle.handle(state, action.clone()));
                actions.extend(self.process.handle(state, action));
            }
            Action::PollProcess { .. } => {
                actions.extend(self.process.handle(state, action));
            }
            Action::Finished { .. } => {
                actions.extend(self.admission.handle(state, action.clone()));
                actions.extend(self.timeout.handle(state, action.clone()));
                actions.extend(self.result.handle(state, action));
            }
            Action::Control { .. } => {
                actions.extend(self.process.handle(state, action));
            }
            Action::TimeoutReached { .. } => {
                actions.extend(self.process.handle(state, action));
            }
            Action::KillDeadlineReached { .. } => {
                actions.extend(self.process.handle(state, action));
            }
            Action::Query { .. } => {
                actions.extend(self.result.handle(state, action));
            }
            _ => {}
        }
        actions
    }

    pub fn dispatch_event(&mut self, state: &mut ExecutionState, event: Event) -> Vec<Action> {
        let mut actions = Vec::new();
        match event {
            Event::ProcessStarted { .. } => {
                actions.extend(self.lifecycle.handle_event(state, event.clone()));
                // We dispatch ProcessStarted to IO module as well to trigger WatchStream.
                actions.extend(self.io.handle_event(state, event));
            }
            Event::ProcessSpawnFailed { .. } => {
                actions.extend(self.lifecycle.handle_event(state, event));
            }
            Event::ProcessExited { .. } => {
                actions.extend(self.result.handle_event(state, event));
            }
            Event::JobFdReady { .. } => {
                actions.extend(self.io.handle_event(state, event));
            }
        }
        actions
    }

    pub fn compute_timeout_ms(&self, state: &ExecutionState) -> i32 {
        self.timeout.compute_timeout_ms(state)
    }
}


pub struct Core {
    pub dispatcher: Dispatcher,
}

impl Core {
    pub fn new() -> Self {
        Self {
            dispatcher: Dispatcher::new(),
        }
    }

    pub fn resolve_effects(&self, state: &ExecutionState, actions: Vec<Action>) -> (Vec<Action>, Vec<Effect>) {
        let mut ipc_actions = Vec::new();
        let mut effects = Vec::new();
        for action in actions {
            match action {
                Action::EmitLog { level, event } => effects.push(Effect::Log { level, event }),
                Action::RegisterInterest { id, stream } => {
                    if let Some(job) = state.jobs.get(&id) {
                        if let Some(io) = job.io {
                            effects.push(Effect::WatchStream { id, io, stream });
                        }
                    }
                }
                Action::RemoveInterest { id, stream } => {
                    if let Some(job) = state.jobs.get(&id) {
                        if let Some(io) = job.io {
                            effects.push(Effect::UnwatchStream { id, io, stream });
                        }
                    }
                }
                Action::PerformIo { id } => {
                    if let Some(job) = state.jobs.get(&id) {
                        if let Some(io) = job.io {
                            effects.push(Effect::PerformIo { id, io });
                        }
                    }
                }
                Action::StartProcess { id, exec, policy } => effects.push(Effect::StartProcess { id, exec, policy }),
                Action::SignalProcess { id, signal } => {
                    if let Some(job) = state.jobs.get(&id) {
                        if let Some(process) = job.process {
                            effects.push(Effect::KillProcess {
                                id,
                                signal,
                                process,
                                is_group: job.pgroup.is_some() || job.isolated,
                            });
                        }
                    }
                }
                Action::PollProcess { id } => {
                    if let Some(job) = state.jobs.get(&id) {
                        if let Some(process) = job.process {
                            effects.push(Effect::PollProcess {
                                id,
                                process,
                            });
                        }
                    }
                }
                a => ipc_actions.push(a),
            }
        }
        (ipc_actions, effects)
    }
}
