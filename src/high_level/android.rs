#[cfg(target_os = "android")]
pub fn getprop(key: &str) -> Option<String> {
    use libc::c_char;

    let mut buf = [0u8; 92]; // PROP_VALUE_MAX

    let key_c = std::ffi::CString::new(key).ok()?;

    let len = unsafe {
        crate::low_level::spawn::__system_property_get(
            key_c.as_ptr() as *const c_char,
            buf.as_mut_ptr() as *mut c_char,
        )
    };

    if len > 0 {
        Some(String::from_utf8_lossy(&buf[..len as usize]).into_owned())
    } else {
        None
    }
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_set(
        name: *const libc::c_char,
        value: *const libc::c_char,
    ) -> libc::c_int;
}

#[cfg(target_os = "android")]
pub fn setprop(key: &str, value: &str) -> bool {
    let k = std::ffi::CString::new(key).ok();
    let v = std::ffi::CString::new(value).ok();

    if let (Some(k), Some(v)) = (k, v) {
        unsafe {
            __system_property_set(k.as_ptr(), v.as_ptr()) == 0
        }
    } else {
        false
    }
}

use crate::core::CancelPolicy;

pub struct ExecConfig {
    pub timeout_ms: Option<u32>,
    pub kill_grace_ms: u32,
    pub cancel: CancelPolicy,
    pub max_output: usize,
}

// Emulating the old struct to allow high_level::api mapping.
// It could just return (ExecSpec, ExecPolicy) directly but we keep it modular.
pub struct AndroidExecRequest {
    pub argv: Vec<String>,
    pub stdin: Option<Vec<u8>>,
    pub capture_stdout: bool,
    pub capture_stderr: bool,
    pub timeout_ms: Option<u32>,
    pub kill_grace_ms: u32,
    pub cancel: CancelPolicy,
    pub max_output: usize,
}

pub fn cmd(service: &str, args: &[&str], cfg: ExecConfig) -> AndroidExecRequest {
    let mut argv = Vec::with_capacity(2 + args.len());
    argv.push("/system/bin/cmd".to_string());
    argv.push(service.to_string());
    for a in args {
        argv.push(a.to_string());
    }

    AndroidExecRequest {
        argv,
        stdin: None,
        capture_stdout: true,
        capture_stderr: true,
        timeout_ms: cfg.timeout_ms,
        kill_grace_ms: cfg.kill_grace_ms,
        cancel: cfg.cancel,
        max_output: cfg.max_output,
    }
}

pub fn dumpsys(service: &str, args: &[&str], cfg: ExecConfig) -> AndroidExecRequest {
    let mut argv = Vec::with_capacity(2 + args.len());
    argv.push("/system/bin/dumpsys".to_string());
    argv.push(service.to_string());
    for a in args {
        argv.push(a.to_string());
    }

    AndroidExecRequest {
        argv,
        stdin: None,
        capture_stdout: true,
        capture_stderr: true,
        timeout_ms: cfg.timeout_ms,
        kill_grace_ms: cfg.kill_grace_ms,
        cancel: cfg.cancel,
        max_output: cfg.max_output,
    }
}
