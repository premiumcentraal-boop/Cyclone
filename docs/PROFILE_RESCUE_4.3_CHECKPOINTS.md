# Cyclone 4.3 profile continuity sprint

Base v4.2.9: ae52303b834d88e9e40983a7415c1b3bc1558896.
Branch agent/430-profile-rescue.

Checkpoint 1: setup-independent Return to Profile A entry point in every secondary
user. Root switch verification polls instead of assuming am switch-user is synchronous.
If the root manager denies root inside an existing user, expose the Android profile
switcher directly. No app can bypass a root-manager denial by copying preferences.

Next: prepare app settings and supported grants before switching; preserve rescue
identity across private user storage; deterministic tests; 4.3.0 / 91 CI and release.
Physical phone testing UNVERIFIED.
