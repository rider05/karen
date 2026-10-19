# Distributed AI — Architecture (Phase 1)

Companion to `docs/distributed-ai-audit.md` and the plan
(`docs/Distrubuting_AI_Infrennces.md`). This document is the design contract for
the Phase 2 vertical slice: **one mobile master + one (or more) laptop GPU
workers**, request-level distribution only.

Status labels used here follow the plan: **WORKING** = implemented and exercised,
**EXPERIMENTAL** = implemented but may change, **PLANNED** = not built.

---

## 1. Roles

| Role | Runs on | Responsibility |
|---|---|---|
| **Master host** | Karen app (Android) | HTTP server, auth, worker registry, task queue, relay, node tracking. Always on while the app runs. |
| **Console** | Any dashboard page (phone browser or laptop browser) | Submit prompts, pick runner/model, watch stream + per-task metrics, manage workers. |
| **Worker** | Dashboard page in *worker mode* (laptop/desktop browser with WebGPU) | Register capabilities, long-poll for tasks, load MLC models, run inference on its GPU, stream tokens back. Other devices join **automatically** once the token is saved; the phone itself never becomes a worker. |

```
 phone (master host, :11434)                 laptop browser (worker)
 ┌────────────────────────────┐  SSE poll   ┌─────────────────────────┐
 │ registry  queue  relay     │◄────────────│ GET /v1/worker/poll     │
 │ /v1/workers /v1/worker/*   │             │ (task event)            │
 │ /v1/chat/completions       │────────────►│ POST progress/stream/   │
 │  └─ worker:"auto"|"<id>"   │  POSTs      │      done  (relay back) │
 └────────────┬───────────────┘             └─────────────────────────┘
              │ SSE (relayed chunks)                  ▲
              ▼                                       │ WebLLM on the
      console (any dashboard)  ─────────────────────── ┘ worker's GPU
```

Browsers cannot accept inbound connections, so **the worker always initiates**:
it long-polls over the existing server. No new ports, no WebSocket server, no
WebRTC signaling.

## 2. Worker lifecycle (EXPERIMENTAL)

1. **Join (automatic by default)** — any *other* device that opens the dashboard
   in a WebGPU browser becomes a worker as soon as the bearer token is saved
   (one-time pairing per browser); the page registers on load
   (`POST /v1/worker/register`) with: name, adapter, GFLOPS (from the shipped
   benchmark, if run), backend `webllm`, supported model ids, default model.
   Server returns a stable `workerId` (persisted in `localStorage`). The manual
   *Work as a slave node* switch is an override — off = leave and stay off
   (`karen_worker=0`), on = rejoin (`=1`); unset = auto. **The phone itself
   never registers as a worker**: loopback and the phone's own LAN address get
   `{ok, master:true}` instead of an id, and the page disables the switch with
   an explanation.
2. **Idle poll** — worker holds `GET /v1/worker/poll` (SSE). Server keeps it open
   up to 5 min (`POLL_HOLD_MS`), sending `: ping` every 30 s; each open counts as
   a heartbeat. On close the worker reconnects immediately (≤ few seconds).
   Two guards keep a broken loop from hammering the phone: an invalid token
   (`401`) stops polling outright (switch off, message shown; saving a valid
   token rejoins), and a stale id (`404`) re-registers once — if that
   re-registration fails, polling stops instead of looping.
3. **Assignment** — a queued task is written as `event: task` and the poll
   returns; worker state becomes `assigned`.
4. **Load** — the worker resolves the Karen model name to its browser (MLC)
   build (see §5) and ensures it is loaded (`WebLLM reload`), posting `progress`
   events (phase `loading`) that the server relays to the console. First use of
   a model downloads it to the browser cache.
5. **Run/stream** — generation deltas are batched (~40 ms) and posted to
   `/v1/worker/stream`; the server relays each as an OpenAI-shaped SSE chunk.
   Each response carries `{"cancel": bool}`; a `true` makes the worker interrupt
   the engine and end the task as `cancelled`.
6. **Finish** — worker posts `/v1/worker/done` with status + measured stats
   (TTFT, tokens, elapsed, tok/s). Server finalizes the task, stores the metrics
   on the worker, and the worker returns to `idle` and resumes polling.
