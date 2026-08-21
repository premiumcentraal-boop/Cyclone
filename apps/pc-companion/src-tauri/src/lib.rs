use rand::{rngs::OsRng, RngCore};
use serde::Serialize;
use tauri::{Manager, State};
use tauri_plugin_shell::ShellExt;

struct GatewayState {
    token: String,
}

#[derive(Serialize)]
struct GatewaySession {
    token: String,
    http_base: &'static str,
    ws_base: &'static str,
}

#[tauri::command]
fn gateway_session(state: State<'_, GatewayState>) -> GatewaySession {
    GatewaySession {
        token: state.token.clone(),
        http_base: "http://127.0.0.1:8765",
        ws_base: "ws://127.0.0.1:8765",
    }
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let token = strong_token();
    let runtime_token = token.clone();

    tauri::Builder::default()
        .manage(GatewayState { token })
        .plugin(tauri_plugin_shell::init())
        .setup(move |app| {
            let runtime_dir = app.path().app_local_data_dir()?.join("runtime");
            std::fs::create_dir_all(&runtime_dir)?;
            let command = app
                .shell()
                .sidecar("CyclonePCRuntime")?
                .arg("serve")
                .env("CYCLONE_DEVICE_GATEWAY_TOKEN", &runtime_token)
                .env("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:8765")
                .env("CYCLONE_DEVICE_GATEWAY_RUNTIME", runtime_dir.to_string_lossy().to_string());
            let (mut events, _child) = command.spawn()?;
            tauri::async_runtime::spawn(async move {
                // Drain sidecar output so pipes can never fill and stall the Gateway. Output is not
                // surfaced to the UI because it may contain low-level diagnostic text.
                while events.recv().await.is_some() {}
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![gateway_session, connector_status, connector_action])
        .run(tauri::generate_context!())
        .expect("error while running Cyclone PC Companion");
}
