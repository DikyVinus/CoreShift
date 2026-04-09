use crate::low_level::reactor::Fd;
use crate::low_level::spawn::SysError;
use std::os::unix::io::{RawFd, AsRawFd};
use libc::{c_void, socklen_t, ucred, SO_PEERCRED, SOL_SOCKET, accept4, SOCK_NONBLOCK, SOCK_CLOEXEC};
use crate::mid_level::router::Router;
use crate::core::{Action, ExecOutcome};
use crate::low_level::reactor::{Event, Token};
use std::collections::HashMap;

pub struct IpcModule {
    pub fd: Fd,
    pub server_token: Option<Token>,
    pub router: Router,
    pub clients: HashMap<u32, RawFd>,
    pub job_to_client: HashMap<u64, u32>,
    next_client_id: u32,
}

impl IpcModule {
    pub fn new(fd: Fd, router: Router, token: Token) -> Self {
        Self {
            fd,
            server_token: Some(token),
            router,
            clients: HashMap::new(),
            job_to_client: HashMap::new(),
            next_client_id: 1
        }
    }

    /// Verifies the credentials of a peer on a connected Unix domain socket.
    /// Returns `Ok(uid)` if successful, or a `SysError` if validation fails.
    pub fn verify_peer_credentials(&self, peer_fd: RawFd) -> Result<u32, SysError> {
        let mut cred: ucred = unsafe { std::mem::zeroed() };
        let mut len: socklen_t = std::mem::size_of::<ucred>() as socklen_t;

        let ret = unsafe {
            libc::getsockopt(
                peer_fd,
                SOL_SOCKET,
                SO_PEERCRED,
                &mut cred as *mut ucred as *mut c_void,
                &mut len as *mut socklen_t,
            )
        };

        if ret != 0 {
            return Err(SysError::sys(
                std::io::Error::last_os_error().raw_os_error().unwrap_or(0),
                "getsockopt(SO_PEERCRED)"
            ));
        }

        Ok(cred.uid)
    }

    /// Single-connection handle ready for IPC server.
    pub fn handle_ready(&mut self) -> Vec<Action> {
        let mut actions = Vec::new();

        loop {
            let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
            let mut addr_len: socklen_t = std::mem::size_of::<libc::sockaddr_un>() as socklen_t;

            let client_fd = unsafe {
                accept4(
                    self.fd.as_raw_fd(),
                    &mut addr as *mut libc::sockaddr_un as *mut libc::sockaddr,
                    &mut addr_len as *mut socklen_t,
                    SOCK_NONBLOCK | SOCK_CLOEXEC,
                )
            };

            if client_fd < 0 {
                let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                if err == libc::EAGAIN || err == libc::EWOULDBLOCK {
                    return actions; // Non-blocking, no more clients for now
                }
                return actions;
            }

            if let Ok(client_fd_obj) = Fd::new(client_fd, "accept4") {
                // 1. Extract UID (Verification left to high_level policy engine)
                let uid = match self.verify_peer_credentials(client_fd) {
                    Ok(u) => u,
                    Err(_) => continue, // Drop on verification failure
                };

                // 2. Decode Request
                let mut valid_req = false;
                if let Ok(Some(req)) = decode_request(&client_fd_obj, uid) {
                    valid_req = true;

                    let client_id = self.next_client_id;
                    self.next_client_id = self.next_client_id.wrapping_add(1);
                    if self.next_client_id == 0 { self.next_client_id = 1; }

                    self.clients.insert(client_id, client_fd);

                    // 3. Router Handle (emits pure actions)
                    if let Some(action) = self.router.handle(req) {
                        // Store the job mapping manually if it's a creation action
                        match &action {
                            Action::Submit { id, .. } | Action::Query { id } | Action::Control { id, .. } => {
                                self.job_to_client.insert(*id, client_id);
                            }
                            _ => {}
                        }
                        actions.push(action);
                    } else {
                        // Invalid request mapped to None
                        let _ = encode_response(&client_fd_obj, WireResponse::Error);
                        self.clients.remove(&client_id);
                        valid_req = false;
                    }
                }

                if valid_req {
                    std::mem::forget(client_fd_obj); // Kept alive, mapped in self.clients
                } else {
                    drop(client_fd_obj); // Drop naturally on parse errors
                }
            }
        }
    }

    pub fn handle_event(&mut self, event: &Event) -> Vec<Action> {
        if Some(event.token) == self.server_token && event.readable {
            self.handle_ready()
        } else {
            Vec::new()
        }
    }

