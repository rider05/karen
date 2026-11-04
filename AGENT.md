# Agent Instructions — Karen

## What to do
- Build: `cd D:\karen; .\gradlew.bat :app:compileDebugKotlin --offline` (SDK path in `local.properties`).
- Ship every fix the same way: commit code → `git push origin main` → `.\gradlew.bat :app:assembleRelease --offline` → copy `app/build/outputs/apk/release/app-release.apk` → `apks/karen.apk` → commit APK refresh → push.
- Ship the **release** APK, never `assembleDebug`: debug is signed with the Android Debug key and breaks installs with "Signing certificate mismatch". `karen-release.jks` (CN=Karen) is the installed key.
- Verify behavior fixes on the `My_Phone` emulator before closing the task (boot headless, `-memory 5120`, install via `adb install -r`, screenshot via `adb exec-out uiautomator dump`).

## What not to do
- Don't restyle the theme unprompted — `ui/KarenTheme.kt` owns the OLED/charcoal/light token set.
- Don't use M3 default text colors on text fields — use `karenFieldColors` from `ui/KarenCommon.kt`.
- Don't use `fillMaxSize` overlays in header columns for popups — anchored `DropdownMenu` (Popup) only.
- Don't add dependencies without being asked.
- Don't log or persist API keys/secrets.
- Don't ship behavior or UI fixes without emulator verification.
