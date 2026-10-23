# Karen Build Plan v3 — Context, Extensibility, Resilience

Status: proposed · Drafted 20 Oct 2026 · Supersedes nothing

This plan sits **on top of** `Karen_MASTER_MERGED_PLAN_v2.2.md`. Sections 9 (tool contract), 14 (roadmap gates) and 16 (backlog) still govern. What follows is the missing middle: a concrete, file-level sequence for the capabilities surfaced by the OpenCode / ChatGPT-Codex desktop teardown (`D:\chatgpt-re\OPENCODE-CHATGPT-DESKTOP.md`) and the four-way Android teardown (`D:\chatgpt-re\KAREN-THINAI-CLAUDE-CHATGPT.md`), scoped against what actually ships today.

Three themes, in priority order:

1. **Resilience** — stop losing user data silently (wave 0).
2. **Extensibility** — let capability grow without an app update (waves 2–3).
3. **Context** — let Karen see what the user sees (waves 6–7).

---

## 1. Baseline — what already exists

Verified against source on 20 Oct 2026. Do not rebuild any of this. Line numbers are pointers, not anchors — `ChatScreen.kt` and `CloudChatApi.kt` are under active edit, so re-locate by symbol (`ToolSpecs`, `ToolRouting`, `ChatHistoryStore`) before touching anything.

| Subsystem | Where | State |
|---|---|---|
| Chat + streaming + attachments | `ui/ChatScreen.kt` (167 KB) | shipped |
| Tool specs (single source of truth) | `assets/tools.json` → `PhoneTools.kt:245` `ToolSpecs` | shipped |
| Tool routing / alias resolution | `ToolRouter.kt:70` `ToolRouting` | shipped |
| Risk tiers T0/T1/T2 + confirm cards | `PhoneTools.kt:51`, `ChatScreen.kt:606,1715` | shipped |
| Agent loop | `ui/AgentRunner.kt`, `ChatScreen.kt:558-679` | shipped |
| Chat persistence (TSV + Base64) | `ChatHistoryStore.kt` | shipped, **non-atomic** |
| Memory (facts / prompt / style) | `MemoryFacts.kt`, `PromptMemory.kt`, `StyleMemory.kt` | shipped |
| Knowledge vault + lexical RAG | `FilesScreen.kt` (34 KB) | shipped |
| Local API + Bearer auth | `LocalApiServer.kt:2028` | shipped |
| Voice (wake word, STT, TTS) | `WakeWordService.kt`, `VoiceEngine.kt`, `TtsManager.kt` | shipped |
| Models / downloader / ORT | `ModelManagerScreen.kt`, `ModelDownloader.kt`, `OrtEngine.kt` | shipped |
| Telemetry / perf | `DeviceTelemetry.kt`, `PerformanceScreen.kt` | shipped |
| Backup / migration / storage | `ManagersScreens.kt`, `BackupPackageScreen` | shipped |
| Theming | `KarenTheme.kt` + `docs/DESIGN.md` | shipped, **frozen by rule** |
| MCP | — | absent |
| Skills / plugins | — | absent |
| Screen capture | — | absent |
| In-app web view | — | absent |
| Overlay / HUD | — | permission declared, no UI |

Two facts that shape every wave below:

- **`ToolSpecs.load()` caches for the process lifetime** (`PhoneTools.kt:246-287`, `@Volatile cached`) with no invalidation path. Any dynamically-added tool source must add `invalidate()`.
- **`ChatHistoryStore` parses with `getOrNull(n)`** (`:82-90`) and index rows with `parts.size < 3` (`:37`). Appending columns is therefore **backward compatible** — no migration needed for additive fields.

---

## 2. Non-negotiable constraints

From `AGENTS.md` and master plan §9 / §20:

| Constraint | Consequence |
|---|---|
| No new dependencies | `HttpURLConnection` + `org.json` only (per cloud-chat precedent) |
| Every user-facing screen needs route + drawer entry + `KarenHomeBackHandler` | Prefer growing existing screens; each new screen costs ~half a day of chrome |
| Theme is frozen unless asked | No theme work in this plan; wave 8 is opt-in import only |
| Offline-first, no telemetry | Diagnostics stay on-device; export is user-initiated |
| Tool contract: schema + risk tier + eval case | No tool ships without all three |
| `karenFieldColors(colors)` on every text field | Non-negotiable on new inputs |
| Recompile after every UI edit | `.\gradlew.bat :app:compileDebugKotlin --offline` |

