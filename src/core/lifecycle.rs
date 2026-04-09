use crate::core::{Module, Action, Event, ExecutionState, JobLifecycle, JobState};

pub struct LifecycleModule;

impl Module for LifecycleModule {
    fn handle(&mut self, state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Admitted { id, owner, exec, policy } => {
                let job = JobState {
                    id,
                    owner,
                    process: None,
                    io: None,
                    timed_out: false,
                    pgroup: None,
                    isolated: false,
                };
                state.jobs.insert(id, job);
                state.lifecycle.insert(id, JobLifecycle::Admitted);
                actions.push(Action::StartProcess { id, exec, policy });
            }
            Action::SignalProcess { id, signal } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    if signal == crate::core::ControlSignal::GracefulStop {
                        job.timed_out = true;
                        state.lifecycle.insert(id, JobLifecycle::Terminating);
                    } else if signal == crate::core::ControlSignal::ForceKill {
                        state.lifecycle.insert(id, JobLifecycle::Killed);
                    }
                    // Signal is sent to process; transition is noted.
                    // The Control Action mapping emits `Action::Controlled`
                    // which serves as the Ack, so we don't emit CancelAck here.
                }
            }
            _ => {}
        }
        actions
    }

    fn handle_event(&mut self, state: &mut ExecutionState, event: Event) -> Vec<Action> {
        let mut actions = Vec::new();
        match event {
            Event::ProcessStarted { id, process: process_handle, io: io_handle } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    job.process = Some(process_handle);
                    job.io = Some(io_handle);
                    state.lifecycle.insert(id, JobLifecycle::Running);
                    actions.push(Action::Started { id });
                }
            }
            Event::ProcessSpawnFailed { id, err } => {
                state.lifecycle.insert(id, JobLifecycle::Finished);
                actions.push(Action::Rejected { id });
                actions.push(Action::EmitLog {
                    level: crate::core::LogLevel::Error,
                    event: crate::core::LogEvent::Error { id, err },
                });
            }
            _ => {}
        }
        actions
    }
}