7. **Leave/remove** — worker untick → `POST /v1/worker/leave`. Master-side
   *Disconnect* → `POST /v1/workers/remove`: the worker gets `410` on its next
   call, stops polling and turns its join toggle off (no silent re-join).
   Master-side *Pause* → `POST /v1/workers/pause`: the node keeps its
   connection (stays listed and online) but takes no new tasks until resumed;
   a task already running is not interrupted.

**States** (worker): `idle → assigned → loading → running → idle`; computed
`offline` when no call for > 90 s. **Task**: `queued → assigned → loading →
running → completed | failed | cancelled`.

## 3. Protocol (all bearer-token authenticated except the two pages)

| Endpoint | Direction | Payload (essentials) |
|---|---|---|
| `GET /v1/workers` | console/master | `{object:"list", data:[{id,name,ip,gpu,gflops,state,loadedModel,defaultModel,models[],lastSeenAt,tasksDone,lastTps,lastTtftMs, cpuCores,cpuArch,memGB,pressure,quotaGB,usageGB,netType,downlinkMbps,netRttMs,jsScore,maxBufferMB,telemetryAt,paused}]}`; the console's 10 s refresh carries its own readings as `?tel=<url-encoded json>` so the dashboard device's node entry stays fresh with zero extra requests |
| `GET /v1/karen/models` | worker page → master | `{object:"list", data:[{name, file, size, quant, ram, paramsB, reasoning, embedding, installed, source:"catalog"\|"file"}]}` — Karen's model universe for the node dropdown: weights installed on the phone first, then the downloadable catalog (details in §5) |
| `POST /v1/worker/register` | worker → master | `{workerId?, name, gpu, gflops, backend:"webllm", engine:"web-llm@0.2.86", defaultModel, models[], loadedModel, telemetry{c,a,m,p,sq,su,nt,nd,nr,js,ab}}` → `{ok, workerId, pollSeconds, taskTimeoutSeconds}`; **master device → `{ok, master:true}`** (no id; see §2.1). `defaultModel`/`models[]` are Karen model names the node can actually run — see §5 |
| `GET /v1/worker/poll` | worker → master | header `X-Karen-Worker: <id>`, query `?tel=<url-encoded telemetry json>` (fresh readings every cycle); SSE: `event: task` + `data: {id, model, messages[], temperature, max_tokens}`; `: ping` keepalives; `event: idle` end-of-hold |
| `POST /v1/worker/progress` | worker → master | `{workerId, taskId, phase:"loading"\|"running", progress?, text?, loadedModel?}` → `{ok, cancel}` |
| `POST /v1/worker/stream` | worker → master | `{workerId, taskId, delta}` → `{ok, cancel}` |
| `POST /v1/worker/done` | worker → master | `{workerId, taskId, status, error?, stats:{ttftMs, tokens, elapsedMs, tps}}` → `{ok, stale?}` |
| `POST /v1/worker/leave` | worker → master | `{workerId}` |
| `POST /v1/nodes/hello` | any page → master | `{name, webgpu, gpu, gflops, telemetry{c,a,m,p,sq,su,nt,nd,nr,js,ab}}` — registers the device and its machine readings (feeds the app's Nodes screen) |
| `POST /v1/workers/remove` | console/master | `{id}` — force disconnect (worker gets `410` afterwards) |
| `POST /v1/workers/pause` | console/master | `{id, paused}` — out/in of rotation without a disconnect; `auto` skips paused nodes, explicit routes to one get `503` “node is paused” |
| `POST /v1/chat/completions` | console → master | existing body + optional `"worker":"auto"\|"w_x"` and `"model"` (worker-side model id) |

Console SSE for worker tasks, in order:
`worker.status` (queued/assigned/loading/running) → `chat.completion.chunk`
(deltas, identical shape to phone streaming) → `worker.done` (`stats`) →
`[DONE]`. Failures emit `worker.error` + `[DONE]`. Without `"worker"` the
request behaves exactly as before (phone llama.cpp) — **unchanged**.

## 4. Scheduling (request-level only)

`auto` chooses, among workers online, **not paused**, and with the model in their
supported list: prefer `loadedModel == requested`, then idle over busy, then
GFLOPS desc, then fewest tasks. Tasks for busy workers **queue** (worker picks
them up on its next poll). No work is dispatched to a worker whose list lacks
the model, and a paused node receives nothing until resumed (explicit picks are
refused with a clear 503 instead of silently running elsewhere). There is no
promise that adding workers speeds up a single generation.

## 5. Model strategy (honest boundaries)

- The worker's model dropdown is **Karen's own model universe**, not web-llm's
  built-in catalog. `GET /v1/karen/models` merges the weights installed on the
  phone (file-verified through the scanner, plus names registered in Model
  Manager) with the downloadable catalog (bundled + remote catalog URL); the
  page renders them as "… · installed" / "… · catalog", installed first.
- Each Karen entry is matched to the **MLC build that will actually run** in the
  browser (name-prefix match against the WebLLM package's prebuilt list,
  `q4f16_1` preferred, smallest VRAM as the tie-break). Entries with no browser
  build stay visible but disabled ("no browser build") — no silent substitution.
- Tasks are named with the Karen model (e.g. `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`)
  and the worker resolves that to the MLC id when loading the engine; the
  registry therefore reports Karen-style model names as supported/loaded.
  Phone GGUF files **cannot** be loaded by a browser; weights are fetched by the
  worker from upstream once and cached in the browser.
- If the phone list is unreachable (rare — same origin as the page), the page
  falls back to the raw browser builds and says so in the status line.
- Master may request a model per task; the worker loads/reloads it (progress is
  streamed). The registry reports supported vs loaded models.
- **No phone→worker weight transfer** in this slice (PLANNED, Phase 3).

## 6. Networking & security

- Everything rides the existing single port + bearer token. Two token-free pages:
  `GET /` — the slave-node worker page a device just opens to start working (GPU
  status, join state, task status; aliases `/worker` and `/slave`), and
  `GET /dashboard` — the console dashboard (monitor slaves, pick runner/model,
  test chat; the slave list and the Runner picker are ordered fastest-GPU-first,
  every node has a "Select for chat" action, the pick is remembered per browser,
  and `Auto` routes each prompt to the fastest online GPU). All worker/chat APIs
  require the token (pages paste it once; stored per address in `localStorage`).
- **WebGPU needs a secure context.** Over plain `http://192.168.x.x` browsers
  hide `navigator.gpu` — in Chrome, Edge, Brave and Firefox alike (verified;
  see audit §2.7). `tools/karen-tunnel.ps1` forwards `127.0.0.1:11434` →
  `phone:11434` on the laptop, so the page origin is `http://127.0.0.1:11434`
  (trusted) while the TCP connection still lands on the phone; the script now
  opens the worker page itself. Alternative documented path:
  `chrome://flags/#unsafely-treat-insecure-origin-as-secure`.
  Both the worker page and the dashboard name the real cause on load — the
  insecure origin, WebGPU switched off in the browser, or no GPU adapter —
  and print the tunnel command with the current phone address filled in,
  instead of a silent "WebGPU unavailable". Once the insecure-origin bypass
  is on, the page also offers an *Undo the bypass* button with copy-able steps
  that put the browser back to normal.
- **Which GPU the page gets is the browser's call.** WebGPU has no adapter
  picker; a page can only ask. The worker page requests
  `powerPreference: "high-performance"` first (Chrome/Edge honor it on
  dual-GPU laptops, so an RTX/dGPU is used when available) and falls back to
  the plain request; it also reads the browser's *default* adapter and, when
  the two differ, tells the user their default (what WebLLM chat follows) is
  the slower one and how to fix it — Windows → Settings → System → Display →
  Graphics → the browser → High performance, or NVIDIA Control Panel →
  Manage 3D settings → Program Settings. The adapter line on the page and the
  GFLOPS benchmark always name/show what was actually picked.
- No new exposure: workers poll outbound; nothing listens in the browser; no
  arbitrary code/file endpoints; logs never contain the token.
- **Request budget per open page** (was: every page asked for the slave list
  every 5 s, forever): a joined worker tab makes exactly one traffic stream —
  the SSE long-poll, reconnected every 5 min (`POLL_HOLD_MS`; tasks are handed
  over the moment they are queued, `: ping` keepalives every 30 s, same TCP
  request). The slave list refresh runs only on the console, only while its tab
  is visible, at a 10 s cadence. One poll loop per tab is enforced
  (`S.w.loop`), so duplicate joins cannot double the polling. Error paths never
  hot-loop: `401` stops the slave switch with a message, `404` re-registers
  once and stops if that fails. Steady state is therefore **one request every
  five minutes per joined node** — the phone's node counter ticking up that
  rarely is the reconnect, not background chatter. Keepalives are what remains
  between reconnects: 8-byte `: ping` lines inside the already-open request.
- **In-app routing** — when a node is joined, Chat and Workspace show a
  "Run on" chip (This phone / Auto — fastest online node / each node).
  Selecting a node routes whole turns through `LocalApiServer.runOnWorker`
  (system prompt + history pairs → task queue → status/delta events → final
  text) and the reply bubble footer names the node; Auto falls back to the
  phone when nothing is online, an explicit pick falls back with a toast.
  The route is stored in prefs (`worker_target`) and applies to chat, scaffold
  builds, agent tasks and workspace chat alike.

## 7. Failure & recovery

| Failure | Behavior |
|---|---|
| Worker tab closed mid-task | No post for 120 s → task `failed` (“worker went quiet”), console unblocked; worker shows `offline` after 90 s |
| Console disconnects | Next worker post gets `cancel:true` → engine interrupted → task `cancelled` |
| Worker removed by master | Worker receives `410`, stops polling, join toggle off (`karen_worker=0`), toast shown |
| Master device opens its own dashboard | `/v1/nodes/hello` and `/v1/worker/register` answer `{ok, master:true}`; page disables the slave switch — the master never becomes a worker |
| Server restarted | Worker poll gets `404` → re-registers automatically once (same id); if the token was also rejected, polling stops with a message instead of looping — saving the token rejoins |
| Node paused from the phone/console | Stays connected and listed (`paused` state), takes no new prompts; `auto` routes elsewhere, explicit picks get `503` “node is paused”; a running task finishes untouched and Resume puts it back in rotation |
| Model load exceeds RAM | WebLLM throws → task `failed` with the real error text |
| Task queued, nobody picks up | 120 s with no worker activity → `failed` (“no worker picked up the task”) |

Limits: one task per worker at a time; a suspended background tab stops
responding (documented); phone RAM is untouched by worker tasks.

## 8. Metrics (real, measured only)

- Worker GFLOPS: the shipped WGSL matmul benchmark (user-triggered).
- Per task: TTFT, tokens, elapsed, tok/s — counted by the worker and stored on
  the registry entry (`lastTps`, `lastTtftMs`, `tasksDone`).
- **Node telemetry** (cpu / ram / disk / network / heat), shown on the console
  node rows and the app's Nodes screen:
  - Browser workers report only what the platform exposes: CPU cores, arch and
    a fixed-loop CPU score; `deviceMemory` (spec-capped at 8 GB); the storage
    estimate (`quota` ≈ free disk, `usage`); Network Information
    (`effectiveType`, `downlink`, `rtt`); the WebGPU adapter's `maxBufferSize`
    (a VRAM-tier proxy).
  - **Heat:** no browser has a temperature API. A slave node shows the Compute
    Pressure API state (`nominal/fair/serious/critical`, Chrome/Edge 125+), the
    closest real load/thermal signal; the phone's own card shows the actual
    battery °C plus `THERMAL_STATUS_*` (Android), its real RAM/storage and the
    active link type with downstream/upstream bandwidth.
  - Telemetry rides requests that were happening anyway — poll query string,
    register/hello bodies, the console's list refresh — so it adds **zero**
    additional traffic, consistent with §6's request budget.
- Nothing is estimated or back-filled; missing metrics show as blank.

## 9. Testing strategy

1. Kotlin compiles (`:app:compileDebugKotlin`, `:app:assembleDebug`) — no unit
   tests exist in this repo (audit §3).
2. **Protocol QA**: the dashboard worker/console code is exercised against a
   local mock that implements the master protocol (register → task → relay →
   cancel → 410), driven through a real browser with real WebGPU.
   **Ran 2026-10-18**: join/register → task → model load → streamed deltas →
   done(stats); cancel mid-stream → done(cancelled); Disconnect → 410 → worker
   leaves and stays off across reloads; runner picker + workers panel + metrics
   line; `tools/karen-tunnel.ps1` verified (concurrent requests through one
   forwarder, secure context, WebGPU available). Found + fixed during QA: JSON
   API responses now send `Cache-Control: no-store` — without it a browser could
   replay a cached 410 on `/v1/worker/poll` after the worker re-registered.
   **Slave-node-default round (same day)**: fresh browser with WebGPU → no token
   = no join, guidance status; token saved = pairs and auto-joins with zero
   clicks; reload = auto-rejoin (~2 s); switch off = leave, persists off across
   reloads (no register call after reload); switch on = rejoins; master
   simulation (`hello`/`register` return `master:true`) = switch disabled,
   explanation shown, no register call even when previously joined. No console
   errors in any run.
   **Real-app guard pass (same day, emulator build, PC connection via
   `adb forward` arrives as loopback)**: the actual Kotlin server answered
   `/v1/nodes/hello` and `/v1/worker/register` with `{ok, master:true}`, the
   served dashboard disabled the switch and showed the master explanation, the
   phone stayed at 0 slaves / 0 devices while its requests counter increased.
   The mock QA and this real-server pass together cover the guard end to end;
   the Wi-Fi laptop-worker path remains the open checklist below.
   **Karen-model-list + poll-header round (same day, emulator build, real
   browser with WebGPU arriving as a non-loopback peer via the emulator NAT)**:
   the worker dropdown now lists Karen's model universe (`/v1/karen/models` —
   installed weights first, then the downloadable catalog), 8 entries rendered
   with installed/catalog tags and "no browser build" disabled where web-llm
   has no matching build; join registered `defaultModel`/`models[]` as Karen
   file names (5 runnable). App chat routed to the node: pickup landed on the
   held poll immediately, the Karen name resolved to
   `Qwen2.5-0.5B-Instruct-q4f16_1-MLC` (277 MB fetched into the browser cache,
   "Finish loading on WebGPU"), and the streamed reply rendered in the app with
   the node footer. First attempt hit a CDN tunnel error and the app surfaced
   `Failed to fetch (node route: node Windows ⌘ Chrome)` — no silent fallback.
   Idle afterward: same worker id, `lastSeenAt` +60 s over 75 s of idling (30 s
   heartbeats inside the 5-minute poll hold), zero re-registers. Found + fixed
   during the round: the worker page never sent `X-Karen-Worker` on the poll,
   so every poll 404'd into the re-register path — the loudest source of idle
   node chatter; HTML pages now send `Cache-Control: no-store` (a browser kept
   serving a pre-update worker page after the app was updated).
   **Gotcha found on real hardware (reported): "Run GPU benchmark" failed with
   `requestDevice … adapter is "consumed"`.** Root cause: a `GPUAdapter` can
   create exactly ONE device, ever, and the benchmark reused the page's cached
   adapter on every click (and that same adapter is what WebLLM chat also gets
   handed on some Chrome builds). Fix in the page: the benchmark now creates its
   device once and reuses it (`S.device`, dropped automatically if the GPU is
   lost), requests a *fresh* adapter when it has none (high-performance first,
   then default), and if every adapter the tab is offered is already consumed it
   says so plainly and tells the user to reload rather than showing the Dawn
   error. Same plain-language mapping now covers the GPU-boost and worker-task
   error paths. Verified live (same emulator/NAT browser): benchmark twice in a
   row — second run reuses the device, no error (61.9 → 57.4 GFLOPS, adapter
   named in the result line); benchmark again *after* the node's WebLLM engine
   had already loaded a model on the GPU — 108.8 GFLOPS, still clean; `gflops`
   reached the phone (`/v1/workers`). Node then served a task end to end
   (pickup → WebGPU model load → 37 streamed tokens → stats footer).
3. **On-device acceptance checklist** (manual, first run on user's hardware):
   join from a laptop; run a prompt from the phone console; watch tokens; Stop;
   close the tab mid-task and confirm the task fails cleanly; rejoin.
   Kotlin endpoints ship compile-verified; this checklist is the real-device
   proof the plan's Phase 2 exit criterion demands — do not claim it before it
   runs.

## 10. Mapping to the plan's acceptance criteria

- Mobile master + laptop worker, same Wi-Fi: **this slice** (manual checklist pending).
- Worker reports capabilities: **this slice** (`/v1/workers`).
- Qwen model loads through the selected runtime: WebLLM builds from Karen's model list **this slice**.
- Prompt → streamed output: **this slice** (SSE relay, same shape as phone path).
- Transfer/cache documented: §5 (no device transfer; browser cache, documented).
- Metrics real: §8. Disconnects/cancel/errors: §7.
- Existing features preserved: phone chat, cloud chat, boost mode, files panel untouched.
- Distinguish request distribution vs model parallelism: §4 vs plan Phase 7 (PLANNED, not attempted).