Security invariants for waves 2–3 (these are the whole point of doing them carefully):

- **Skills are declarative.** Prompt text + a declared subset of existing tools. No code execution, ever.
- **MCP tools default to T2** regardless of what the server claims about itself.
- **No third-party UI hosting.** Consume MCP tools; do not render MCP apps. (Four-way report §12 already rejected the app sandbox; that still holds.)

---

## 3. Wave summary

| Wave | Deliverable | Effort | Depends on | Ship gate |
|---|---|---|---|---|
| **0** | Atomic persistence + quarantine | 0.5–1 d | — | Kill during save loses nothing silently |
| **1** | Turn trace panel | 1–2 d | — | Every tool call shows args, result, timing |
| **2** | Skills packs | 3–5 d | 1 | Load/unload a skill without restart |
| **3** | MCP client | 5–8 d | 1 | External server's tools callable, T2-gated |
| **4** | Quick-ask HUD | 2–3 d | — | Answer without leaving the current app |
| **5** | Fork / archive conversations | 1–2 d | 0 | Branch a chat; archive hides not deletes |
| **6** | Screen context | 5–8 d | 1 | Capture → answer, fully on-device |
| **7** | Page reader | 3–5 d | — | Open URL, ask about it, no navigation |
| **8** | Artifact integrity | ~1 d | — | Corrupt model refused with clear error |

Wave 8 is independent and can slot anywhere. Waves 2 and 3 are the product bets; 6 is the one users will feel.

---

## 4. Wave 0 — Atomic persistence + quarantine

**Goal.** A torn write must never silently delete history.

**Why.** `ChatHistoryStore.saveConversation` rewrites the whole conversation and the whole index with plain `writeText` (`:107`, `:112`, `:128`). A kill or power loss mid-write truncates the file, and because parsing drops any line it can't fully decode (`:57-92`), the user sees a conversation that is simply *shorter* with no error. This is Codex's damaged-database pattern applied to a file store, and it is the cheapest real bug fix in this plan.

**Work.**

1. Add `private fun writeAtomic(target: File, text: String)`:
   write to `File(dir, "${target.name}.tmp")`, `flush`, `fd.sync()`, then `tmp.renameTo(target)`; delete the temp on failure.
2. Route `:107`, `:112`, `:128` through it.
3. Add `quarantine(file, reason)`: rename to `<name>.damaged-<epoch>` and record one line in `filesDir/diagnostics/store.log`.
4. In `loadMessages`, if a line is present but undecodable, count it and expose `ChatHistoryStore.lastLoadIssues` so the UI can show a one-line banner ("2 messages could not be read — file preserved as …").
5. Settings → Storage: surface quarantined files with size and a delete action.

**Files.** `ui/ChatHistoryStore.kt` (primary), `ui/SettingsScreen.kt` (banner + storage row), `ui/ManagersScreens.kt` (storage listing).

**Accept.** Force-stop the app during a large `saveConversation` (insert a `Thread.sleep(2000)` between write and rename in a debug build): on relaunch the conversation is either intact or quarantined and reported — never silently empty or truncated.

**Rollback.** Single-file change; revert `ChatHistoryStore.kt`.

---

## 5. Wave 1 — Turn trace panel

**Goal.** Make every turn's tool activity inspectable, so later waves (skills, MCP) are debuggable by the user rather than only by you.

**Why now.** The UI shows a compact pill string (`ChatScreen.kt:679` builds `"name · 123ms"`, joined at `:649`). The *data* needed for a real trace is already half-persisted (`ChatItem.Assistant.toolCall`, `tookMs`, `inTokens`, `reasonTokens`, `interrupted`, `usageReal`) — but arguments, results and validation verdicts are discarded after the turn. Extending the `A` row with a trailing Base64 JSON column is backward compatible by construction (`getOrNull`).

**Work.**

1. Define `TurnTrace` (kotlinx-free, plain `JSONObject`):
   ```json
   {"calls":[{"name":"place_call","args":{"contact":"Amma"},"verdict":"ok",
              "detail":"dialed","ms":412,"tier":"T2","approved":true}],
    "recovered":false,"route":"phone"}
   ```