    pub fn intercept_action(&mut self, action: &Action) {
        match action {
            Action::Started { id } => {
                if let Some(client_id) = self.job_to_client.remove(id) {
                    if let Some(fd_raw) = self.clients.remove(&client_id) {
                        if let Ok(fd_obj) = Fd::new(fd_raw, "respond") {
                            let _ = encode_response(&fd_obj, WireResponse::Exec(*id));
                        }
                    }
                }
            }
            Action::Controlled { id } => {
                if let Some(client_id) = self.job_to_client.remove(id) {
                    if let Some(fd_raw) = self.clients.remove(&client_id) {
                        if let Ok(fd_obj) = Fd::new(fd_raw, "respond") {
                            let _ = encode_response(&fd_obj, WireResponse::CancelOk);
                        }
                    }
                }
            }
            Action::QueryResult { id, result } => {
                if let Some(client_id) = self.job_to_client.remove(id) {
                    if let Some(fd_raw) = self.clients.remove(&client_id) {
                        if let Ok(fd_obj) = Fd::new(fd_raw, "respond") {
                            let _ = encode_response(&fd_obj, WireResponse::Result(result.clone()));
                        }
                    }
                }
            }
            Action::Rejected { id } => {
                if let Some(client_id) = self.job_to_client.remove(id) {
                    if let Some(fd_raw) = self.clients.remove(&client_id) {
                        if let Ok(fd_obj) = Fd::new(fd_raw, "respond") {
                            let _ = encode_response(&fd_obj, WireResponse::Error);
                        }
                    }
                }
            }
            Action::Finished { id, result } => {
                if let Some(client_id) = self.job_to_client.remove(id) {
                    if let Some(fd_raw) = self.clients.remove(&client_id) {
                        let outcome = ExecOutcome {
                            id: *id,
                            result: result.clone(),
                        };
                        if let Ok(fd_obj) = Fd::new(fd_raw, "respond") {
                            let _ = encode_response(&fd_obj, WireResponse::Result(Some(outcome)));
                        }
                    }
                }
            }
            _ => {}
        }
    }
}

// Format:
// [u32 len][payload]
// payload:
// [u8 type]
// if type == 1 (Exec): bincode or custom JSON? For now let's just use JSON or manual. Since there are dependencies on Serde, we will use serde_json for the payload.
// Wait! To keep it simple and independent, let's use serde_json since we saw it compiling before.

use crate::mid_level::router::Request;
use crate::high_level::api::Command;

fn decode_request(fd: &Fd, uid: u32) -> Result<Option<Request>, SysError> {
    let mut len_buf = [0u8; 4];
    let n = fd.read(len_buf.as_mut_ptr(), 4)?;
    if n != 4 {
        return Ok(None);
    }

    let len = u32::from_le_bytes(len_buf) as usize;
    if len > 10 * 1024 * 1024 {
        return Ok(None); // Drop effectively on invalid request format size limits to prevent crash payload propagation
    }

    let mut payload = vec![0u8; len];
    let mut total_read = 0;
    while total_read < len {
        let n = fd.read(payload[total_read..].as_mut_ptr(), len - total_read)?;
        if n == 0 {
            return Ok(None);
        }
        total_read += n;
    }

    if payload.is_empty() {
        return Ok(None);
    }

    let req_type = payload[0];
    match req_type {
        1 => {
            if let Ok(req) = serde_json::from_slice::<Command>(&payload[1..]) {
                Ok(Some(Request::Exec(uid, req)))
            } else {
                Ok(None)
            }
        }
        2 => {
            if payload.len() == 9 {
                let mut id_buf = [0u8; 8];
                id_buf.copy_from_slice(&payload[1..9]);
                let id = u64::from_le_bytes(id_buf);
                Ok(Some(Request::GetResult(uid, id)))
            } else {
                Ok(None)
            }
        }
        3 => {
            if payload.len() == 9 {
                let mut id_buf = [0u8; 8];
                id_buf.copy_from_slice(&payload[1..9]);
                let id = u64::from_le_bytes(id_buf);
                Ok(Some(Request::Cancel(uid, id)))
            } else {
                Ok(None)
            }
        }
        _ => Ok(None),
    }
}

enum WireResponse {
    Exec(u64),
    Result(Option<ExecOutcome>),
    CancelOk,
    Error,
}

fn encode_response(fd: &Fd, resp: WireResponse) -> Result<(), SysError> {
    let payload = match resp {
        WireResponse::Exec(id) => {
            let mut p = vec![1u8];
            p.extend_from_slice(&id.to_le_bytes());
            p
        }
        WireResponse::Result(res) => {
            let mut p = vec![2u8];
            let json = serde_json::to_vec(&res).unwrap_or_default();
            p.extend_from_slice(&json);
            p
        }
        WireResponse::CancelOk => {
            vec![3u8]
        }
        WireResponse::Error => {
            vec![4u8]
        }
    };

    let len = payload.len() as u32;
    let len_buf = len.to_le_bytes();

    fd.write(len_buf.as_ptr(), 4)?;
    let mut total_written = 0;
    while total_written < payload.len() {
        let n = fd.write(payload[total_written..].as_ptr(), payload.len() - total_written)?;
        if n == 0 {
            break;
        }
        total_written += n;
    }
    Ok(())
}
