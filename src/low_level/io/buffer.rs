use crate::low_level::reactor::Fd;
use crate::low_level::spawn::SysError;

const READ_CHUNK: usize = 65536;

#[derive(Default)]
#[repr(align(64))]
pub struct BufferState {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub limit: usize,
}

impl BufferState {
    pub fn new(limit: usize) -> Self {
        Self {
            stdout: Vec::with_capacity(1024),
            stderr: Vec::with_capacity(1024),
            limit,
        }
    }

    #[inline(always)]
    pub fn read_from_fd(&mut self, fd: &Fd, is_stdout: bool, early_exit: &mut Option<impl FnMut(&[u8]) -> bool>) -> Result<bool, SysError> {
        let dest = if is_stdout { &mut self.stdout } else { &mut self.stderr };

        loop {
            let cap = dest.capacity();
            let len = dest.len();
            let remaining_limit = self.limit.saturating_sub(len);

            if remaining_limit == 0 {
                // Limit reached, just discard data.
                let mut drop_buf = [0u8; 8192];
                match fd.read(drop_buf.as_mut_ptr(), drop_buf.len()) {
                    Ok(n) if n > 0 => continue,
                    Ok(_) => {
                        return Ok(true); // EOF
                    }
                    Err(SysError::Syscall { code, .. }) if code == libc::EAGAIN || code == libc::EWOULDBLOCK => return Ok(false), // Would block
                    Err(e) => {
                        return Err(e);
                    }
                }
            }

            // Ensure capacity and read directly into uninitialized space
            let to_read = remaining_limit.min(READ_CHUNK);
            if cap - len < to_read {
                dest.reserve(to_read);
            }

            let ptr = unsafe { dest.as_mut_ptr().add(len) };
            match fd.read(ptr, to_read) {
                Ok(n) if n > 0 => {
                    unsafe { dest.set_len(len + n); }

                    if is_stdout {
                        if let Some(f) = early_exit {
                            if f(&dest[len..len + n]) {
                                return Ok(true); // Early exit implies EOF/done
                            }
                        }
                    }
                }
                Ok(_) => {
                    return Ok(true); // EOF
                }
                Err(SysError::Syscall { code, .. }) if code == libc::EAGAIN || code == libc::EWOULDBLOCK => return Ok(false), // Would block
                Err(e) => {
                    return Err(e);
                }
            }
        }
    }

    pub fn into_parts(mut self) -> (Vec<u8>, Vec<u8>) {
        (std::mem::take(&mut self.stdout), std::mem::take(&mut self.stderr))
    }
}