2. Append column 12 to the `A` line in `saveConversation:104`; decode with `getOrNull(12)`.
3. Populate from the existing loop: `extractToolCalls` (`ChatScreen.kt:558`) → `validateToolCall` (`:573`) → result. Collect into a `MutableList<TurnTrace.Call>` per turn.
4. Render as an expandable row under the assistant bubble: collapsed shows `3 tools · 1.8 s`; expanded shows a mono list (name, args one-liner, verdict, ms, tier badge). Reuse the T2 amber mapping (`:2519`).
5. Add "why this tool" line using `routeTool(prompt)` / `ToolRouting.route` result so misroutes are visible.

**Files.** `ui/ChatScreen.kt` (loop + renderer, ~120 lines), `ui/ChatHistoryStore.kt` (column), `ui/KarenComponents.kt` (small `TraceRow` composable).

**Accept.** A turn with one T2 call and one T1 call renders three collapsed rows of correct timing; a killed turn shows `interrupted` and the last completed call only. Old conversations open without error (missing column → empty trace).

**Notes.** Keep the collapsed row to one line — this must not become a debug console in the main column.

---

## 6. Wave 2 — Skills packs

**Goal.** Users add capability by dropping a folder, no rebuild.

**Model.** A skill is **data**, deliberately not code:

```
filesDir/skills/<id>/
  skill.json     # manifest, same vocabulary as tools.json
  prompt.md      # guidance injected only when the skill matches
  assets/        # optional templates the model may reference
```

`skill.json`:

```json
{
  "id": "study-tutor",
  "name": "Study Tutor",
  "version": 1,
  "description": "Socratic tutoring over vault documents",
  "when": ["explain", "quiz me", "flashcards", "revise"],
  "tools": ["search_local_files", "read_document", "remember_fact"],
  "maxExtraTokens": 400,
  "prompt": "prompt.md"
}
```

**Work.**

1. `ui/Skills.kt`: `SkillStore.load(ctx)` scans `filesDir/skills/*/skill.json`, validates (`id` slug, ≤64 chars; `tools` ⊆ `ToolSpecs.load(ctx)` names; `when` ≤12 cues; `maxExtraTokens` ≤800), returns `List<Skill>`. Invalid skills are skipped with a reason, never crash.
2. Match on the same scoring shape as `ToolRouting.route` (`ToolRouter.kt:175-198`) — reuse it rather than inventing a second matcher.
3. Inject at most **two** matched skills into the prompt block built in `PhoneTools.promptBlock` (`:298`), respecting `maxExtraTokens`. This is the budget guard: prompt length is already the scarce resource.
4. **Tool narrowing is enforced, not advisory.** If a skill is active, `ToolRouting.route` for that turn is intersected with `skill.tools`. This is the safety property that makes skills worth having — a "study tutor" cannot dial a phone.
5. `invalidate()` on `ToolSpecs` cache when the skills dir changes (needed only if a skill ever contributes specs; today it narrows, so this is a no-op safeguard).
6. UI: `Settings → Skills` list (name, tools count, enable toggle, delete, import from file via `ACTION_OPEN_DOCUMENT`). This is a **section inside Settings**, not a new screen — avoids the chrome tax.
7. Import: accept a `.zip` or a single `skill.json` + `prompt.md`, validate before writing, never overwrite an existing id silently.

**Files.** new `ui/Skills.kt` (~250 lines), `ui/SettingsScreen.kt` (section), `ui/PhoneTools.kt` (prompt injection point), `ui/ToolRouter.kt` (intersection).

**Accept.** A skill whose `when` matches adds its guidance and *only* its declared tools are offered; a skill declaring `place_call` but not `compose_message` cannot send SMS; removing the folder takes effect on next message; an invalid manifest is listed with a reason and no tools.

**Risks.** Prompt injection through `prompt.md` — mitigated by tool narrowing and by never letting a skill alter system rules. Document that a skill is trusted content once installed (same trust level as the user typing it).

---

## 7. Wave 3 — MCP client

**Goal.** Import external tool servers into `ToolRouter` without shipping code.

**Scope decision.** Client only. Karen speaks to MCP servers; Karen does not host MCP apps or render third-party UI. This is the one place where the desktop report's pattern is adopted and its sibling is refused — see §13.

