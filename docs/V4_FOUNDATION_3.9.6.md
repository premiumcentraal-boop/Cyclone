# V4 foundation seams after Cyclone 3.9.6

3.9.6 adds session and live-frame seams only. Production execution remains one foreground Accessibility workspace on display 0.

Missing/implicit session = default-foreground = displayId 0 = legacy behavior.
Synthetic sessions may be registered but executable=false.
LiveFrameBroker.latest() never captures.

NOT implemented: MediaProjection, continuous capture, scrcpy bridge, setDisplayId routing, multi-display observation, Shizuku, ADB display daemon, root, virtual displays, hidden workspace tools, human handoff.
