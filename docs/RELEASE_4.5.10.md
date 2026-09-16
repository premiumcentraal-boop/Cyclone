# Cyclone Mobile 4.5.10

Android versionCode 119; builds on published 4.5.9.

Login walls now offer three real actions: **Autofill**, **Take Over**, and **I'm Done**.

- Autofill focuses the sign-in fields so Android's password manager can fill, then taps the unique Sign in control. Cyclone does not store or type passwords.
- Take Over hands the screen to you. I'm Done continues after you finish.
- Easy named-app opens still complete without this gate. "Open Facebook and login" and DMs stay Medium and cannot finish just because the app opened.

Difficulty still starts from the ask (Easy / Medium / Hard). The screen can only raise the tier: two real apps in one run, or two no-progress turns on a two-app goal, become Hard. A router model is not used.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