**Transport.** JSON-RPC 2.0 over HTTP (streamable), `HttpURLConnection`, `org.json`. No stdio (no subprocesses on Android), no SSE requirement in v1.

**Work.**

1. `ui/McpClient.kt`:
   - `initialize` → `notifications/initialized` → `tools/list`, with a 15 s timeout and a hard 1 MB response cap.
   - `tools/call` with a 30 s timeout; result truncated to ~8 KB before it enters context.
   - Per-server state: `id, name, url, enabled, lastError, toolCount`.
2. Map each MCP tool to a `ToolSpec` with `tier = "T2"` **always**, name `mcp_<server>_<tool>` (sanitized to `[a-z0-9_]`, ≤48 chars). Description from the server, prefixed `[server]`.
3. Merge into `ToolSpecs.load` output via a provider list, and add the missing `invalidate()` so an enable/disable takes effect without restart.
4. **Agent-step visibility decision:** `ToolRouting.catalog` currently exposes only T0/T1 (`ToolRouter.kt:204-206`). MCP tools are T2, so they will not appear in agent catalogs — correct default. Add an explicit per-server "allow in agent steps" toggle that still requires per-call confirmation.
5. Settings → MCP servers: add/edit URL, test connection (shows tool list), enable, remove. Store config in `UserPrefs`, never log URLs with credentials.
6. Failure mode: a dead server must not block a turn — wrap discovery in `withTimeoutOrNull` and degrade to "server unreachable" in the trace row.

**Files.** new `ui/McpClient.kt` (~350 lines), `ui/PhoneTools.kt` (`ToolSpecs` provider + `invalidate`), `ui/SettingsScreen.kt` (section), `ui/UserPrefs.kt` (server list).

**Accept.** A local MCP server's three tools appear after "test connection"; calling one shows its output and a T2 confirm card; disabling removes it from routing immediately; an unreachable server produces one warning and the turn continues.

**Risks.** Untrusted tool output is prompt-injection surface of the highest order. Mitigations: T2 by default, output wrapped in an explicit `<<<external-tool-output>>>` delimiter with a system note that content inside is data, and never auto-chaining MCP output into a T2 phone action in the same turn.

---

## 8. Wave 4 — Quick-ask HUD

**Goal.** Ask Karen without leaving the current app.

**Why cheap.** `SYSTEM_ALERT_WINDOW` is already declared (`AndroidManifest.xml`), and `WakeWordService` is a working foreground-service template.

**Work.**

1. `QuickAskService.kt`: FGS (`foregroundServiceType` unchanged — no microphone unless voice is used) hosting a `ComposeView` overlay; requires `Settings.canDrawOverlays` with an explicit rationale screen first.
2. Overlay content stays minimal: one text field, mic button, last answer, "open in Karen". Do **not** reimplement chat — `onOpenInApp` routes to `Chat` with the prompt prefilled.
3. Entry points: notification action, long-press on the launcher icon shortcut (`ShortcutManager` static shortcut), and an optional accessibility-free gesture. No home-button hijack.
4. Auto-dismiss on outside tap; never persist overlay position across restarts without consent.

**Files.** new `QuickAskService.kt` (~200 lines), `AndroidManifest.xml` (service), `MainActivity.kt` (intent handling), `KarenApp.kt` (prefill handoff).

**Accept.** Overlay opens over a third-party app in <300 ms, answers via the same `CloudChatApi` path, and "open in Karen" lands in Chat with the text. Denying the overlay permission leaves every other entry point working.

---

## 9. Wave 5 — Fork and archive

**Goal.** Explore without destroying, and keep the drawer honest.

**Work.**

1. Index rows gain two trailing columns: `archived` (`0/1`) and `parent` (`id` or empty). Safe by construction — `loadConversations` only requires 3 fields (`:37`).
2. `ChatHistoryStore.fork(ctx, id)`: copy `chat_<id>.txt` to a new id, truncate after message N, write index row with `parent = id`.
3. Drawer: "Archived" collapsible section, long-press → Fork / Archive / Delete.
4. `LocalApiServer` conversation routes must skip archived by default (check the existing handler before wiring).

**Files.** `ui/ChatHistoryStore.kt`, `ui/KarenCommon.kt:2683` (`ChatGPTDrawerContent`), `ui/ChatScreen.kt` (fork action in overflow).

