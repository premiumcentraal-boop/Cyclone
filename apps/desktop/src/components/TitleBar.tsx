import { PlusIcon } from "./Icons";

interface TitleBarProps {
  onNewConversation?: () => void;
  onWindowAction?: (action: "close" | "minimize" | "maximize") => void;
}

export function TitleBar({ onNewConversation, onWindowAction }: TitleBarProps) {
  return <div className="titlebar" data-tauri-drag-region>
    <div className="window-controls" aria-label="Window controls">
      <button type="button" className="window-control window-control--close" aria-label="Close window" onClick={() => onWindowAction?.("close")} />
      <button type="button" className="window-control window-control--minimize" aria-label="Minimize window" onClick={() => onWindowAction?.("minimize")} />
      <button type="button" className="window-control window-control--maximize" aria-label="Maximize window" onClick={() => onWindowAction?.("maximize")} />
    </div>
    <button type="button" className="titlebar__new" aria-label="New conversation" title="New conversation" onClick={onNewConversation}><PlusIcon size={15} /></button>
  </div>;
}

export function WindowControlPlaceholder() {
  return <div className="titlebar__placeholder" />;
}
