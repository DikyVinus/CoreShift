use crate::core::{Module, Action, Event, ExecutionState};

pub struct ProcessModule;

impl Module for ProcessModule {
    fn handle(&mut self, state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Control { id, signal } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    if job.process.is_some() && !job.timed_out {
                        // Enforce execution rules: Translate external Control into internal SignalProcess intent
                        actions.push(Action::SignalProcess { id, signal });
                        actions.push(Action::Controlled { id });
                    }
                }
            }
            Action::TimeoutReached { id } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    if job.process.is_some() && !job.timed_out {
                        actions.push(Action::SignalProcess { id, signal: crate::core::ControlSignal::GracefulStop });
                    }
                }
            }
            Action::KillDeadlineReached { id } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    if job.process.is_some() && !job.timed_out {
                        actions.push(Action::SignalProcess { id, signal: crate::core::ControlSignal::ForceKill });
                    }
                }
            }
            // `StartProcess`, `SignalProcess`, and `PollProcess` are mapped directly to `Effect`s in `resolve_effects`.
            // We don't generate additional intent from them here.
            _ => {}
        }
        actions
    }

    fn handle_event(&mut self, _state: &mut ExecutionState, _event: Event) -> Vec<Action> {
        Vec::new()
    }
}