**Accept.** Forking at message 4 yields a new conversation with 4 messages and a visible parent link; archiving removes it from the main list but search still finds it; deleting a parent leaves the fork intact.

---

## 10. Wave 6 — Screen context

**Goal.** "What am I looking at?" answered offline.

**Why last among the big ones.** It needs a new permission, a new FGS type, and a vision path that must not silently fall back to cloud.

**Work.**

1. Manifest: `FOREGROUND_SERVICE_MEDIA_PROJECTION` + service `foregroundServiceType="mediaProjection"`.
2. `ui/ScreenContext.kt`: one-shot `MediaProjection` capture (never a loop), downscale longest edge to 1280 px, JPEG q80, keep in memory, discard after the turn.
3. Consent: `MediaProjectionManager.createScreenCaptureIntent()` every time. **No remembered consent.** A visible indicator during capture is mandatory — if the indicator can't ship, the feature doesn't ship (this is the Android equivalent of refusing silent borderless capture).
4. Routing: capture lands as an attachment on the next user message and flows through the existing vision path (`DocVision`), local model first, cloud only if the user has a key **and** has enabled cloud vision.
5. Entry: attachment sheet row "Screen" + HUD button. Not a background trigger.

**Files.** new `ui/ScreenContext.kt` (~220 lines), `AndroidManifest.xml`, `ui/ChatScreen.kt` (attachment sheet), `QuickAskService.kt` (HUD button).

**Accept.** With airplane mode and a local vision model, a captured settings screen is described correctly; with no local vision model the UI says so instead of silently calling the cloud; capture cancels cleanly from the system dialog.

---

## 11. Wave 7 — Page reader

**Goal.** Open a URL, ask about the page, with no browsing surface to attack.

**Work.**

1. `ui/PageReader.kt`: `WebView` with `javaScriptEnabled = true` (needed for extraction only), `allowFileAccess = false`, `allowContentAccess = false`, `domStorageEnabled = false`, no `addJavascriptInterface`, mixed content never allowed.
2. Navigation is locked: `shouldOverrideUrlLoading` returns true for everything except the initial `http(s)` load; `onCreateWindow` always false. The result is a reader, not a browser.
3. Extract with a single `evaluateJavascript("document.body.innerText")`, cap at ~24 KB, strip boilerplate lines, feed as web context (same slot `WebSearch` already uses).
4. Settings → Privacy: page reader off by default; when on, it states plainly that page content can contain instructions and is treated as data.

**Files.** new `ui/PageReader.kt` (~200 lines), `ui/ChatScreen.kt` (open-in-reader affordance), `ui/SettingsScreen.kt`.

**Accept.** A page with `target=_blank` links cannot open new windows; `file://` and `intent://` are refused; extraction of a long article yields context under the cap and the model answers from it.

---

## 12. Wave 8 — Artifact integrity

**Goal.** A corrupt or tampered model file fails loudly instead of producing nonsense.

**Why.** `WeightStore` already builds a `manifest.json` with a per-artifact `sha256` and size (`WeightStore.kt:84-97`, `sha256()` at `:153`), and `resolve()` (`:141`) hands back a file path — but nothing re-checks those hashes before inference. The manifest exists and is unused as a control. This is the OpenCode lesson inverted: it stages a signed runtime per version and then never re-verifies it; Karen already has the manifest, so verification is cheap.

**Work.**

1. `WeightStore.verify(model): VerifyResult` — recompute SHA-256 for each artifact, compare to manifest, return `{ok, bad: List<String>, missing: List<String>}`. Stream in 1 MB blocks, run on `Dispatchers.IO`.
2. Call it on model load in the inference path and on import completion. On failure: refuse to load, name the bad artifact, offer delete + re-import. Never auto-delete.
3. Surface state in `ModelManagerScreen` — a per-model "verified / unchecked / damaged" badge, with "verify now".
4. Add verify-on-first-load only (not every launch) and cache the result keyed by `(path, size, mtime)` so cold start isn't taxed.
5. Log one line per failure to the wave-0 `diagnostics/store.log`.

**Files.** `ui/WeightStore.kt`, `ui/ModelManagerScreen.kt`, `ui/OrtEngine.kt` or `KarenLlama.kt` (load gate).

