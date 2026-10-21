mod mcp_tunnel;
mod live_phone;

use rand::{rngs::OsRng, RngCore};
use serde::Serialize;
use std::net::TcpListener;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use std::process::Command;
use tauri::{Manager, State};
use tauri_plugin_shell::ShellExt;

struct GatewayState {
    token: String,
    http_base: String,
    ws_base: String,
}

#[derive(Serialize)]
struct GatewaySession {
    token: String,
    http_base: String,
    ws_base: String,
}

#[tauri::command]
fn gateway_session(state: State<'_, GatewayState>) -> GatewaySession {
    GatewaySession {
        token: state.token.clone(),
        http_base: state.http_base.clone(),
        ws_base: state.ws_base.clone(),
    }
}

#[tauri::command]
fn diagnostics_folder(app: tauri::AppHandle) -> Result<String, String> {
    let path = app
        .path()
        .app_local_data_dir()
        .map_err(|error| error.to_string())?
        .join("runtime")
        .join("diagnostics");
    std::fs::create_dir_all(&path).map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
fn open_diagnostics_folder(app: tauri::AppHandle) -> Result<String, String> {
    let path = app
        .path()
        .app_local_data_dir()
        .map_err(|error| error.to_string())?
        .join("runtime")
        .join("diagnostics");
    std::fs::create_dir_all(&path).map_err(|error| error.to_string())?;

    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        Command::new("explorer.exe")
            .arg(&path)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map_err(|error| error.to_string())?;
    }

    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
async fn connector_status(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    let output = app
        .shell()
        .sidecar("CycloneAgentMCP")
        .map_err(|error| error.to_string())?
        .args(["status", "--probe-gateway"])
        .output()
        .await
        .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err("Cyclone Agent connector status is unavailable".into());
    }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
}

#[tauri::command]
fn legacy_companion_warning() -> Option<String> {
    #[cfg(windows)]
    {
        let local = std::env::var_os("LOCALAPPDATA")?;
        let path = std::path::PathBuf::from(local).join("Cyclone PC Companion");
        if path.is_dir() {
            Some(
                "Cyclone PC Companion 3.8.x is installed beside Cyclone One. Prefer Cyclone One; uninstall the legacy companion so MCP points at the right runtime."
                    .into(),
            )
        } else {
            None
        }
    }
    #[cfg(not(windows))]
    {
        None
    }
}

#[tauri::command]
async fn connector_action(
    app: tauri::AppHandle,
    connector_id: String,
    action: String,
) -> Result<serde_json::Value, String> {
    let host = match connector_id.as_str() {
        "codex" => "codex",
        "deepseek-mcp" => "opencode",
        "generic-mcp" => "generic",
        _ => return Err("Unknown Cyclone connector".into()),
    };
    if action == "install" && host != "generic" {
        return Err("Install the selected AI harness first, then connect it to Cyclone".into());
    }
    let mut command = app
        .shell()
        .sidecar("CycloneAgentMCP")
        .map_err(|error| error.to_string())?;
    if host == "generic" {
        command = command.args(["copy-config", "generic"]);
    } else {
        command = command.args(["connect", host, "--verify"]);
    }
    let output = command.output().await.map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    if host == "generic" {
        return Ok(serde_json::json!({
            "ok": true,
            "message": String::from_utf8_lossy(&output.stdout).trim()
        }));
    }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
}

fn strong_token() -> String {
    let mut bytes = [0_u8; 32];
    OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|value| format!("{value:02x}")).collect()
}

fn reserve_loopback_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|error| error.to_string())?;
    let port = listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();
    drop(listener);
    Ok(port)
}

