use std::sync::mpsc;
use tokio::sync::Notify;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayEvent {
    OpenUi,
    ToggleAutostart,
    Quit,
}

#[cfg(target_os = "windows")]
pub fn spawn_tray_thread(
    port: u16,
    tx: mpsc::Sender<TrayEvent>,
    _shutdown: std::sync::Arc<Notify>,
) -> anyhow::Result<std::thread::JoinHandle<()>> {
    let handle = std::thread::Builder::new()
        .name("tray".into())
        .spawn(move || {
            if let Err(e) = run_tray_loop(port, tx.clone()) {
                eprintln!("Tray error: {}", e);
            }
        })
        .map_err(|e| anyhow::anyhow!("Failed to spawn tray thread: {}", e))?;
    Ok(handle)
}

#[cfg(target_os = "windows")]
fn run_tray_loop(port: u16, tx: mpsc::Sender<TrayEvent>) -> anyhow::Result<()> {
    use std::ffi::c_void;
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::*;
    use windows::Win32::Graphics::Gdi::*;
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Shell::*;
    use windows::Win32::UI::WindowsAndMessaging::*;

    const TRAY_ID: u32 = 1;
    const WM_TRAYICON: u32 = WM_USER + 1;

    let class_name: Vec<u16> = "OpenMateTrayClass\0".encode_utf16().collect();
    let window_name: Vec<u16> = "OpenMateTray\0".encode_utf16().collect();

    let h_instance = unsafe { GetModuleHandleW(None)? };
    let h_inst_icon = HINSTANCE(h_instance.0);

    let wnd_class = WNDCLASSW {
        lpfnWndProc: Some(tray_wnd_proc),
        hInstance: h_instance.into(),
        lpszClassName: PCWSTR(class_name.as_ptr()),
        hbrBackground: HBRUSH::default(),
        hCursor: unsafe { LoadCursorW(None, IDC_ARROW)? },
        ..Default::default()
    };

    unsafe {
        if RegisterClassW(&wnd_class) == 0 {
            let err = GetLastError();
            if err != ERROR_CLASS_ALREADY_EXISTS {
                return Err(anyhow::anyhow!("RegisterClassW failed: {}", err.0));
            }
        }
    }

    let ctx = Box::new(TrayContext {
        port,
        tx,
        autostart_checked: is_autostart_enabled(),
    });
    let ctx_ptr = Box::into_raw(ctx) as *const c_void;

    let hwnd = unsafe {
        CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            PCWSTR(class_name.as_ptr()),
            PCWSTR(window_name.as_ptr()),
            WINDOW_STYLE::default(),
            0, 0, 0, 0,
            Some(HWND_MESSAGE),
            None,
            Some(h_instance.into()),
            Some(ctx_ptr),
        )?
    };

    let icon = unsafe {
        match LoadImageW(
            Some(h_inst_icon),
            PCWSTR(1 as *const u16),
            IMAGE_ICON,
            0, 0,
            LR_DEFAULTSIZE | LR_SHARED,
        ) {
            Ok(handle) => HICON(handle.0),
            Err(_) => LoadIconW(None, IDI_APPLICATION).unwrap(),
        }
    };

    let tip: Vec<u16> = "OpenMate Bridge\0".encode_utf16().collect();
    let mut nid = NOTIFYICONDATAW {
        cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
        hWnd: hwnd,
        uID: TRAY_ID,
        uFlags: NIF_MESSAGE | NIF_ICON | NIF_TIP,
        uCallbackMessage: WM_TRAYICON,
        hIcon: icon,
        ..Default::default()
    };
    nid.szTip[..tip.len()].copy_from_slice(&tip[..]);

    unsafe {
        if !Shell_NotifyIconW(NIM_ADD, &nid).as_bool() {
            return Err(anyhow::anyhow!("Shell_NotifyIconW NIM_ADD failed"));
        }
    }

    let mut msg = MSG::default();
    unsafe {
        loop {
            let ret = GetMessageW(&mut msg, None, 0, 0);
            if !ret.as_bool() || ret.0 == -1 {
                break;
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }

    unsafe {
        let nid_remove = NOTIFYICONDATAW {
            cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: hwnd,
            uID: TRAY_ID,
            ..Default::default()
        };
        let _ = Shell_NotifyIconW(NIM_DELETE, &nid_remove);
    }

    Ok(())
}

#[cfg(target_os = "windows")]
struct TrayContext {
    port: u16,
    tx: mpsc::Sender<TrayEvent>,
    autostart_checked: bool,
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn tray_wnd_proc(
    hwnd: windows::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    use windows::Win32::Foundation::*;
    use windows::Win32::UI::WindowsAndMessaging::*;

    const WM_TRAYICON: u32 = WM_USER + 1;
    const IDM_OPEN_UI: usize = 1001;
    const IDM_AUTOSTART: usize = 1002;
    const IDM_QUIT: usize = 1003;
    const TRAY_ID: u32 = 1;

    match msg {
        WM_CREATE => {
            let cs = lparam.0 as *const CREATESTRUCTW;
            if !cs.is_null() {
                unsafe {
                    let lp = (*cs).lpCreateParams;
                    if !lp.is_null() {
                        SetWindowLongPtrW(hwnd, GWLP_USERDATA, lp as isize);
                    }
                }
            }
            LRESULT(0)
        }
        WM_TRAYICON => {
            if wparam.0 as u32 == TRAY_ID {
                match lparam.0 as u32 {
                    WM_RBUTTONUP => {
                        show_context_menu(hwnd);
                    }
                    WM_LBUTTONDBLCLK => {
                        if let Some(ctx) = get_tray_ctx(hwnd) {
                            let _ = crate::browser::open_browser(&format!("http://127.0.0.1:{}/ui/", ctx.port));
                        }
                    }
                    _ => {}
                }
            }
            LRESULT(0)
        }
        WM_COMMAND => {
            let cmd_id = (wparam.0 as u32 & 0xFFFF) as usize;
            match cmd_id {
                IDM_OPEN_UI => {
                    if let Some(ctx) = get_tray_ctx(hwnd) {
                        let _ = crate::browser::open_browser(&format!("http://127.0.0.1:{}/ui/", ctx.port));
                    }
                }
                IDM_AUTOSTART => {
                    if let Some(ctx) = get_tray_ctx(hwnd) {
                        let new_state = !ctx.autostart_checked;
                        if set_autostart_enabled(new_state) {
                            ctx.autostart_checked = new_state;
                        }
                    }
                }
                IDM_QUIT => {
                    if let Some(ctx) = get_tray_ctx(hwnd) {
                        let _ = ctx.tx.send(TrayEvent::Quit);
                    }
                    unsafe {
                        let _ = DestroyWindow(hwnd);
                    }
                }
                _ => {}
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            unsafe {
                PostQuitMessage(0);
            }
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

#[cfg(target_os = "windows")]
fn get_tray_ctx(hwnd: windows::Win32::Foundation::HWND) -> Option<&'static mut TrayContext> {
    use windows::Win32::UI::WindowsAndMessaging::*;
    unsafe {
        let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA);
        if ptr == 0 {
            None
        } else {
            Some(&mut *(ptr as *mut TrayContext))
        }
    }
}

#[cfg(target_os = "windows")]
fn show_context_menu(hwnd: windows::Win32::Foundation::HWND) {
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::*;
    use windows::Win32::UI::WindowsAndMessaging::*;

    const IDM_OPEN_UI: usize = 1001;
    const IDM_AUTOSTART: usize = 1002;
    const IDM_QUIT: usize = 1003;

    unsafe {
        let hmenu = match CreatePopupMenu() {
            Ok(m) => m,
            Err(_) => return,
        };

        let text_open: Vec<u16> = "打开管理页面\0".encode_utf16().collect();
        let _ = AppendMenuW(hmenu, MF_STRING, IDM_OPEN_UI, PCWSTR(text_open.as_ptr()));

        let _ = AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR::null());

        let ctx = get_tray_ctx(hwnd);
        let autostart_checked = ctx.map(|c| c.autostart_checked).unwrap_or(false);
        let mut autostart_flags = MENU_ITEM_FLAGS(MF_STRING.0);
        if autostart_checked {
            autostart_flags |= MF_CHECKED;
        }
        let text_autostart: Vec<u16> = "开机自动启动\0".encode_utf16().collect();
        let _ = AppendMenuW(hmenu, autostart_flags, IDM_AUTOSTART, PCWSTR(text_autostart.as_ptr()));

        let _ = AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR::null());

        let text_quit: Vec<u16> = "退出\0".encode_utf16().collect();
        let _ = AppendMenuW(hmenu, MF_STRING, IDM_QUIT, PCWSTR(text_quit.as_ptr()));

        let mut pt = POINT::default();
        let _ = GetCursorPos(&mut pt);
        let _ = SetForegroundWindow(hwnd);

        let _ = TrackPopupMenu(
            hmenu,
            TPM_RIGHTALIGN,
            pt.x,
            pt.y,
            None,
            hwnd,
            None,
        );

        let _ = DestroyMenu(hmenu);
    }
}

#[cfg(target_os = "windows")]
fn is_autostart_enabled() -> bool {
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::NO_ERROR;
    use windows::Win32::System::Registry::*;

    let exe_path = match std::env::current_exe() {
        Ok(p) => p,
        Err(_) => return false,
    };
    let exe_str = match exe_path.to_str() {
        Some(s) => s,
        None => return false,
    };

    let key_path: Vec<u16> = r"Software\Microsoft\Windows\CurrentVersion\Run\0".encode_utf16().collect();
    let value_name: Vec<u16> = "OpenMate\0".encode_utf16().collect();

    unsafe {
        let mut key: HKEY = Default::default();
        let result = RegOpenKeyExW(
            HKEY_CURRENT_USER,
            PCWSTR(key_path.as_ptr()),
            None,
            KEY_READ,
            &mut key,
        );

        if result != NO_ERROR {
            return false;
        }

        let mut buf = [0u16; 512];
        let mut buf_size = (buf.len() * 2) as u32;
        let mut reg_type: REG_VALUE_TYPE = Default::default();

        let query_result = RegQueryValueExW(
            key,
            PCWSTR(value_name.as_ptr()),
            None,
            Some(&mut reg_type),
            Some(buf.as_mut_ptr() as *mut u8),
            Some(&mut buf_size),
        );

        let _ = RegCloseKey(key);

        if query_result != NO_ERROR {
            return false;
        }

        let existing = String::from_utf16_lossy(&buf[..buf_size as usize / 2]);
        existing.trim_end_matches('\0') == exe_str
    }
}

#[cfg(target_os = "windows")]
fn set_autostart_enabled(enabled: bool) -> bool {
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::NO_ERROR;
    use windows::Win32::System::Registry::*;

    let exe_path = match std::env::current_exe() {
        Ok(p) => p,
        Err(_) => return false,
    };
    let exe_str = match exe_path.to_str() {
        Some(s) => s,
        None => return false,
    };

    let key_path: Vec<u16> = r"Software\Microsoft\Windows\CurrentVersion\Run\0".encode_utf16().collect();
    let value_name: Vec<u16> = "OpenMate\0".encode_utf16().collect();

    unsafe {
        let mut key: HKEY = Default::default();
        let result = RegOpenKeyExW(
            HKEY_CURRENT_USER,
            PCWSTR(key_path.as_ptr()),
            None,
            KEY_SET_VALUE,
            &mut key,
        );

        if result != NO_ERROR {
            return false;
        }

        let success = if enabled {
            let val_bytes: Vec<u8> = format!("{}\0", exe_str)
                .encode_utf16()
                .flat_map(|c| c.to_le_bytes())
                .collect();
            let res = RegSetValueExW(
                key,
                PCWSTR(value_name.as_ptr()),
                None,
                REG_SZ,
                Some(&val_bytes),
            );
            res == NO_ERROR
        } else {
            let res = RegDeleteValueW(key, PCWSTR(value_name.as_ptr()));
            res == NO_ERROR
        };

        let _ = RegCloseKey(key);
        success
    }
}

#[cfg(target_os = "linux")]
struct BridgeTray {
    port: u16,
    autostart_enabled: bool,
    tx: mpsc::Sender<TrayEvent>,
}

#[cfg(target_os = "linux")]
impl ksni::Tray for BridgeTray {
    fn id(&self) -> String {
        "openmate-bridge".into()
    }
    fn title(&self) -> String {
        "OpenMate Bridge".into()
    }
    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "OpenMate Bridge".into(),
            description: format!("Port: {}", self.port).into(),
            ..Default::default()
        }
    }
    fn activate(&self, _x: i32, _y: i32) {
        let _ = self.tx.send(TrayEvent::OpenUi);
    }
    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;
        vec![
            StandardItem {
                label: "Open Management Page".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::OpenUi);
                }),
                ..Default::default()
            },
            Separator,
            CheckmarkItem {
                label: "Auto-start on boot".into(),
                checked: self.autostart_enabled,
                activate: Box::new(|tray: &mut BridgeTray| {
                    tray.autostart_enabled = !tray.autostart_enabled;
                    let _ = tray.tx.send(TrayEvent::ToggleAutostart);
                }),
                ..Default::default()
            },
            Separator,
            StandardItem {
                label: "Quit".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::Quit);
                }),
                ..Default::default()
            },
        ]
    }
}

#[cfg(target_os = "linux")]
pub fn spawn_tray_thread(
    port: u16,
    tx: mpsc::Sender<TrayEvent>,
) -> anyhow::Result<std::thread::JoinHandle<()>> {
    if std::env::var("DBUS_SESSION_BUS_ADDRESS").is_err() {
        tracing::warn!("D-Bus session bus not available, skipping system tray");
        return Err(anyhow::anyhow!("D-Bus session bus not available"));
    }

    let tray = BridgeTray {
        port,
        autostart_enabled: false,
        tx,
    };
    let handle = ksni::Handle::new(tray);

    std::thread::Builder::new()
        .name("tray".into())
        .spawn(move || {
            if let Err(e) = handle.run_blocking() {
                tracing::error!("Tray error: {}", e);
            }
        })
        .map_err(|e| anyhow::anyhow!("Failed to spawn tray thread: {}", e))
}
