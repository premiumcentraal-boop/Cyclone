use serde_json::{json, Value};
use std::path::PathBuf;

fn root() -> Result<PathBuf, String> {
    let local = std::env::var_os("LOCALAPPDATA").ok_or("Live Phone requires Windows")?;
    let path = PathBuf::from(local).join("Cyclone One").join("live-phone");
    std::fs::create_dir_all(&path).map_err(|_| "Could not prepare Live Phone")?;
    Ok(path)
}

#[tauri::command]
pub fn live_phone_control(action: String) -> Result<Value, String> {
    if !["enable", "pause", "stop"].contains(&action.as_str()) { return Err("Unknown Live Phone control".into()); }
    let path = root()?;
    let state = json!({"enabled": action == "enable", "stopped": action == "stop"});
    std::fs::write(path.join("control.json"), state.to_string()).map_err(|_| "Could not change Live Phone control")?;
    if action == "stop" {
        for name in ["latest.png", "latest.jpg"] { let _ = std::fs::remove_file(path.join(name)); }
    }
    Ok(state)
}

#[tauri::command]
pub fn live_phone_status() -> Result<Value, String> {
    let path = root()?;
    let state = std::fs::read(path.join("status.json")).ok()
        .and_then(|v| serde_json::from_slice::<Value>(&v).ok()).unwrap_or(json!({}));
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs();
    let recent = now.saturating_sub(state["at"].as_u64().unwrap_or(0)) < 15;
    Ok(json!({"connected": recent, "vision": recent && state["vision"].as_bool().unwrap_or(false), "control": recent && state["control"].as_bool().unwrap_or(false)}))
}
