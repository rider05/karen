# Karen — agent conventions

Offline-first Android AI assistant. Jetpack Compose, `com.karen` (`app/src/main/java/com/karen/`).
Master plan: `docs/Karen_MASTER_MERGED_PLAN_v2.2.md`. Design tokens: `docs/DESIGN.md`.

## Build

```powershell
cd D:\karen; .\gradlew.bat :app:compileDebugKotlin --offline
```

SDK path is in `local.properties`. Always recompile after UI edits; `-q` hides success.

**Shipping `apks/karen.apk`:** always build the **release** APK, never
`assembleDebug` — debug is signed with the Android Debug key and fails on
user devices with "Signing certificate mismatch":

```powershell
.\gradlew.bat :app:assembleRelease --offline
Copy-Item app\build\outputs\apk\release\app-release.apk apks\karen.apk -Force
```

`karen-release.jks` (CN=Karen, repo root) is the installed signing key —
release builds sign with it via the `release` signingConfig in
`app/build.gradle.kts`, so a fresh `apks/karen.apk` installs over the
current app with no uninstall. After copying, commit the APK refresh and
push; a cached download may still show the old build — hard-refresh.
**Slim rule:** keep `abiFilters` arm64-v8a only — adding x86_64 balloons the
APK past GitHub's 100 MB push limit.

## Theme (Professional Workspace — per `docs/DESIGN.md`)

`ui/KarenTheme.kt` is the source of truth: canvas `#0F1115` / raised `#1F2530`
(dark), canvas `#F8FAFC` / surface `#FFFFFF` (light), slate text `#E5E9F0` /
`#0F172A`, borders `#2B3342` / `#E2E8F0`. `accentGreen` carries the **primary
blue** `#2563EB` (most call sites use it for primary actions), `accentBlue`
`#60A5FA` is the secondary tint, `accentSuccess` `#34D399`/`#059669` is the
green status color. Shared chrome lives in `ui/KarenCommon.kt`
(`ChatGPTTopAppBar`, `KScreen`, `Section`, `KV`, `KCard`), `ui/KarenChrome.kt`,
`ui/KarenComponents.kt`. White (`Color.White`) icon/text on accent fills — never
dark-on-accent.

## Standing rule: every screen navigates Home

System back must stay in-app (never exit to the phone home), exactly like Chat.
Every new screen **must** include all three:

1. Param: `onNavigateToHome: () -> Unit = {}`.
2. Header: `onBackClick = onNavigateToHome` on `ChatGPTTopAppBar`
   (renders the back arrow; model/effort pills stay centered automatically).
3. System back: `KarenHomeBackHandler` (`ui/KarenCommon.kt`) — consume sheets,
   dialogs, and inner levels first, fall through to Home:

```kotlin
KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
    if (showSheet) { showSheet = false; true } else false
}
```

4. Route wiring in `ui/KarenApp.kt`: `onNavigateToHome = { currentScreen = "Home" }`
   plus a drawer entry in `ChatGPTDrawerContent` when the screen is user-facing.

## Sheets, menus, dialogs

- Bottom sheets (`ModelSelectorSheet`, `AttachmentSheet`): dismiss-then-act on
  every row; add `BackHandler` coverage in the owning screen.
- Header overflow: anchored `DropdownMenu` at the more button (Popup — never a
  `fillMaxSize` overlay inside the header `Column`; that blanks content).
- API keys / secrets: `UserPrefs` `SharedPreferences` only, per-provider id,
  masked display (`••••abcd`), explicit Save/Remove. Never log keys.
  Live calls live in `ui/CloudChatApi.kt` (HttpURLConnection + org.json, no new
  deps); chat routes to a keyed provider selected in the model sheet.
  Reasoning providers (`reasoning = true`) get effort-driven thinking budgets
  and show the effort pill; all other models hide it.
- Text inputs: every `OutlinedTextField` must use `karenFieldColors(colors)`
  (`ui/KarenCommon.kt`) — M3 defaults are light-scheme and vanish on dark.
- Web search (`ui/WebSearch.kt`): keyless DDG + Wikipedia, heuristic trigger,
  one-time consent dialog (`webSearchAsked`), Settings → Privacy toggle.
