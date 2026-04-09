use crate::low_level::spawn::{SysError, syscall_ret};
use std::io::Error as IoError;

#[inline(always)]
fn errno() -> i32 {
    IoError::last_os_error().raw_os_error().unwrap_or(0)
}

/// A safe wrapper for file descriptors ensuring they are closed when dropped.
#[derive(Clone)]
pub struct Fd(RawFd);

use std::os::unix::io::{AsRawFd, RawFd};

impl AsRawFd for Fd {
    fn as_raw_fd(&self) -> RawFd {
        self.0
    }
}

impl Fd {
    #[inline(always)]
    pub fn new(fd: RawFd, op: &'static str) -> Result<Self, SysError> {
        if fd < 0 {
            Err(SysError::sys(errno(), op))
        } else {
            Ok(Self(fd))
        }
    }

    #[inline(always)]
    pub fn raw(&self) -> RawFd {
        self.0
    }

    // into_raw transfers ownership of the FD to the caller.
    // Caller must ensure it is eventually closed or handed off.
    #[inline(always)]
    pub fn into_raw(self) -> RawFd {
        let fd = self.0;
        std::mem::forget(self);
        fd
    }
    
    pub fn dup2(&self, target: RawFd) -> Result<(), SysError> {
        loop {
            let r = unsafe { libc::dup2(self.0, target) };
            if r < 0 {
                let e = errno();
                if e == libc::EINTR { continue; }
                return syscall_ret(r, "dup2");
            }
            return Ok(());
        }
    }

    pub fn set_nonblock(&self) -> Result<(), SysError> {
        let flags = unsafe { libc::fcntl(self.0, libc::F_GETFL) };
        syscall_ret(flags, "fcntl(F_GETFL)")?;
        let r = unsafe { libc::fcntl(self.0, libc::F_SETFL, flags | libc::O_NONBLOCK) };
        syscall_ret(r, "fcntl(F_SETFL)")
    }

    pub fn set_cloexec(&self) -> Result<(), SysError> {
        let flags = unsafe { libc::fcntl(self.0, libc::F_GETFD) };
        syscall_ret(flags, "fcntl(F_GETFD)")?;
        let r = unsafe { libc::fcntl(self.0, libc::F_SETFD, flags | libc::FD_CLOEXEC) };
        syscall_ret(r, "fcntl(F_SETFD)")
    }
    
    pub fn read(&self, buf: *mut u8, count: usize) -> Result<usize, SysError> {
        loop {
            let n = unsafe { libc::read(self.0, buf as *mut libc::c_void, count) };
            if n < 0 {
                let e = errno();
                if e == libc::EINTR { continue; }
                syscall_ret(-1, "read")?;
            }
            return Ok(n as usize);
        }
    }

    pub fn write(&self, buf: *const u8, count: usize) -> Result<usize, SysError> {
        loop {
            let n = unsafe { libc::write(self.0, buf as *const libc::c_void, count) };
            if n < 0 {
                let e = errno();
                if e == libc::EINTR { continue; }
                syscall_ret(-1, "write")?;
            }
            return Ok(n as usize);
        }
    }
}

