#[cfg(target_os = "windows")]
pub fn open_browser(url: &str) -> Result<(), String> {
    use windows::core::PCWSTR;
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    let wide_url: Vec<u16> = url.encode_utf16().chain(std::iter::once(0)).collect();
    let operation: Vec<u16> = "open\0".encode_utf16().collect();

    unsafe {
        let result = ShellExecuteW(
            None,
            PCWSTR(operation.as_ptr()),
            PCWSTR(wide_url.as_ptr()),
            PCWSTR::null(),
            PCWSTR::null(),
            SW_SHOWNORMAL,
        );
        if result.0 as usize <= 32 {
            return Err(format!("ShellExecuteW failed with code {}", result.0 as isize));
        }
    }
    Ok(())
}

#[cfg(not(target_os = "windows"))]
pub fn open_browser(url: &str) -> Result<(), String> {
    open::that(url).map_err(|e| e.to_string())
}
