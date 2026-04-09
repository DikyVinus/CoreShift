use crate::core::{Module, Action, Event, ExecutionState};
use std::time::{Instant, Duration};

pub struct ResultModule;

impl Module for ResultModule {
    fn handle(&mut self, state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Query { id } => {
                if let Some(_res) = state.results.get(&id) {
                    let result = state.results.remove(&id).map(|r| r.result).unwrap();
                    let outcome = crate::core::ExecOutcome {
                        id,
                        result,
                    };
                    actions.push(Action::QueryResult { id, result: Some(outcome) });
                } else {
                    actions.push(Action::QueryResult { id, result: None });
                }
            }
            Action::Finished { id, result } => {
                if let Some(job) = state.jobs.remove(&id) {
                    let status = result.as_ref().ok().and_then(|r| r.status);
                    actions.push(Action::EmitLog {
                        level: crate::core::LogLevel::Info,
                        event: crate::core::LogEvent::Exit { id, status },
                    });

                    state.results.insert(id, crate::core::StoredResult {
                        result: result.clone(),
                        owner: job.owner,
                        created: Instant::now(),
                    });
                }
                self.evict_results(state);
            }
            _ => {}
        }
        actions
    }

    fn handle_event(&mut self, state: &mut ExecutionState, event: Event) -> Vec<Action> {
        let mut actions = Vec::new();
        match event {
            Event::ProcessExited { id, status } => {
                if let Some(job) = state.jobs.get_mut(&id) {
                    // For now, assume process exits without explicit buffered IO completion.
                    // A proper design will receive IoDataReceived or the drain contents from runtime.
                    // We simply synthesize empty stdout/stderr if the drain parts aren't forwarded.
                    let result = crate::core::ExecResult {
                        status,
                        stdout: Vec::new(),
                        stderr: Vec::new(),
                        timed_out: job.timed_out,
                    };

                    actions.push(Action::Finished { id, result: Ok(result) });
                }
            }
            _ => {}
        }
        actions
    }
}

impl ResultModule {
    fn evict_results(&self, state: &mut ExecutionState) {
        let now = Instant::now();
        let ttl = Duration::from_secs(60 * 5); // 5 minutes TTL
        let max_results = 1000;

        state.results.retain(|_, res| {
            now.duration_since(res.created) <= ttl
        });

        if state.results.len() > max_results {
            let mut entries: Vec<_> = state.results.iter().map(|(&k, v)| (k, v.created)).collect();
            entries.sort_by_key(|&(_, created)| created);
            let to_remove = state.results.len() - max_results;
            for (id, _) in entries.into_iter().take(to_remove) {
                state.results.remove(&id);
            }
        }
    }
}
