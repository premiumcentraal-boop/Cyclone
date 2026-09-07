# Cyclone 4.0.4 — simpler setup (work in progress)

Base: published v4.0.3, 28cfd216b803696796167124084a6755847c9aa3. VersionCode 75.

Root cause: v4.0.3 retained the Play Store-only helper install, hid Back during installation, and added a registry form rather than provisioning an Android profile. Green CI did not verify external store availability or the end-user setup journey.

Plan: (1) always-available exit and official helper distribution, (2) in-app verified APK download and Android install approval, (3) Profile A / create Profile B / choose apps / create / ready using bounded root provisioning and automatic registration, (4) Android 13 compatibility audit with newer background-display features gated, (5) green authoritative CI then publish its exact APK with sidecars.

No phone, Pixel, USB or adb testing. Physical/UI acceptance UNVERIFIED. Publication disabled until the implementation is complete.

Evidence: https://github.com/RikkaApps/Shizuku/issues/1974 and https://shizuku.rikka.app/download/ identify the Android 16 Play distribution problem and official GitHub distribution.
