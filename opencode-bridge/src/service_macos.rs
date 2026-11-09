use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub const LABEL: &str = "net.clawmate.openmate";
const PLIST_PATH: &str = "/Library/LaunchDaemons/net.clawmate.openmate.plist";

pub fn plist_path() -> PathBuf {
    PathBuf::from(PLIST_PATH)
}

pub fn is_installed() -> bool {
    plist_path().exists()
}

/// 守护进程是否已被 launchd 加载运行（system 域）。
pub fn is_loaded() -> bool {
    Command::new("launchctl")
        .args(["print", &format!("system/{LABEL}")])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub struct PlistSpec<'a> {
    pub exe: &'a str,
    pub user: &'a str,
    pub group: &'a str,
    pub home: &'a str,
    pub path: &'a str,
    pub stdout_path: &'a str,
    pub stderr_path: &'a str,
}

/// 生成 LaunchDaemon（system 域，开机即启动，不依赖用户登录）。
pub fn build_plist(spec: &PlistSpec<'_>) -> String {
    let user = escape(spec.user);
    let group = escape(spec.group);
    let home = escape(spec.home);
    let path = escape(spec.path);
    let exe = escape(spec.exe);
    let stdout_path = escape(spec.stdout_path);
    let stderr_path = escape(spec.stderr_path);
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>{LABEL}</string>

    <key>ProgramArguments</key>
    <array>
        <string>{exe}</string>
        <string>service</string>
    </array>

    <key>UserName</key>
    <string>{user}</string>
    <key>GroupName</key>
    <string>{group}</string>
    <key>WorkingDirectory</key>
    <string>{home}</string>

    <key>EnvironmentVariables</key>
    <dict>
        <key>HOME</key>
        <string>{home}</string>
        <key>USER</key>
        <string>{user}</string>
        <key>LOGNAME</key>
        <string>{user}</string>
        <key>PATH</key>
        <string>{path}</string>
        <key>RUST_LOG</key>
        <string>info</string>
        <key>OPENMATE_SERVICE_MODE</key>
        <string>1</string>
    </dict>

    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>ThrottleInterval</key>
    <integer>10</integer>
    <key>ProcessType</key>
    <string>Background</string>
    <key>AbandonProcessGroup</key>
    <true/>

    <key>StandardOutPath</key>
    <string>{stdout_path}</string>
    <key>StandardErrorPath</key>
    <string>{stderr_path}</string>
</dict>
</plist>
"#
    )
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// 服务必须以普通用户身份运行：以 root 运行会把 `~/.openmate` 变成 `/var/root/.openmate`，
/// 相当于换了一个全新实例（配对设备、instance_id 全部丢失）。
pub fn resolve_run_user(sudo_user: Option<&str>, current_user: Option<&str>) -> anyhow::Result<String> {
    let candidate = sudo_user
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .or_else(|| current_user.map(str::trim).filter(|value| !value.is_empty()));

    let user = match candidate {
        Some(user) => user,
        None => anyhow::bail!("Cannot determine the user to run the service as"),
    };

    if user == "root" {
        anyhow::bail!(
            "Refusing to install the service as root. Run `sudo {} install` as a regular user so the bridge keeps using your ~/.openmate",
            std::env::current_exe()
                .map(|p| p.display().to_string())
                .unwrap_or_else(|_| "openmate".to_string())
        );
    }

    if !user
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
    {
        anyhow::bail!("Invalid user name: {}", user);
    }

    Ok(user.to_string())
}

fn run_as_root(program: &str, args: &[&str]) -> Option<String> {
    Command::new(program)
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
}

fn login_shell_value(user: &str, variable: &str) -> Option<String> {
    run_as_root("su", &["-l", user, "-c", &format!("printf %s \"${}\"", variable)])
        .filter(|value| !value.is_empty())
}

fn user_home(user: &str) -> anyhow::Result<String> {
    login_shell_value(user, "HOME")
        .or_else(|| Some(format!("/Users/{}", user)))
        .ok_or_else(|| anyhow::anyhow!("Cannot resolve home directory for {}", user))
}

