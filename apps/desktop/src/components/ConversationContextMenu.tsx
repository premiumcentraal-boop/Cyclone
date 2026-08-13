import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
import type { ConversationSummary } from "../types";
import { CopyIcon, EditIcon, FolderPlusIcon, HideIcon, PinIcon, TrashIcon, UnreadIcon } from "./Icons";

export type ConversationMenuAction = "pin" | "section" | "unread" | "edit" | "duplicate" | "copy-id" | "hide" | "delete";

export interface ConversationContextMenuState {
  conversation: ConversationSummary;
  x: number;
  y: number;
}

export function ConversationContextMenu({ state, onAction, onClose }: {
  state: ConversationContextMenuState;
  onAction: (action: ConversationMenuAction) => void;
  onClose: () => void;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const left = Math.max(8, Math.min(state.x, window.innerWidth - 220));
  const top = Math.max(8, Math.min(state.y, window.innerHeight - 358));

  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (menu.current && !menu.current.contains(event.target as Node)) onClose();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [onClose]);

  const choose = (action: ConversationMenuAction) => {
    onAction(action);
    onClose();
  };

  return <div ref={menu} className="conversation-context-menu" role="menu" aria-label={`Actions for ${state.conversation.title}`} style={{ left, top }}>
    <MenuItem icon={<PinIcon size={16} />} label={state.conversation.is_pinned ? "Unpin" : "Pin"} onClick={() => choose("pin")} />
    <MenuItem icon={<FolderPlusIcon size={16} />} label="Move to new section" onClick={() => choose("section")} />
    <MenuItem icon={<UnreadIcon size={16} />} label={state.conversation.is_unread ? "Mark as Read" : "Mark as Unread"} onClick={() => choose("unread")} />
    <MenuDivider />
    <MenuItem icon={<EditIcon size={16} />} label="Edit Profile" onClick={() => choose("edit")} />
    <MenuItem icon={<CopyIcon size={16} />} label="Duplicate" onClick={() => choose("duplicate")} />
    <MenuDivider />
    <MenuItem icon={<CopyIcon size={16} />} label="Copy conversation ID" onClick={() => choose("copy-id")} />
    <MenuDivider />
    <MenuItem icon={<HideIcon size={16} />} label="Hide from sidebar" onClick={() => choose("hide")} />
    <MenuDivider />
    <MenuItem icon={<TrashIcon size={16} />} label="Delete" destructive onClick={() => choose("delete")} />
  </div>;
}

function MenuItem({ icon, label, destructive = false, onClick }: { icon: ReactNode; label: string; destructive?: boolean; onClick: () => void }) {
  return <button type="button" className={`conversation-context-menu__item${destructive ? " conversation-context-menu__item--destructive" : ""}`} role="menuitem" onClick={onClick}>
    {icon}<span>{label}</span>
  </button>;
}

function MenuDivider() { return <div className="conversation-context-menu__divider" role="separator" />; }
