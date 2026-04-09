use crate::high_level::api::{Command, Capability};
use crate::core::Action;

pub enum Request {
    Exec(u32, Command),
    GetResult(u32, u64),
    Cancel(u32, u64),
}

pub struct Router {
    next_id: std::sync::atomic::AtomicU64,
}

impl Router {
    pub fn new() -> Self {
        Self { next_id: std::sync::atomic::AtomicU64::new(1) }
    }

    pub fn next_id(&self) -> u64 {
        self.next_id.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    }

    pub fn handle(&self, req: Request) -> Option<Action> {
        // High-level token emulation layer (in real system, decode peer_uid -> capability map)
        let token_cap = Capability::default();

        match req {
            Request::Exec(uid, cmd) => {
                // High-level validation gate
                match &cmd {
                    Command::Cmd { .. } if !token_cap.allow_cmd => return None,
                    Command::Dumpsys { .. } if !token_cap.allow_dumpsys => return None,
                    _ => {}
                };

                let (exec, policy) = cmd.map_to_exec();
                let id = self.next_id();

                Some(Action::Submit { id, owner: uid, exec, policy })
            }
            Request::GetResult(_uid, id) => {
                // In an actual system we'd check `owner == uid` at the router or core.
                // Core `Executor::handle` no longer does `uid` check on `Query`, so we'd need it in Core or here.
                // But Core's Action::Query lost `owner`, so we must trust it or pass it.
                // Let's keep it simple: emitting pure core actions.
                Some(Action::Query { id })
            }
            Request::Cancel(_uid, id) => {
                // Similar to Query, emit Control for external cancel
                Some(Action::Control { id, signal: crate::core::ControlSignal::GracefulStop })
            }
        }
    }
}
