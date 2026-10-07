# Karen — agent conventions

Offline-first Android AI assistant. Jetpack Compose, `com.karen` (`app/src/main/java/com/karen/`).
Master plan: `docs/Karen_MASTER_MERGED_PLAN_v2.2.md`. Design tokens: `docs/DESIGN.md`.

## Build

```powershell
cd D:\karen; .\gradlew.bat :app:compileDebugKotlin --offline
```

SDK path is in `local.properties`. Always recompile after UI edits; `-q` hides success.

## Theme (old ChatGPT style — do not "upgrade" unprompted)

`ui/KarenTheme.kt` is the source of truth: OLED `#000000` / Charcoal `#212121` /
Light `#FFFFFF`, `accentGreen #10A37F` (primary), `accentBlue #38BDF8` (secondary).
Shared chrome lives in `ui/KarenCommon.kt` (`ChatGPTTopAppBar`, `KScreen`,
`Section`, `KV`, `KCard`), `ui/KarenChrome.kt`, `ui/KarenComponents.kt`.
White (`Color.White`) icon/text on accent fills — never dark-on-accent.

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
