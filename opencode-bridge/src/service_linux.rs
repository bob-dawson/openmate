use std::io::Write;
use std::path::Path;

const SERVICE_NAME: &str = "openmate";
const UNIT_PATH: &str = "/etc/systemd/system/openmate.service";

pub fn install() -> anyhow::Result<()> {
    let exe_path = std::env::current_exe()?;
    let working_dir = exe_path
        .parent()
        .unwrap_or(Path::new("/"))
        .to_string_lossy()
        .to_string();

    let current_user = std::env::var("SUDO_USER")
        .or_else(|_| std::env::var("USER"))
        .unwrap_or_else(|_| "root".to_string());

    let current_path = if current_user != "root" {
        std::process::Command::new("su")
            .args(["-", &current_user, "-c", "echo $PATH"])
            .output()
            .ok()
            .and_then(|o| {
                if o.status.success() {
                    Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
                } else {
                    None
                }
            })
            .unwrap_or_else(|| std::env::var("PATH").unwrap_or_default())
    } else {
        std::env::var("PATH").unwrap_or_default()
    };

    let current_home = if current_user != "root" {
        std::process::Command::new("su")
            .args(["-", &current_user, "-c", "echo $HOME"])
            .output()
            .ok()
            .and_then(|o| {
                if o.status.success() {
                    Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
                } else {
                    None
                }
            })
            .unwrap_or_else(|| "/root".to_string())
    } else {
        std::env::var("HOME").unwrap_or_else(|_| "/root".to_string())
    };

    let db = crate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    db.set_config("opencode.run_as_user", &current_user).map_err(|e| anyhow::anyhow!("{}", e))?;

    let unit_content = format!(
        "[Unit]\n\
         Description=OpenMate Bridge\n\
         After=network.target\n\
         \n\
         [Service]\n\
         Type=simple\n\
         Environment=\"PATH={}\"\n\
         Environment=\"HOME={}\"\n\
         ExecStart={} service\n\
         WorkingDirectory={}\n\
         Restart=on-failure\n\
         RestartSec=5\n\
         \n\
         [Install]\n\
         WantedBy=multi-user.target\n",
        current_path,
        current_home,
        exe_path.display(),
        working_dir
    );

    let mut file = std::fs::File::create(UNIT_PATH)?;
    file.write_all(unit_content.as_bytes())?;

    let daemon_reload = std::process::Command::new("systemctl")
        .arg("daemon-reload")
        .status()?;

    if !daemon_reload.success() {
        anyhow::bail!("systemctl daemon-reload failed");
    }

    let status = std::process::Command::new("systemctl")
        .args(["enable", "--now", SERVICE_NAME])
        .status()?;

    if !status.success() {
        anyhow::bail!("systemctl enable/start failed");
    }

    println!("{} service installed and started successfully.", SERVICE_NAME);
    Ok(())
}

pub fn uninstall() -> anyhow::Result<()> {
    let _ = std::process::Command::new("systemctl")
        .args(["stop", SERVICE_NAME])
        .status();

    let status = std::process::Command::new("systemctl")
        .args(["disable", SERVICE_NAME])
        .status()?;

    if !status.success() {
        anyhow::bail!("systemctl disable failed");
    }

    if Path::new(UNIT_PATH).exists() {
        std::fs::remove_file(UNIT_PATH)?;
    }

    std::process::Command::new("systemctl")
        .arg("daemon-reload")
        .status()?;

    println!("{} service uninstalled successfully.", SERVICE_NAME);
    Ok(())
}