**Accept.** Truncating a byte in a model file and reopening the app produces a "damaged" badge and a refused load with a specific artifact name — not a crash, not silent garbage output. A clean model shows "verified" and loads in unchanged time.

**Rollback.** Verification is a gate that can be disabled by pref if it misbehaves on a device.

---

## 13. Deliberately out of scope

Carried forward from the four-way report §12 and the desktop report §7, with reasons:

| Not building | Why |
|---|---|
| MCP **app host** (origin-locked sandbox rendering third-party UI) | Highest attack surface in either desktop app for the least user value here. Wave 3 gets the ecosystem benefit without it |
| Skills that execute code / a plugin runtime | Turns a data format into an execution engine; the declarative version captures most value |
| Theme redesign or theme import in v3 | `AGENTS.md` freezes the theme unless asked. If wanted later it's an opt-in JSON import, never a redesign |
| Realtime WebRTC voice | Needs a backend and ABI-specific native libs; system STT/TTS + wake word is the right offline trade |
| Local feature-flag service | No fleet, no experiments. `UserPrefs` covers it |
| Silent / background screen capture | Non-negotiable: no indicator, no feature |
| Telemetry, crash reporting upload | Offline-first posture is the product |

---

## 14. Sequencing and gates

```
W0 resilience ──► W1 trace ──► W2 skills ──► W3 MCP
                    │
                    └────────► W6 screen context
W4 HUD (independent)      W5 fork (needs W0)      W7 reader      W8 integrity
```

| Gate | Criteria before moving on |
|---|---|
| After W0 | Force-stop test passes; no format change visible to old files |
| After W1 | Old conversations render; trace survives restart |
| After W2 | Skill narrowing proven by test (skill cannot reach undeclared tool) |
| After W3 | Unreachable server degrades; T2 enforced regardless of server claims |
| After W6 | Airplane-mode test passes; indicator observed |
| Before any release | `:app:compileDebugKotlin --offline` clean, drawer + back-handler audit, prompt-token budget re-measured |

Prompt budget is the recurring risk: every wave that touches `PhoneTools.promptBlock` must report the new token cost in the PR description. Current cap discipline (6 routed tools, compact one-line specs) must hold.

---

## 15. Risk register

| Risk | Wave | Mitigation |
|---|---|---|
| Torn writes still occur in `index.txt` rebuild path | 0 | Same `writeAtomic`; rebuild path already exists as fallback |
| Skills become a prompt-injection vector | 2 | Tool narrowing, token cap, "trusted once installed" documented |
| MCP output drives a phone action | 3 | T2 default, delimited output, no same-turn chaining into T2 |
| `ToolSpecs` cache serves stale tools | 2–3 | Add `invalidate()` before wiring dynamic sources |
| Overlay permission denied / OEM quirk | 4 | Every path degrades to notification shortcut |
| MediaProjection FGS killed by OEM battery policy | 6 | One-shot capture, no long-lived session, clear error |
| WebView extraction breaks on SPA pages | 7 | Cap + fallback message; reader is a convenience, not a promise |
| New screen chrome missed | 4,7 | Route + drawer + `KarenHomeBackHandler` in the DoD checklist |

---

## 16. Definition of done (per wave)

1. `.\gradlew.bat :app:compileDebugKotlin --offline` clean.
2. Every new `OutlinedTextField` uses `karenFieldColors(colors)`.
3. Every new screen: `onNavigateToHome` param, `ChatGPTTopAppBar(onBackClick = onNavigateToHome)`, `KarenHomeBackHandler`, route in `KarenApp.kt`, drawer entry.
4. Back behaviour consumes sheets/dialogs/inner levels before Home; never exits the app.
5. No new Gradle dependencies.
6. No secrets in logs; MCP URLs and local-API tokens never printed.
7. Trace data (wave 1) covers any new tool source added by a later wave.
8. Manual device pass on the target phone (Dimensity 9300+, 12 GB) — not just an emulator.

---

## 17. First three days

| Day | Work |
|---|---|
| 1 | W0: `writeAtomic` + quarantine + Settings banner; force-stop test |
| 2 | W1: `TurnTrace` column + populate from the existing loop |
| 3 | W1: expandable renderer + route line; measure prompt cost unchanged |
