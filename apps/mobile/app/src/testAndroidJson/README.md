# Android's own org.json, for unit tests

Unit tests run on the desktop JVM, where `android.jar`'s `org.json` is only stubs. Until alpha 141 they used the
JSON-java library (`org.json:json`), which behaves differently from the phone: for example, `optString` on an
explicit JSON `null` returns `""` there but `"null"` on Android. Code that passed every test could break on the phone.

This folder holds AOSP's implementation, `libcore/json/src/main/java/org/json` from `android14-release`
(Apache License 2.0, copyright The Android Open Source Project). The only edit is that Android-internal annotations
(`@UnsupportedAppUsage`, `@SystemApi`, `libcore.util.NonNull/Nullable`) were removed so it compiles here. Unit tests
now see exactly the JSON behaviour the phone has. Guarded by `scripts/ci/tests/test_android_json_guard.py`.
