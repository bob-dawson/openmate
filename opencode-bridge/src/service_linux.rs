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

    let unit_content = format!(
        "[Unit]\n\
         Description=OpenMate Bridge\n\
         After=network.target\n\
         \n\
         [Service]\n\
         Type=simple\n\
         ExecStart={} service\n\
         WorkingDirectory={}\n\
         Restart=on-failure\n\
         RestartSec=5\n\
         \n\
         [Install]\n\
         WantedBy=multi-user.target\n",
        exe_path.display(),
        working_dir
    );

    let mut file = std::fs::File::create(UNIT_PATH)?;
    file.write_all(unit_content.as_bytes())?;

    let status = std::process::Command::new("systemctl")
        .args(["daemon-reload", "enable", "--now", SERVICE_NAME])
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
