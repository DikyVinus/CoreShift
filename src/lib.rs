#![allow(non_snake_case)]

#[macro_use]
pub mod low_level;

pub mod mid_level;

pub mod high_level;
pub mod core;
pub mod runtime;

pub fn run_daemon() -> Result<(), crate::low_level::spawn::SysError> {
    use crate::core::{Core, ExecutionState};
    use crate::mid_level::ipc::IpcModule;
    use crate::mid_level::router::Router;
    use crate::low_level::reactor::{Reactor, Fd, Token};

    let mut reactor = Reactor::new()?;
    let ipc_fd = Fd::new(unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK, 0) }, "ipc")?;
    let ipc_token = Token(1);
    use std::os::unix::io::AsRawFd;
    reactor.add_with_token(ipc_fd.as_raw_fd(), ipc_token, true, false)?;

    let mut core = Core::new();
    let mut state = ExecutionState::new();
    let mut effect_executor = crate::runtime::EffectExecutor::new(reactor);
    let mut ipc = IpcModule::new(ipc_fd, Router::new(), ipc_token);

    loop {
        let mut reactor_events = Vec::new();
        let timeout = core.dispatcher.compute_timeout_ms(&state);

        let sys_events = match effect_executor.process_reactor_events(&mut reactor_events, timeout) {
            Ok(evs) => evs,
            Err(_) => continue,
        };

        let mut actions = Vec::new();

        for rev in reactor_events {
            actions.extend(ipc.handle_event(&rev));
        }

        for ev in sys_events {
            actions.extend(core.dispatcher.dispatch_event(&mut state, ev));
        }

        // Resolve actions deterministically
        let mut queue = std::collections::VecDeque::from(actions);

        while let Some(action) = queue.pop_front() {
            let new_actions = core.dispatcher.dispatch(&mut state, action.clone());

            let (ipc_acts, effects) = core.resolve_effects(&state, vec![action]);

            for ipc_action in ipc_acts {
                ipc.intercept_action(&ipc_action);
            }

            for effect in effects {
                if let Some(event) = effect_executor.apply(effect) {
                    let ev_actions = core.dispatcher.dispatch_event(&mut state, event);
                    queue.extend(ev_actions);
                }
            }

            queue.extend(new_actions);
        }
    }
}
