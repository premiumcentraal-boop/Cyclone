import { PlusIcon } from "./Icons";

interface TitleBarProps {
  onNewConversation?: () => void;
  onWindowAction?: (action: "close" | "minimize" | "maximize") => void;
}

// Native-looking Windows controls (─ □ ×) top-right; the thin plus sits at
// the top-right of the sidebar. No macOS traffic lights (DESIGN.md §3-4).
function MinIcon() {
  return <svg width="11" height="11" viewBox="0 0 11 11" aria-hidden="true"><line x1="1.5" y1="5.5" x2="9.5" y2="5.5" stroke="currentColor" strokeWidth="1" /></svg>;
}
function MaxIcon() {
  return <svg width="11" height="11" viewBox="0 0 11 11" aria-hidden="true"><rect x="2" y="2" width="7" height="7" fill="none" stroke="currentColor" strokeWidth="1" /></svg>;
}
function CloseIcon() {
  return <svg width="11" height="11" viewBox="0 0 11 11" aria-hidden="true"><path d="M2 2 L9 9 M9 2 L2 9" stroke="currentColor" strokeWidth="1" /></svg>;
}

export function TitleBar({ onNewConversation, onWindowAction }: TitleBarProps) {
  return <div className="titlebar" data-tauri-drag-region>
    <button type="button" className="titlebar__new" aria-label="New conversation" title="New conversation" onClick={onNewConversation}>+</button>
    <div className="window-controls" aria-label="Window controls">
      <button type="button" className="window-control window-control--minimize" aria-label="Minimize window" onClick={() => onWindowAction?.("minimize")}><MinIcon /></button>
      <button type="button" className="window-control window-control--maximize" aria-label="Maximize window" onClick={() => onWindowAction?.("maximize")}><MaxIcon /></button>
      <button type="button" className="window-control window-control--close" aria-label="Close window" onClick={() => onWindowAction?.("close")}><CloseIcon /></button>
    </div>
  </div>;
}
