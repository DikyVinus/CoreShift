use crate::low_level::spawn::{SysError, syscall_ret};
use libc::sigset_t;

pub struct SignalRuntime;

#[inline(always)]
pub fn get_clk_tck() -> u64 {
    unsafe { libc::sysconf(libc::_SC_CLK_TCK) as u64 }
}

impl SignalRuntime {
    pub fn empty_set() -> sigset_t {
        let mut set: sigset_t = unsafe { std::mem::zeroed() };
        unsafe { libc::sigemptyset(&mut set) };
        set
    }

    pub fn set_with(signals: &[i32]) -> sigset_t {
        let mut set: sigset_t = unsafe { std::mem::zeroed() };
        unsafe { libc::sigemptyset(&mut set) };
        for &sig in signals {
            unsafe { libc::sigaddset(&mut set, sig) };
        }
        set
    }

    pub fn unblock_all() -> Result<(), SysError> {
        let empty_mask = Self::empty_set();
        let r = unsafe { libc::sigprocmask(libc::SIG_SETMASK, &empty_mask, std::ptr::null_mut()) };
        syscall_ret(r, "sigprocmask")
    }

    pub fn reset_default(sig: i32) {
        unsafe { libc::signal(sig, libc::SIG_DFL) };
    }
}
use libc::{pid_t, c_char};
use std::ffi::CString;
use std::ptr;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CancelPolicy {
    None,
    Graceful, // implies term then kill
    Kill,     // implies direct kill
}

impl Default for CancelPolicy {
    fn default() -> Self {
        CancelPolicy::None
    }
}

#[derive(Debug, Clone, Copy)]
pub struct ProcessGroup {
    pub leader: Option<pid_t>,
    pub isolated: bool, // Corresponds to setsid
}

impl ProcessGroup {
    pub fn new(leader: Option<pid_t>, isolated: bool) -> Self {
        Self { leader, isolated }
    }
}

impl Default for ProcessGroup {
    fn default() -> Self {
        Self { leader: None, isolated: false }
    }
}

use arrayvec::ArrayVec;

pub enum ExecArgv {
    Dynamic(Vec<CString>),
}

pub struct ExecContext {
    pub argv: ExecArgv,
    pub envp: Option<Vec<CString>>,
    pub cwd: Option<CString>,
}

impl ExecContext {
    pub fn new(
        argv: Vec<String>,
        env: Option<Vec<String>>,
        cwd: Option<String>,
    ) -> Self {
        let mut c_argv: Vec<CString> = argv
            .into_iter()
            .filter_map(|s| CString::new(s).ok())
            .collect();

        if c_argv.is_empty() {
            c_argv.push(CString::new("/bin/false").unwrap());
        }

        let c_envp = match env {
            Some(vars) => {
                let e_vars: Vec<CString> = vars
                    .into_iter()
                    .filter_map(|s| CString::new(s).ok())
                    .collect();
                Some(e_vars)
            }
            None => None,
        };

        let c_cwd = match cwd {
            Some(c) => CString::new(c).ok(),
            None => None,
        };

        Self {
            argv: ExecArgv::Dynamic(c_argv),
            envp: c_envp,
            cwd: c_cwd,
        }
    }

    pub fn get_argv_ptrs(&self) -> ArrayVec<*mut c_char, 64> {
        let mut ptrs = ArrayVec::new();
        match &self.argv {
            ExecArgv::Dynamic(v) => {
                for s in v {
                    if ptrs.try_push(s.as_ptr() as *mut c_char).is_err() { break; }
                }
            }
        }
        if ptrs.is_full() {
            ptrs.pop(); // Ensure room for null terminator
        }
        let _ = ptrs.try_push(ptr::null_mut());
        ptrs
    }

    pub fn get_envp_ptrs(&self) -> Option<ArrayVec<*mut c_char, 64>> {
        self.envp.as_ref().map(|envp| {
            let mut ptrs = ArrayVec::new();
            for s in envp {
                if ptrs.try_push(s.as_ptr() as *mut c_char).is_err() { break; }
            }
            if ptrs.is_full() {
                ptrs.pop(); // Ensure room for null terminator
            }
            let _ = ptrs.try_push(ptr::null_mut());
            ptrs
        })
    }
}
