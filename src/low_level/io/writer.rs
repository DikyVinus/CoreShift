use crate::low_level::reactor::Fd;
use crate::low_level::spawn::SysError;

const WRITE_CHUNK: usize = 65536;

pub struct WriterState {
    pub buf: Option<Box<[u8]>>,
    pub off: usize,
}

impl WriterState {
    pub fn new(buf: Option<Box<[u8]>>) -> Self {
        Self { buf, off: 0 }
    }

    #[inline(always)]
    pub fn write_to_fd(&mut self, fd: &Fd) -> Result<bool, SysError> {
        if let Some(buf) = &self.buf {
            while self.off < buf.len() {
                let remaining = buf.len() - self.off;
                let chunk = remaining.min(WRITE_CHUNK);

                match fd.write(buf[self.off..].as_ptr(), chunk) {
                    Ok(n) if n > 0 => {
                        self.off += n;
                    }
                    Ok(_) => {
                        self.buf = None;
                        return Ok(true); // Done
                    }
                    Err(SysError::Syscall { code, .. }) => {
                        if code == libc::EAGAIN || code == libc::EWOULDBLOCK {
                            return Ok(false); // Would block
                        } else if code == libc::EPIPE {
                            self.buf = None;
                            return Ok(true); // Broken pipe
                        } else {
                            self.buf = None;
                            return Ok(true); // Error
                        }
                    }
                }
            }
            // Done writing
            self.buf = None;
            return Ok(true);
        }
        Ok(true)
    }
}