fn user_group(user: &str) -> String {
    run_as_root("id", &["-gn", user]).unwrap_or_else(|| "staff".to_string())
}

/// launchd 不继承登录 shell 的环境，npm/opencode 都在用户目录下，必须显式拼进 PATH。
fn service_path(home: &str, user: &str) -> String {
    let mut parts: Vec<String> = vec![
        format!("{}/.opencode/bin", home),
        format!("{}/.local/bin", home),
        format!("{}/.npm-global/bin", home),
        format!("{}/.bun/bin", home),
    ];
    if let Some(login) = login_shell_value(user, "PATH") {
        for dir in login.split(':').filter(|d| !d.trim().is_empty()) {
            if !parts.iter().any(|existing| existing == dir) {
                parts.push(dir.to_string());
            }
        }
    }
    for dir in ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"] {
        if !parts.iter().any(|existing| existing == dir) {
            parts.push(dir.to_string());
        }
    }
    parts.join(":")
}

fn require_root(action: &str) -> anyhow::Result<()> {
    if run_as_root("id", &["-u"]).as_deref() == Some("0") {
        return Ok(());
    }
    anyhow::bail!(
        "{} requires root: run `sudo {} {}`",
        action,
        std::env::current_exe()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|_| "openmate".to_string()),
        action.to_lowercase()
    );
}

fn persist_run_as_user(user: &str, home: &str) -> anyhow::Result<()> {
    let db_path = Path::new(home).join(".openmate").join("bridge.db");
    let db = crate::bridge_db::BridgeDb::open_at(&db_path).map_err(|e| anyhow::anyhow!("{}", e))?;
    db.set_config("opencode.run_as_user", user)
        .map_err(|e| anyhow::anyhow!("{}", e))?;
    Ok(())
}

/// `bootout` 是异步的：launchd 还没把任务彻底摘掉就 `bootstrap` 会返回
/// `Bootstrap failed: 5: Input/output error`。这里等到任务真正消失再继续。
fn bootout_and_wait() {
    let _ = Command::new("launchctl")
        .args(["bootout", &format!("system/{LABEL}")])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    for _ in 0..40 {
        if !is_loaded() {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(250));
    }
}