/// Remove only superseded developer-era Cyclone gateway monitors and executables.
///
/// The monitor must be stopped first because it otherwise respawns the visible console process.
/// Selection is restricted to PowerShell processes whose command line names the exact legacy
/// `monitor-pc-console.ps1` script, followed by three fixed gateway image names.
#[cfg(windows)]
fn cleanup_legacy_gateway_processes() {
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const STOP_LEGACY_MONITOR: &str = r#"
$selfPid = $PID
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object {
    $_.ProcessId -ne $selfPid -and
    $_.Name -in @('powershell.exe', 'pwsh.exe') -and
    $_.CommandLine -match '(?i)(?:^|[\\/\s])monitor-pc-console\.ps1(?:[\"\s]|$)'
  } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
"#;
    const LEGACY_IMAGES: [&str; 3] = [
        "cyclone-device-gateway.exe",
        "Cyclone Device Gateway.exe",
        "CycloneDeviceGateway.exe",
    ];
    let _ = Command::new("powershell.exe")
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-Command",
            STOP_LEGACY_MONITOR,
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
    for image in LEGACY_IMAGES {
        let _ = Command::new("taskkill")
            .args(["/F", "/T", "/IM", image])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
    }
}

#[cfg(not(windows))]
fn cleanup_legacy_gateway_processes() {}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    cleanup_legacy_gateway_processes();
    let _ = live_phone::live_phone_control("stop".into());

    let token = strong_token();
    let gateway_port =
        reserve_loopback_port().expect("Cyclone could not reserve a local Gateway port");
    let http_base = format!("http://127.0.0.1:{gateway_port}");
    let ws_base = format!("ws://127.0.0.1:{gateway_port}");

    let runtime_token = token.clone();
    let runtime_http_base = http_base.clone();
    let runtime_port = gateway_port.to_string();
    let parent_pid = std::process::id().to_string();

    tauri::Builder::default()
        .manage(GatewayState {
            token,
            http_base,
            ws_base,
        })
        .plugin(tauri_plugin_shell::init())
        .setup(move |app| {
            let runtime_dir = app.path().app_local_data_dir()?.join("runtime");
            std::fs::create_dir_all(&runtime_dir)?;
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                loop {
                    let command = match handle.shell().sidecar("CyclonePCRuntime") {
                        Ok(command) => command,
                        Err(_) => break,
                    };
                    let command = command.arg("serve")
                        .env("CYCLONE_DEVICE_GATEWAY_TOKEN", &runtime_token)
                        .env("CYCLONE_DEVICE_GATEWAY_URL", &runtime_http_base)
                        .env("CYCLONE_DEVICE_GATEWAY_PORT", &runtime_port)
                        .env("CYCLONE_DEVICE_GATEWAY_RUNTIME", runtime_dir.to_string_lossy().to_string())
                        .env("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "1")
                        .env("CYCLONE_PC_PARENT_PID", &parent_pid);
                    if let Ok((mut events, _child)) = command.spawn() {
                        // Drain output, then restart the owned runtime at the same private endpoint.
                        tauri::async_runtime::block_on(async move {
                            while let Some(event) = events.recv().await {
                                if matches!(event, tauri_plugin_shell::process::CommandEvent::Terminated(_)) { break; }
                            }
                        });
                    }
                    std::thread::sleep(std::time::Duration::from_secs(2));
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            gateway_session,
            live_phone::live_phone_status,
            live_phone::live_phone_control,
            diagnostics_folder,
            open_diagnostics_folder,
            connector_status,
            connector_action,
            legacy_companion_warning,
            mcp_tunnel::mcp_tunnel_status,
            mcp_tunnel::mcp_tunnel_start,
            mcp_tunnel::mcp_tunnel_stop,
            mcp_tunnel::mcp_tunnel_restart,
            mcp_tunnel::mcp_tunnel_rotate_token,
            mcp_tunnel::mcp_tunnel_set_mode,
            mcp_tunnel::mcp_tunnel_token,
            mcp_tunnel::mcp_tunnel_smoke,
            mcp_tunnel::mcp_tunnel_open_docs
        ])
        .run(tauri::generate_context!())
        .expect("error while running Cyclone PC Companion");
}
