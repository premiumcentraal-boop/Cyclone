#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::process::Command;
use tauri::{AppHandle, Manager, WebviewWindow};

#[tauri::command]
fn docker_available() -> bool {
    Command::new("docker")
        .arg("info")
        .output()
        .map(|result| result.status.success())
        .unwrap_or(false)
}

#[tauri::command]
fn core_url() -> String {
    std::env::var("CYCLONE_CORE_URL").unwrap_or_else(|_| "http://127.0.0.1:8787".to_string())
}

#[tauri::command]
fn minimize_window(window: WebviewWindow) -> Result<(), String> {
    window.minimize().map_err(|error| error.to_string())
}

#[tauri::command]
fn toggle_maximize_window(window: WebviewWindow) -> Result<(), String> {
    let maximized = window.is_maximized().map_err(|error| error.to_string())?;
    if maximized {
        window.unmaximize().map_err(|error| error.to_string())
    } else {
        window.maximize().map_err(|error| error.to_string())
    }
}

#[tauri::command]
fn close_window(window: WebviewWindow) -> Result<(), String> {
    window.close().map_err(|error| error.to_string())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![
            docker_available,
            core_url,
            minimize_window,
            toggle_maximize_window,
            close_window
        ])
        .setup(|app| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_focus();
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Cyclone desktop");
}
