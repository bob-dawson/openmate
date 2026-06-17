pub fn generate_ps1(exe: &str, update: &str, pid: u32) -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'
$targetPid = {pid}
$new = '{update}'
$tgt = '{exe}'
$deadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $deadline -and (Get-Process -Id $targetPid -ErrorAction SilentlyContinue)) {{
    Start-Sleep -Milliseconds 500
}}
$proc = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
if ($proc) {{
    Stop-Process -Id $targetPid -Force
    Start-Sleep -Seconds 1
}}
Copy-Item $tgt "$tgt.bak" -Force
try {{
    Move-Item $new $tgt -Force
}} catch {{
    Move-Item "$tgt.bak" $tgt -Force
    Start-Process $tgt -ArgumentList "--tray" -WindowStyle Hidden
    exit 1
}}
Start-Process $tgt -ArgumentList "--tray" -WindowStyle Hidden
Remove-Item "$tgt.bak" -Force -ErrorAction SilentlyContinue
Remove-Item $MyInvocation.MyCommand.Path -Force
"#,
        pid = pid,
        update = update.replace('\'', "''"),
        exe = exe.replace('\'', "''"),
    )
}

pub fn generate_sh(exe: &str, update: &str, pid: u32) -> String {
    format!(
        r#"#!/bin/bash
set -e
OLDPID={pid}
NEW='{update}'
TGT='{exe}'
for i in $(seq 1 6); do kill -0 $OLDPID 2>/dev/null || break; sleep 0.5; done
if kill -0 $OLDPID 2>/dev/null; then
    kill -9 $OLDPID 2>/dev/null || true
    sleep 1
fi
cp "$TGT" "$TGT.bak"
if ! mv "$NEW" "$TGT"; then
    mv "$TGT.bak" "$TGT"
    nohup "$TGT" >/dev/null 2>&1 &
    exit 1
fi
chmod +x "$TGT"
nohup "$TGT" >/dev/null 2>&1 &
rm -f "$TGT.bak"
rm -- "$0"
"#,
        pid = pid,
        update = update.replace('\'', "'\\''"),
        exe = exe.replace('\'', "'\\''"),
    )
}

pub fn generate(exe: &str, update: &str, pid: u32) -> String {
    if cfg!(windows) {
        generate_ps1(exe, update, pid)
    } else {
        generate_sh(exe, update, pid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ps1_contains_key_steps() {
        let s = generate_ps1("/path/to/openmate.exe", "/tmp/openmate.update", 12345);
        assert!(s.contains("$targetPid = 12345"));
        assert!(s.contains("Get-Process"));
        assert!(s.contains(".bak"));
        assert!(s.contains("Move-Item"));
        assert!(s.contains("Start-Process"));
        assert!(s.contains("Remove-Item $MyInvocation.MyCommand.Path"));
    }

    #[test]
    fn ps1_has_rollback_on_move_failure() {
        let s = generate_ps1("/path/openmate.exe", "/tmp/update", 99);
        assert!(s.contains("catch"));
        assert!(s.contains(".bak"));
    }

    #[test]
    fn sh_contains_key_steps() {
        let s = generate_sh("/path/openmate", "/tmp/openmate.update", 12345);
        assert!(s.contains("OLDPID=12345"));
        assert!(s.contains("kill -0"));
        assert!(s.contains(".bak"));
        assert!(s.contains("mv"));
        assert!(s.contains("chmod +x"));
        assert!(s.contains("nohup"));
        assert!(s.contains("rm --"));
    }

    #[test]
    fn sh_has_rollback_on_move_failure() {
        let s = generate_sh("/path/openmate", "/tmp/update", 99);
        assert!(s.contains("if ! mv"));
        assert!(s.contains("mv \"$TGT.bak\""));
    }
}