impl Drop for Fd {
    fn drop(&mut self) {
        if self.0 >= 0 {
            unsafe { libc::close(self.0); }
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Token(pub u64);

#[derive(Clone, Copy, Debug)]
pub struct Event {
    pub token: Token,
    pub readable: bool,
    pub writable: bool,
    pub error: bool,
}

pub struct Reactor {
    epfd: RawFd,
    next_token: u64,
    events_buf: Vec<libc::epoll_event>,
    signalfd: Option<Fd>,
    pub sigchld_token: Option<Token>,
}

impl Reactor {
    pub fn new() -> Result<Self, SysError> {
        let epfd = unsafe { libc::epoll_create1(libc::EPOLL_CLOEXEC) };
        syscall_ret(epfd, "epoll_create1")?;
        Ok(Self { epfd, next_token: 1, events_buf: Vec::with_capacity(64), signalfd: None, sigchld_token: None })
    }

    pub fn setup_signalfd(&mut self) -> Result<(), SysError> {
        let mut mask: libc::sigset_t = unsafe { std::mem::zeroed() };
        unsafe { libc::sigemptyset(&mut mask) };
        unsafe { libc::sigaddset(&mut mask, libc::SIGCHLD) };

        // Block SIGCHLD so signalfd can intercept it
        let r = unsafe { libc::sigprocmask(libc::SIG_BLOCK, &mask, std::ptr::null_mut()) };
        syscall_ret(r, "sigprocmask")?;

        let sfd = unsafe { libc::signalfd(-1, &mask, libc::SFD_NONBLOCK | libc::SFD_CLOEXEC) };
        syscall_ret(sfd, "signalfd")?;

        let fd = Fd::new(sfd, "signalfd")?;
        let token = self.add(&fd, true, false)?;

        self.signalfd = Some(fd);
        self.sigchld_token = Some(token);

        Ok(())
    }

    pub fn drain_signalfd(&self) {
        if let Some(fd) = &self.signalfd {
            let mut buf = [0u8; std::mem::size_of::<libc::signalfd_siginfo>()];
            loop {
                match fd.read(buf.as_mut_ptr(), buf.len()) {
                    Ok(n) if n < buf.len() => break,
                    Ok(_) => continue,
                    Err(_) => break,
                }
            }
        }
    }

    #[inline(always)]
    pub fn add(&mut self, fd: &Fd, readable: bool, writable: bool) -> Result<Token, SysError> {
        let token = Token(self.next_token);
        self.next_token += 1;
        self.add_with_token(fd.raw(), token, readable, writable)?;
        Ok(token)
    }

    #[inline(always)]
    pub fn add_with_token(&mut self, raw_fd: RawFd, token: Token, readable: bool, writable: bool) -> Result<(), SysError> {
        let mut events = libc::EPOLLET as u32;
        if readable { events |= libc::EPOLLIN as u32; }
        if writable { events |= libc::EPOLLOUT as u32; }
        let mut ev = libc::epoll_event {
            events,
            u64: token.0,
        };
        let r = unsafe { libc::epoll_ctl(self.epfd, libc::EPOLL_CTL_ADD, raw_fd, &mut ev) };
        syscall_ret(r, "epoll_ctl_add")?;
        Ok(())
    }

    #[inline(always)]
    pub fn del(&self, fd: &Fd) {
        self.del_raw(fd.raw());
    }

    #[inline(always)]
    pub fn del_raw(&self, raw: RawFd) {
        unsafe {
            let _ = libc::epoll_ctl(self.epfd, libc::EPOLL_CTL_DEL, raw, std::ptr::null_mut());
        }
    }

    #[inline(always)]
    pub fn wait(&mut self, buffer: &mut Vec<Event>, max_events: usize, timeout: i32) -> Result<usize, SysError> {
        buffer.clear();

        // Ensure buffer has enough capacity
        if buffer.capacity() < max_events {
            buffer.reserve(max_events.saturating_sub(buffer.len()));
        }

        if self.events_buf.capacity() < max_events {
            self.events_buf.reserve(max_events.saturating_sub(self.events_buf.len()));
        }

        let n = unsafe {
            libc::epoll_wait(self.epfd, self.events_buf.as_mut_ptr(), max_events as i32, timeout)
        };
        
        if n > 0 {
            for i in 0..n as usize {
                let ev = unsafe { *self.events_buf.as_ptr().add(i) };
                let is_read = (ev.events & libc::EPOLLIN as u32) != 0;
                let is_write = (ev.events & libc::EPOLLOUT as u32) != 0;
                let is_err = (ev.events & (libc::EPOLLERR | libc::EPOLLHUP) as u32) != 0;
                
                buffer.push(Event {
                    token: Token(ev.u64),
                    readable: is_read || is_err,
                    writable: is_write || is_err,
                    error: is_err,
                });
            }
            return Ok(n as usize);
        }
        
        if n < 0 {
            let e = errno();
            if e == libc::EINTR {
                return Ok(0);
            }
            return Err(SysError::sys(e, "epoll_wait"));
        }
        Ok(0)
    }

    pub fn fd(&self) -> RawFd {
        self.epfd
    }
}

impl Drop for Reactor {
    fn drop(&mut self) {
        if self.epfd >= 0 {
            unsafe { libc::close(self.epfd); }
        }
    }
}
