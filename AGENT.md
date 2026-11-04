# Agent Instructions — Karen

## What to do
- Build: `cd D:\karen; .\gradlew.bat :app:compileDebugKotlin --offline` (SDK path in `local.properties`); `assembleDebug` before shipping an APK to `apks/karen.apk`.
- Ship every fix the same way: commit code → `git push origin main` → copy `app/build/outputs/apk/debug/app-debug.apk` → `apks/karen.apk` → commit APK refresh → push.
- Verify behavior fixes on the `My_Phone` emulator before closing the task (boot headless, `-memory 5120`, install via `adb install -r`, screenshot via `adb exec-out uiautomator dump`).

## What not to do
- Don't restyle the theme unprompted — `ui/KarenTheme.kt` owns the OLED/charcoal/light token set.
- Don't use M3 default text colors on text fields — use `karenFieldColors` from `ui/KarenCommon.kt`.
- Don't use `fillMaxSize` overlays in header columns for popups — anchored `DropdownMenu` (Popup) only.
- Don't add dependencies without being asked.
- Don't log or persist API keys/secrets.
- Don't ship behavior or UI fixes without emulator verification.
