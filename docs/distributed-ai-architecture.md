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
   up to 40 s, sending `: ping` every 10 s; each open counts as a heartbeat. On
   close the worker reconnects immediately (≤ few seconds).
3. **Assignment** — a queued task is written as `event: task` and the poll
   returns; worker state becomes `assigned`.
4. **Load** — worker ensures the requested model is loaded (`WebLLM reload`),
   posting `progress` events (phase `loading`) that the server relays to the
   console. First use of a model downloads it to the browser cache.
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

**States** (worker): `idle → assigned → loading → running → idle`; computed
`offline` when no call for > 75 s. **Task**: `queued → assigned → loading →
running → completed | failed | cancelled`.

## 3. Protocol (all bearer-token authenticated except the two pages)

| Endpoint | Direction | Payload (essentials) |
|---|---|---|
| `GET /v1/workers` | console/master | `{object:"list", data:[{id,name,ip,gpu,gflops,state,loadedModel,defaultModel,models[],lastSeenAt,tasksDone,lastTps,lastTtftMs}]}` |
| `POST /v1/worker/register` | worker → master | `{workerId?, name, gpu, gflops, backend:"webllm", engine:"web-llm@0.2.86", defaultModel, models[], loadedModel}` → `{ok, workerId, pollSeconds, taskTimeoutSeconds}`; **master device → `{ok, master:true}`** (no id; see §2.1) |
| `GET /v1/worker/poll` | worker → master | header `X-Karen-Worker: <id>`; SSE: `event: task` + `data: {id, model, messages[], temperature, max_tokens}`; `: ping` keepalives; `event: idle` end-of-hold |
| `POST /v1/worker/progress` | worker → master | `{workerId, taskId, phase:"loading"\|"running", progress?, text?, loadedModel?}` → `{ok, cancel}` |
| `POST /v1/worker/stream` | worker → master | `{workerId, taskId, delta}` → `{ok, cancel}` |
| `POST /v1/worker/done` | worker → master | `{workerId, taskId, status, error?, stats:{ttftMs, tokens, elapsedMs, tps}}` → `{ok, stale?}` |
| `POST /v1/worker/leave` | worker → master | `{workerId}` |
| `POST /v1/workers/remove` | console/master | `{id}` — force disconnect (worker gets `410` afterwards) |
| `POST /v1/chat/completions` | console → master | existing body + optional `"worker":"auto"\|"w_x"` and `"model"` (worker-side model id) |

Console SSE for worker tasks, in order:
`worker.status` (queued/assigned/loading/running) → `chat.completion.chunk`
(deltas, identical shape to phone streaming) → `worker.done` (`stats`) →
`[DONE]`. Failures emit `worker.error` + `[DONE]`. Without `"worker"` the
request behaves exactly as before (phone llama.cpp) — **unchanged**.

## 4. Scheduling (request-level only)

`auto` chooses, among workers online and with the model in their supported list:
prefer `loadedModel == requested`, then idle over busy, then GFLOPS desc, then
fewest tasks. Tasks for busy workers **queue** (worker picks them up on its next
poll). No work is dispatched to a worker whose list lacks the model. There is
no promise that adding workers speeds up a single generation.

## 5. Model strategy (honest boundaries)

- Workers run **MLC models via WebLLM** (Qwen2.5 family is the default filter —
  0.5B/1.5B/3B/7B…). Phone GGUF files **cannot** be loaded by a browser; weights
  are fetched by the worker from upstream once and cached in the browser.
- Master may request a model per task; the worker loads/reloads it (progress is
  streamed). The registry reports supported vs loaded models.
- **No phone→worker weight transfer** in this slice (PLANNED, Phase 3).

## 6. Networking & security

- Everything rides the existing single port + bearer token. Two token-free pages:
  `GET /` — the slave-node worker page a device just opens to start working (GPU
  status, join state, task status; aliases `/worker` and `/slave`), and
  `GET /dashboard` — the console dashboard (monitor slaves, pick runner/model,
  test chat). All worker/chat APIs require the token (pages paste it once; stored
  per address in `localStorage`).
- **WebGPU needs a secure context.** Over plain `http://192.168.x.x` browsers
  hide `navigator.gpu` (verified; see audit §2.7). Ship `tools/karen-tunnel.ps1`
  (WORKING once tested): forwards `127.0.0.1:11434` → `phone:11434` on the
  laptop, so the page origin is `http://127.0.0.1:11434` (trusted) while the
  TCP connection still lands on the phone. Alternative documented path:
  `chrome://flags/#unsafely-treat-insecure-origin-as-secure`.
  The dashboard detects this case and shows the exact fix instead of a silent
  "WebGPU unavailable".
- No new exposure: workers poll outbound; nothing listens in the browser; no
  arbitrary code/file endpoints; logs never contain the token.

## 7. Failure & recovery

| Failure | Behavior |
|---|---|
| Worker tab closed mid-task | No post for 120 s → task `failed` (“worker went quiet”), console unblocked; worker shows `offline` after 75 s |
| Console disconnects | Next worker post gets `cancel:true` → engine interrupted → task `cancelled` |
| Worker removed by master | Worker receives `410`, stops polling, join toggle off (`karen_worker=0`), toast shown |
| Master device opens its own dashboard | `/v1/nodes/hello` and `/v1/worker/register` answer `{ok, master:true}`; page disables the slave switch — the master never becomes a worker |
| Server restarted | Worker poll gets `404` → re-registers automatically (same id) |
| Model load exceeds RAM | WebLLM throws → task `failed` with the real error text |
| Task queued, nobody picks up | 120 s with no worker activity → `failed` (“no worker picked up the task”) |

Limits: one task per worker at a time; a suspended background tab stops
responding (documented); phone RAM is untouched by worker tasks.

## 8. Metrics (real, measured only)

- Worker GFLOPS: the shipped WGSL matmul benchmark (user-triggered).
- Per task: TTFT, tokens, elapsed, tok/s — counted by the worker and stored on
  the registry entry (`lastTps`, `lastTtftMs`, `tasksDone`).
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
3. **On-device acceptance checklist** (manual, first run on user's hardware):
   join from a laptop; run a prompt from the phone console; watch tokens; Stop;
   close the tab mid-task and confirm the task fails cleanly; rejoin.
   Kotlin endpoints ship compile-verified; this checklist is the real-device
   proof the plan's Phase 2 exit criterion demands — do not claim it before it
   runs.

## 10. Mapping to the plan's acceptance criteria

- Mobile master + laptop worker, same Wi-Fi: **this slice** (manual checklist pending).
- Worker reports capabilities: **this slice** (`/v1/workers`).
- Qwen model loads through the selected runtime: WebLLM Qwen2.5 list **this slice**.
- Prompt → streamed output: **this slice** (SSE relay, same shape as phone path).
- Transfer/cache documented: §5 (no device transfer; browser cache, documented).
- Metrics real: §8. Disconnects/cancel/errors: §7.
- Existing features preserved: phone chat, cloud chat, boost mode, files panel untouched.
- Distinguish request distribution vs model parallelism: §4 vs plan Phase 7 (PLANNED, not attempted).
