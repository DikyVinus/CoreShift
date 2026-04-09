use crate::core::{Module, Action, ExecutionState};
use std::collections::HashMap;
use std::time::{Instant, Duration};

struct TimeoutEntry {
    id: u64,
    state: TimeoutState,
    deadline: Instant,
    kill_grace_ms: u32,
}

enum TimeoutState {
    WaitingForDeadline,
    WaitingForKillGrace(Instant),
}

pub struct TimeoutPolicyModule {
    tracking: HashMap<u64, TimeoutEntry>,
}

impl Default for TimeoutPolicyModule {
    fn default() -> Self {
        Self::new()
    }
}

impl TimeoutPolicyModule {
    pub fn new() -> Self {
        Self { tracking: HashMap::new() }
    }

    pub fn compute_timeout_ms(&self, _state: &ExecutionState) -> i32 {
        let mut min_ms: i32 = -1;
        let now = Instant::now();
        for entry in self.tracking.values() {
            let deadline = match entry.state {
                TimeoutState::WaitingForDeadline => entry.deadline,
                TimeoutState::WaitingForKillGrace(d) => d,
            };

            let ms = if deadline > now {
                deadline.duration_since(now).as_millis() as i32
            } else {
                0
            };

            if min_ms == -1 || ms < min_ms {
                min_ms = ms;
            }
        }
        min_ms
    }
}

impl Module for TimeoutPolicyModule {
    fn handle(&mut self, _state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Admitted { id, policy, .. } => {
                actions.push(Action::EmitLog {
                    level: crate::core::LogLevel::Info,
                    event: crate::core::LogEvent::Submit { id },
                });

                if let Some(deadline) = policy.timeout_ms.map(|t| Instant::now() + Duration::from_millis(t as u64)) {
                    self.tracking.insert(id, TimeoutEntry {
                        id,
                        state: TimeoutState::WaitingForDeadline,
                        deadline,
                        kill_grace_ms: policy.kill_grace_ms,
                    });
                }
            }
            Action::Finished { id, .. } => {
                self.tracking.remove(&id);
            }
            _ => {}
        }
        actions
    }

    fn handle_event(&mut self, _state: &mut ExecutionState, _event: crate::core::Event) -> Vec<Action> {
        let mut actions = Vec::new();
        let now = Instant::now();

        for entry in self.tracking.values_mut() {
            match entry.state {
                TimeoutState::WaitingForDeadline => {
                    if now >= entry.deadline {
                        actions.push(Action::EmitLog {
                            level: crate::core::LogLevel::Info,
                            event: crate::core::LogEvent::Timeout { id: entry.id },
                        });
                        entry.state = TimeoutState::WaitingForKillGrace(now + Duration::from_millis(entry.kill_grace_ms as u64));
                        actions.push(Action::TimeoutReached { id: entry.id });
                    }
                }
                TimeoutState::WaitingForKillGrace(grace_deadline) => {
                    if now >= grace_deadline {
                        actions.push(Action::KillDeadlineReached { id: entry.id });
                    }
                }
            }
        }

        actions
    }
}

pub struct AdmissionControlModule {
    pub max_jobs: usize,
    active_jobs: usize,
}

impl Default for AdmissionControlModule {
    fn default() -> Self {
        Self { max_jobs: 64, active_jobs: 0 }
    }
}

impl Module for AdmissionControlModule {
    fn handle(&mut self, _state: &mut ExecutionState, action: Action) -> Vec<Action> {
        let mut actions = Vec::new();
        match action {
            Action::Submit { id, owner, exec, policy } => {
                if self.active_jobs >= self.max_jobs {
                    actions.push(Action::Rejected { id });
                    actions.push(Action::EmitLog {
                        level: crate::core::LogLevel::Error,
                        event: crate::core::LogEvent::Error { id, err: format!("max_jobs {} reached", self.max_jobs) }
                    });
                } else {
                    self.active_jobs += 1;
                    actions.push(Action::Admitted { id, owner, exec, policy });
                }
            }
            Action::Finished { .. } => {
                if self.active_jobs > 0 {
                    self.active_jobs -= 1;
                }
            }
            _ => {}
        }
        actions
    }

    fn handle_event(&mut self, _state: &mut ExecutionState, _event: crate::core::Event) -> Vec<Action> {
        Vec::new()
    }
}
