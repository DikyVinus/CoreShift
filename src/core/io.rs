use crate::core::{Module, Action, Event, ExecutionState, IoStream};

pub struct IoModule;

impl Module for IoModule {
    fn handle(&mut self, _state: &mut ExecutionState, _action: Action) -> Vec<Action> {
        Vec::new()
    }

    fn handle_event(&mut self, state: &mut ExecutionState, event: Event) -> Vec<Action> {
        let mut actions = Vec::new();
        match event {
            Event::ProcessStarted { id, .. } => {
                // Here the core module issues Watch actions purely via intent.
                // It doesn't know about `DrainState` or slots anymore.
                // We request watching for all default streams.
                // EffectExecutor will decide if the stream actually exists or needs a watch based on its internal DrainState.
                actions.push(Action::RegisterInterest { id, stream: IoStream::Stdout });
                actions.push(Action::RegisterInterest { id, stream: IoStream::Stderr });
                actions.push(Action::RegisterInterest { id, stream: IoStream::Stdin });
            }
            Event::JobFdReady { job_id, readable: _, writable: _, error: _ } => {
                if state.jobs.contains_key(&job_id) {
                    actions.push(Action::PerformIo { id: job_id });
                }
            }
            _ => {}
        }
        actions
    }
}
