import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Icon({ size = 16, children, ...props }: IconProps) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.55" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{children}</svg>;
}

export function SearchIcon(props: IconProps) { return <Icon {...props}><circle cx="10.7" cy="10.7" r="5.8" /><path d="m15.1 15.1 4.2 4.2" /></Icon>; }
export function PlusIcon(props: IconProps) { return <Icon {...props}><path d="M12 5v14M5 12h14" /></Icon>; }
export function MonitorIcon(props: IconProps) { return <Icon {...props}><rect x="3.5" y="4.5" width="17" height="12" rx="2" /><path d="M8.5 20h7M12 16.5V20" /></Icon>; }
export function MicrophoneIcon(props: IconProps) { return <Icon {...props}><rect x="8.5" y="3" width="7" height="11" rx="3.5" /><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3M8.5 21h7" /></Icon>; }
export function ClockIcon(props: IconProps) { return <Icon {...props}><circle cx="12" cy="12" r="8.5" /><path d="M12 7v5l3.2 2" /></Icon>; }
export function CloseIcon(props: IconProps) { return <Icon {...props}><path d="m6 6 12 12M18 6 6 18" /></Icon>; }
export function ExpandIcon(props: IconProps) { return <Icon {...props}><path d="M8.5 3.5H3.5v5M15.5 3.5h5v5M20.5 15.5v5h-5M3.5 15.5v5h5" /></Icon>; }
export function AttachmentIcon(props: IconProps) { return <Icon {...props}><path d="m8.7 12.5 5.9-5.9a3 3 0 1 1 4.2 4.2l-7.4 7.4a4.5 4.5 0 0 1-6.4-6.4l7.1-7.1" /></Icon>; }
export function ChevronIcon(props: IconProps) { return <Icon {...props}><path d="m8 10 4 4 4-4" /></Icon>; }
export function ArrowUpIcon(props: IconProps) { return <Icon {...props}><path d="m12 18V6M7 11l5-5 5 5" /></Icon>; }
export function MoreIcon(props: IconProps) { return <Icon {...props}><circle cx="5" cy="12" r=".8" fill="currentColor"/><circle cx="12" cy="12" r=".8" fill="currentColor"/><circle cx="19" cy="12" r=".8" fill="currentColor"/></Icon>; }
export function CheckIcon(props: IconProps) { return <Icon {...props}><path d="m5 12.5 4.4 4.1L19 7.4" /></Icon>; }
export function UserCursorIcon(props: IconProps) { return <Icon {...props}><path d="m5 3 8.6 16.9 1.9-6 5.5-1.9L5 3Z" /></Icon>; }
export function ReconnectIcon(props: IconProps) { return <Icon {...props}><path d="M19 8a7.5 7.5 0 1 0 .4 7.3M19 4v4h-4" /></Icon>; }
export function PanelIcon(props: IconProps) { return <Icon {...props}><rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M9 4.5v15" /></Icon>; }
export function KeyboardIcon(props: IconProps) { return <Icon {...props}><rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h.1M10 10h.1M13 10h.1M16 10h.1M7 14h10" /></Icon>; }
export function WarningIcon(props: IconProps) { return <Icon {...props}><path d="M12 3.5 21 19H3l9-15.5Z"/><path d="M12 9v4M12 16.2v.1" /></Icon>; }
export function PauseIcon(props: IconProps) { return <Icon {...props}><path d="M8 5v14M16 5v14" /></Icon>; }
export function PlayIcon(props: IconProps) { return <Icon {...props}><path d="m8 5 11 7-11 7V5Z" /></Icon>; }
export function SettingsIcon(props: IconProps) { return <Icon {...props}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.8 1.8 0 0 0 .36 2l.08.08-2.1 2.1-.08-.08a1.8 1.8 0 0 0-2-.36 1.8 1.8 0 0 0-1.1 1.65v.11h-3v-.11a1.8 1.8 0 0 0-1.1-1.65 1.8 1.8 0 0 0-2 .36l-.08.08-2.1-2.1.08-.08a1.8 1.8 0 0 0 .36-2A1.8 1.8 0 0 0 5 13.9h-.1v-3H5a1.8 1.8 0 0 0 1.65-1.1 1.8 1.8 0 0 0-.36-2l-.08-.08 2.1-2.1.08.08a1.8 1.8 0 0 0 2 .36A1.8 1.8 0 0 0 11.5 4.4v-.1h3v.1a1.8 1.8 0 0 0 1.1 1.65 1.8 1.8 0 0 0 2-.36l.08-.08 2.1 2.1-.08.08a1.8 1.8 0 0 0-.36 2A1.8 1.8 0 0 0 21 10.9h.1v3H21a1.8 1.8 0 0 0-1.6 1.1Z" /></Icon>; }
export function CircleIcon(props: IconProps) { return <Icon {...props}><circle cx="12" cy="12" r="7" /></Icon>; }
export function SendIcon(props: IconProps) { return <Icon {...props}><path d="m4 4 16 8-16 8 3-8-3-8ZM7 12h9" /></Icon>; }
export function ExternalIcon(props: IconProps) { return <Icon {...props}><path d="M14 4h6v6M20 4l-9 9"/><path d="M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5" /></Icon>; }
export function SpinnerIcon(props: IconProps) { return <Icon {...props} className={`spinner ${props.className ?? ""}`}><path d="M20 12a8 8 0 1 1-2.34-5.66" /></Icon>; }
export function ChatIcon(props: IconProps) { return <Icon {...props}><path d="M20 11.4a7.7 7.7 0 0 1-8 7.4 8.4 8.4 0 0 1-3.2-.64L4 20l1.5-4A7.1 7.1 0 0 1 4 11.4 7.7 7.7 0 0 1 12 4a7.7 7.7 0 0 1 8 7.4Z" /></Icon>; }
