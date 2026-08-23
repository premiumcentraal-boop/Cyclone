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
        .arg("status")
        .output()
        .await
        .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err("Cyclone Agent connector status is unavailable".into());
    }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
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
    let port = listener.local_addr().map_err(|error| error.to_string())?.port();
    drop(listener);
    Ok(port)
}

/// Remove only superseded developer-era Cyclone gateway executables.
///
/// Older setup scripts used a few fixed gateway image names and could leave one running with a
/// visible console window across an in-place update. The packaged Companion owns the hidden
/// `CyclonePCRuntime` sidecar now. Only these known legacy image names are retired; this is not a
/// wildcard process kill and does not touch the current Companion process.
#[cfg(windows)]
fn cleanup_legacy_gateway_processes() {
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const LEGACY_IMAGES: [&str; 3] = [
        "cyclone-device-gateway.exe",
        "Cyclone Device Gateway.exe",
        "CycloneDeviceGateway.exe",
    ];
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

    let token = strong_token();
    let gateway_port = reserve_loopback_port().expect("Cyclone could not reserve a local Gateway port");
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
            let command = app
                .shell()
                .sidecar("CyclonePCRuntime")?
                .arg("serve")
                .env("CYCLONE_DEVICE_GATEWAY_TOKEN", &runtime_token)
                .env("CYCLONE_DEVICE_GATEWAY_URL", &runtime_http_base)
                .env("CYCLONE_DEVICE_GATEWAY_PORT", &runtime_port)
                .env("CYCLONE_DEVICE_GATEWAY_RUNTIME", runtime_dir.to_string_lossy().to_string())
                .env("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "1")
                .env("CYCLONE_PC_PARENT_PID", &parent_pid);
            let (mut events, _child) = command.spawn()?;
            tauri::async_runtime::spawn(async move {
                // Drain sidecar output so pipes can never fill and stall the Gateway. The Python
                // runtime also watches the parent PID and exits if this Companion process ends.
                while events.recv().await.is_some() {}
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            gateway_session,
            diagnostics_folder,
            open_diagnostics_folder,
            connector_status,
            connector_action
        ])
        .run(tauri::generate_context!())
        .expect("error while running Cyclone PC Companion");
}
