import { CSSProperties, PointerEvent, RefObject, useEffect, useId, useMemo, useRef, useState } from "react";
import { agentColor, agentShapeFromSlug, avatarEyesForState, shade } from "../agent-visuals";
import type { Agent, AgentAvatarShape } from "../types";

export interface BotAvatarProps {
  agent: Agent;
  size?: number;
  className?: string;
  title?: string;
  interactive?: boolean;
  pulse?: boolean;
  onClick?: () => void;
}

interface Geometry {
  clipPath: string;
  outlinePath: string;
}

function geometry(shape: AgentAvatarShape): Geometry {
  switch (shape) {
    case "blob":
      return {
        clipPath: "M50 6 C70 3 88 14 93 33 C99 54 88 76 70 87 C53 98 29 95 15 79 C2 63 2 38 17 23 C27 12 36 8 50 6 Z",
        outlinePath: "M50 6 C70 3 88 14 93 33 C99 54 88 76 70 87 C53 98 29 95 15 79 C2 63 2 38 17 23 C27 12 36 8 50 6 Z",
      };
    case "capsule":
      return {
        clipPath: "M50 7 C31 7 17 21 17 50 C17 79 31 93 50 93 C69 93 83 79 83 50 C83 21 69 7 50 7 Z",
        outlinePath: "M50 7 C31 7 17 21 17 50 C17 79 31 93 50 93 C69 93 83 79 83 50 C83 21 69 7 50 7 Z",
      };
    case "polygon":
      return {
        clipPath: "M50 6 L84 28 L84 72 L50 94 L16 72 L16 28 Z",
        outlinePath: "M50 6 L84 28 L84 72 L50 94 L16 72 L16 28 Z",
      };
    case "cloud":
      return {
        clipPath: "M50 30 C40 16 18 18 12 33 C2 34 -1 50 10 58 C8 74 26 84 42 78 C54 90 80 86 84 70 C97 66 96 44 82 40 C76 24 58 24 50 30 Z",
        outlinePath: "M50 30 C40 16 18 18 12 33 C2 34 -1 50 10 58 C8 74 26 84 42 78 C54 90 80 86 84 70 C97 66 96 44 82 40 C76 24 58 24 50 30 Z",
      };
    case "droplet":
      return {
        clipPath: "M50 6 C50 6 90 46 90 68 C90 86 74 95 50 95 C26 95 10 86 10 68 C10 46 50 6 50 6 Z",
        outlinePath: "M50 6 C50 6 90 46 90 68 C90 86 74 95 50 95 C26 95 10 86 10 68 C10 46 50 6 50 6 Z",
      };
    case "triangle":
      return {
        clipPath: "M50 10 L90 84 L10 84 Z",
        outlinePath: "M50 10 L90 84 L10 84 Z",
      };
    case "pebble":
      return {
        clipPath: "M49 8 C74 5 91 20 92 45 C94 67 78 91 52 92 C27 93 8 76 8 50 C8 27 24 11 49 8 Z",
        outlinePath: "M49 8 C74 5 91 20 92 45 C94 67 78 91 52 92 C27 93 8 76 8 50 C8 27 24 11 49 8 Z",
      };
    case "squircle":
      return {
        clipPath: "M34 8 C23 8 9 20 9 34 L9 66 C9 80 22 92 35 92 L65 92 C80 92 92 79 92 65 L92 35 C92 20 79 8 65 8 Z",
        outlinePath: "M34 8 C23 8 9 20 9 34 L9 66 C9 80 22 92 35 92 L65 92 C80 92 92 79 92 65 L92 35 C92 20 79 8 65 8 Z",
      };
    default:
      return {
        clipPath: "M50 7 C74 7 93 26 93 50 C93 74 74 93 50 93 C26 93 7 74 7 50 C7 26 26 7 50 7 Z",
        outlinePath: "M50 7 C74 7 93 26 93 50 C93 74 74 93 50 93 C26 93 7 74 7 50 C7 26 26 7 50 7 Z",
      };
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function BotAvatar({ agent, size = 32, className = "", title, interactive = true, pulse = false, onClick }: BotAvatarProps) {
  const [gaze, setGaze] = useState({ x: 0, y: 0 });
  const [blink, setBlink] = useState(false);
  const [glance, setGlance] = useState({ x: 0, y: 0 });
  const rootRef = useRef<HTMLButtonElement | HTMLSpanElement>(null);
  const gradientId = useId().replaceAll(":", "");
  const shape = agent.avatar_shape ?? agentShapeFromSlug(agent.slug);
  const path = geometry(shape);
  const fill = agentColor(agent);
  const darker = shade(fill, -10);
  const lighter = shade(fill, 12);
  const eyeState = avatarEyesForState(agent.status);
  const motionAllowed = typeof window === "undefined" || !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  useEffect(() => {
    if (!motionAllowed) return undefined;
    let blinkTimer = 0;
    let glanceTimer = 0;
    const scheduleBlink = () => {
      blinkTimer = window.setTimeout(() => {
        setBlink(true);
        window.setTimeout(() => setBlink(false), 140);
        scheduleBlink();
      }, 3500 + Math.random() * 5300);
    };
    const scheduleGlance = () => {
      glanceTimer = window.setTimeout(() => {
        const direction = eyeState === "thinking" ? (Math.random() > 0.5 ? 1.2 : -1.2) : (Math.random() - 0.5) * 1.8;
        setGlance({ x: direction, y: (Math.random() - 0.5) * 0.8 });
        window.setTimeout(() => setGlance({ x: 0, y: 0 }), 700 + Math.random() * 600);
        scheduleGlance();
      }, 4000 + Math.random() * 5000);
    };
    scheduleBlink();
    scheduleGlance();
    return () => {
      window.clearTimeout(blinkTimer);
      window.clearTimeout(glanceTimer);
    };
  }, [eyeState, motionAllowed]);

  function trackPointer(event: PointerEvent<HTMLElement>) {
    if (!interactive || !rootRef.current || !motionAllowed) return;
    const bounds = rootRef.current.getBoundingClientRect();
    const x = clamp(((event.clientX - bounds.left) / bounds.width - 0.5) * 3.6, -1.8, 1.8);
    const y = clamp(((event.clientY - bounds.top) / bounds.height - 0.5) * 2.6, -1.3, 1.3);
    setGaze({ x, y });
  }

  const eyeX = gaze.x + glance.x + (eyeState === "thinking" ? 0.35 : 0);
  const eyeY = gaze.y + glance.y;
  const eyeScale = blink ? 0.08 : 1;
  const classNames = ["bot-avatar", `bot-avatar--${shape}`, `bot-avatar--${eyeState}`, pulse ? "bot-avatar--pulse" : "", className].filter(Boolean).join(" ");
  const sharedProps = {
    className: classNames,
    style: { width: size, height: size, "--avatar-size": `${size}px` } as CSSProperties,
    onPointerMove: trackPointer,
    onPointerLeave: () => setGaze({ x: 0, y: 0 }),
    title: title ?? agent.name,
    "aria-label": title ?? agent.name,
  };

  const drawing = useMemo(() => (
    <svg viewBox="0 0 100 100" role="img" aria-hidden="true" focusable="false">
      <defs>
        <radialGradient id={gradientId} cx="32%" cy="23%" r="82%">
          <stop offset="0%" stopColor={lighter} />
          <stop offset="60%" stopColor={fill} />
          <stop offset="100%" stopColor={darker} />
        </radialGradient>
        <filter id={`${gradientId}-soft`} x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="0.35" />
        </filter>
      </defs>
      <path d={path.outlinePath} fill="url(#${gradientId})" filter={`url(#${gradientId}-soft)`} />
      <path d={path.clipPath} fill="none" stroke="rgba(255,255,255,.24)" strokeWidth="1.2" />
      <ellipse cx="34" cy="28" rx="16" ry="11" fill="rgba(255,255,255,.11)" transform="rotate(-23 34 28)" />
      <g transform={`translate(${eyeX} ${eyeY})`} className="bot-avatar__eyes" style={{ transformOrigin: "50px 50px" }}>
        <rect x="35.4" y="40" width="8.2" height="21" rx="4.1" transform={`rotate(-17 39.5 50.5) scale(1 ${eyeScale})`} fill="#fff" />
        <rect x="57.2" y="40" width="8.2" height="21" rx="4.1" transform={`rotate(17 61.3 50.5) scale(1 ${eyeScale})`} fill="#fff" />
      </g>
    </svg>
  ), [darker, eyeScale, eyeX, eyeY, fill, gradientId, lighter, path.clipPath, path.outlinePath]);

  if (onClick) {
    return <button {...sharedProps} ref={rootRef as RefObject<HTMLButtonElement>} type="button" onClick={onClick}>{drawing}</button>;
  }
  return <span {...sharedProps} ref={rootRef as RefObject<HTMLSpanElement>}>{drawing}</span>;
}

export function CrewAvatar({ agents, size = 32, className = "", title }: { agents: Agent[]; size?: number; className?: string; title?: string }) {
  const visible = agents.slice(0, 3);
  return <span className={`crew-avatar ${className}`} style={{ width: size, height: size }} title={title ?? visible.map((agent) => agent.name).join(", ")} aria-label={title ?? visible.map((agent) => agent.name).join(", ")}>
    {visible.map((agent, index) => <BotAvatar key={agent.id} agent={agent} size={Math.round(size * 0.64)} className={`crew-avatar__member crew-avatar__member--${index}`} />)}
  </span>;
}