fn bootstrap_with_retry() -> anyhow::Result<()> {
    let _ = Command::new("launchctl")
        .args(["enable", &format!("system/{LABEL}")])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    let mut last_error = String::new();
    for attempt in 0..20 {
        let output = Command::new("launchctl")
            .args(["bootstrap", "system", PLIST_PATH])
            .output()?;

        if output.status.success() {
            return Ok(());
        }

        last_error = String::from_utf8_lossy(&output.stderr).trim().to_string();

        if is_loaded() {
            return Ok(());
        }

        if attempt == 0 {
            eprintln!("launchctl bootstrap did not succeed yet ({}), retrying...", last_error);
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }

    anyhow::bail!(
        "launchctl bootstrap failed: {}. Try `sudo launchctl bootout system/{}` then `sudo launchctl bootstrap system {}`",
        last_error,
        LABEL,
        PLIST_PATH
    )
}

pub fn install() -> anyhow::Result<()> {
    require_root("install")?;

    let exe = std::env::current_exe()?;
    let sudo_user = std::env::var("SUDO_USER").ok();
    let current_user = std::env::var("USER").ok();
    let user = resolve_run_user(sudo_user.as_deref(), current_user.as_deref())?;

    let home = user_home(&user)?;
    let group = user_group(&user);
    let log_dir = Path::new(&home).join(".openmate");
    std::fs::create_dir_all(&log_dir)?;

    let exe_str = exe.to_string_lossy().to_string();
    let path_env = service_path(&home, &user);
    let stdout_path = log_dir.join("launchd.out.log").to_string_lossy().to_string();
    let stderr_path = log_dir.join("launchd.err.log").to_string_lossy().to_string();

    let spec = PlistSpec {
        exe: &exe_str,
        user: &user,
        group: &group,
        home: &home,
        path: &path_env,
        stdout_path: &stdout_path,
        stderr_path: &stderr_path,
    };

    let mut file = std::fs::File::create(PLIST_PATH)?;
    file.write_all(build_plist(&spec).as_bytes())?;
    drop(file);

    let _ = Command::new("chown").args(["root:wheel", PLIST_PATH]).status();
    let _ = Command::new("chmod").args(["644", PLIST_PATH]).status();

    bootout_and_wait();
    bootstrap_with_retry()?;

    if let Err(e) = persist_run_as_user(&user, &home) {
        eprintln!("warning: failed to persist opencode.run_as_user: {}", e);
    }

    println!(
        "{} installed and started successfully (user: {}, label: {}).",
        exe.display(),
        user,
        LABEL
    );
    Ok(())
}

pub fn uninstall() -> anyhow::Result<()> {
    require_root("uninstall")?;

    bootout_and_wait();

    let path = plist_path();
    if path.exists() {
        std::fs::remove_file(&path)?;
    }

    println!("{} service uninstalled successfully.", LABEL);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec<'a>(exe: &'a str, path: &'a str) -> PlistSpec<'a> {
        PlistSpec {
            exe,
            user: "article",
            group: "staff",
            home: "/Users/article",
            path,
            stdout_path: "/Users/article/.openmate/launchd.out.log",
            stderr_path: "/Users/article/.openmate/launchd.err.log",
        }
    }

    #[test]
    fn plist_runs_service_as_user_with_boot_defaults() {
        let plist = build_plist(&spec("/Users/article/openmate", "/Users/article/.local/bin:/usr/bin:/bin"));

        assert!(plist.contains("<string>net.clawmate.openmate</string>"));
        assert!(plist.contains("<string>/Users/article/openmate</string>"));
        assert!(plist.contains("<string>service</string>"));
        assert!(plist.contains("<key>UserName</key>\n    <string>article</string>"));
        assert!(plist.contains("<key>HOME</key>\n        <string>/Users/article</string>"));
        assert!(plist.contains("<key>RunAtLoad</key>\n    <true/>"));
        assert!(plist.contains("<key>SuccessfulExit</key>\n        <false/>"));
        assert!(plist.contains("<key>AbandonProcessGroup</key>\n    <true/>"));
    }

    #[test]
    fn plist_escapes_xml_special_characters() {
        let plist = build_plist(&spec("/Users/a&b/open<mate>", "/opt/a&b/bin"));

        assert!(plist.contains("/Users/a&amp;b/open&lt;mate&gt;"));
        assert!(plist.contains("/opt/a&amp;b/bin"));
        assert!(!plist.contains("open<mate>"));
    }

    #[test]
    fn path_keeps_user_dirs_before_system_dirs() {
        let path = service_path("/Users/article", "root");
        let dirs: Vec<&str> = path.split(':').collect();

        assert_eq!(dirs[0], "/Users/article/.opencode/bin");
        assert_eq!(dirs[1], "/Users/article/.local/bin");
        assert!(dirs.contains(&"/usr/bin"));
    }

    #[test]
    fn resolve_run_user_prefers_sudo_user_over_root() {
        assert_eq!(
            resolve_run_user(Some("article"), Some("root")).unwrap(),
            "article"
        );
    }

    #[test]
    fn resolve_run_user_falls_back_to_current_user() {
        assert_eq!(resolve_run_user(None, Some("article")).unwrap(), "article");
        assert_eq!(resolve_run_user(Some(""), Some("article")).unwrap(), "article");
    }

    #[test]
    fn resolve_run_user_rejects_root() {
        assert!(resolve_run_user(None, Some("root")).is_err());
        assert!(resolve_run_user(Some("root"), Some("root")).is_err());
        assert!(resolve_run_user(None, None).is_err());
    }

    #[test]
    fn resolve_run_user_rejects_injected_names() {
        assert!(resolve_run_user(Some("article\n<key>"), None).is_err());
        assert!(resolve_run_user(Some("a/b"), None).is_err());
    }
}
