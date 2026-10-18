# Distributed AI — Phase 0 Repository Audit

Audit for `docs/Distrubuting_AI_Infrennces.md` (the plan). Evidence gathered from the
repository at commit `999d71f`. Line numbers refer to that commit and may drift.

Method: read the actual source files listed below. Nothing here is guessed; every
claim names a file. No application code was changed to produce this audit.

---

## 1. System overview

Karen is an **Android app** (Kotlin + Jetpack Compose, `com.karen`), not a web
project. There is no `package.json`, no Node toolchain, no frontend framework.
The "browser" parts are HTML/JS strings served by the app itself.

| Concern | Where it lives |
|---|---|
| Phone chat UI (local + cloud) | `app/src/main/java/com/karen/ui/ChatScreen.kt` |
| Phone local inference (GGUF) | `app/src/main/java/com/karen/ui/KarenLlama.kt` (llama.cpp JNI, arm64) |
| Cloud API chat (streamed) | `app/src/main/java/com/karen/ui/CloudChatApi.kt` |
| Local HTTP server + dashboard (master host) | `app/src/main/java/com/karen/ui/LocalApiServer.kt` (≈1530 lines) |
| Browser dashboard page (HTML/JS) | inline string in `LocalApiServer.dashboardPage()` (line 965) |
| On-disk model scan + probe | `LocalApiServer.scanModelFiles()` (159), `ggufMagicOk()` (252), `OrtEngine.describe()` |
| Model download locations | `app/src/main/java/com/karen/ui/ModelDownloader.kt` (`modelsDir`, `weightFileFor`) |
| Node registry (devices seen) | `NodeInfo` (29), `recordNode()` (98), `registerNodeCapabilities()` (672) |
| Nodes UI in the app | `app/src/main/java/com/karen/ui/NodesScreen.kt` |
| Server settings + token + diagnostics | `app/src/main/java/com/karen/ui/SettingsScreen.kt`, `UserPrefs.kt` |

Build: `.\gradlew.bat :app:compileDebugKotlin --offline` (see `AGENTS.md`).
No new dependencies are allowed without a documented reason (`AGENTS.md`);
the server is hand-rolled `ServerSocket` HTTP/1.1 + `org.json` only.

## 2. Inference lifecycle — trace with evidence

**1. Where does a prompt enter?**
- App: `ChatScreen.kt` composer → local path (KarenLlama) or cloud path (`CloudChatApi`).
- Network: `POST /v1/chat/completions` route in `LocalApiServer.handle()` (line 513);
  `stream:true` → `chatCompletionSse()` (756), else `chatCompletion()` (544).
- Dashboard: `send()` in the dashboard JS (inside `dashboardPage()`).

**2. Where does inference execute?**
- Phone GGUF: `KarenLlama.complete()` (57) / `completeStream()` (68) → JNI
  `nativeCompleteStream()` (110), llama.cpp, arm64 only.
- Cloud: remote providers via `CloudChatApi.completeStreamingResult()`.
- Laptop GPU (already shipped): the dashboard's WebLLM *boost* path
  (`initEngine()` / `boostSend()` in the dashboard JS) runs MLC models on the
  browser's GPU via `https://esm.run/@mlc-ai/web-llm@0.2.86`. This executes on
  the page that toggled it; it is not addressable by other devices.

**3. Which model families/formats?**
- GGUF: anything llama.cpp loads; scanned from disk, magic-checked (`ggufMagicOk`, 252).
- MLC (WebLLM): 165 entries advertised by web-llm 0.2.86, incl. Qwen2.5 0.5B–32B.
- ONNX: probe-only (`OrtEngine.describe`), no chat pipeline.
- TFLite/GGML: listed, no runtime (`probeModelFile`, 702).

**4. Where are model files stored?**
- Phone: app external dir `getExternalFilesDir("models")` (`ModelDownloader.modelsDir`)
  plus best-effort scan of public `Download/Downloads/Documents/Models/Karen`
  (`scanModelFiles`, 159; 5 s cache).
- Laptop: WebLLM's weights live in the browser **Cache API**; nothing is written
  to the phone.

**5. How are tokens streamed?**
- Server → browser: SSE, `data: {chunk}` lines + `data: [DONE]`
  (`chatCompletionSse`, 756–848). Client disconnect is detected by write failure,
  which calls `KarenLlama.cancel()` (795).
- Browser dashboard reads the stream with a `fetch` ReadableStream reader and
  paints tokens live.

**6. Which transport/session mechanisms exist?**
- Plain `ServerSocket` HTTP/1.1, one coroutine per connection (`handle`, 369),
  30 s socket read timeout, 512 KB body cap (394).
- Auth: static bearer token per request (430–440); dashboard page is token-free.
- Node tracking keyed by client IP (`recordNode`, 98; reverse DNS 127).
- **None**: WebSocket, WebRTC, sessions, long-poll, IndexedDB/OPFS (grep over
  `app/src/main/java/com/karen` → zero matches).

**7. What prevents adding workers / distributing compute today?**
- Chat routes can only run the phone's llama.cpp (`pickModel`, 614 — installed
  GGUF or scanned file; 503 when nothing is installed).
- Browsers cannot receive inbound connections, so a worker cannot be called
  directly; there is no worker registry, task queue, or task protocol.
- Node GPU data is passive reporting (`/v1/nodes/hello`, 672) — stored and shown,
  never used to route work.
- Boost runs only for the page that toggled it; no cross-device addressing.
- **WebGPU requires a secure context**: on `http://192.168.x.x` the browser does
  not expose `navigator.gpu` at all (Chrome: WebGPU is secure-context-only;
  `http://127.0.0.1` is trusted, LAN IPs are not). Verified today: adapter worked
  on `127.0.0.1`, `about:blank` had no `navigator.gpu`. Standard workarounds:
  a `localhost` port forward, or `chrome://flags/#unsafely-treat-insecure-origin-as-secure`.
  Any GPU-worker design over plain LAN HTTP must ship one of these.

**8. What is reusable?**
- HTTP server, routing, bearer auth, SSE writer, error helper (`writeJson`, 888).
- Node registry (extend for workers), node capabilities reporting, Nodes UI.
- Dashboard chat UI + SSE reader; helper JS (`headers()`, `toast()`, panels).
- WebGPU detection + real benchmark (`initGpu`, `runBench` — WGSL matmul, verified
  84.9 GFLOPS, cell check OK on an Intel iGPU).
- WebLLM engine init/streaming (`initEngine`, `boostSend`) — the execution core
  of a worker.
- `readTurns()` (730) for OpenAI-style message parsing.

## 3. Baseline (run at `999d71f`)

| Check | Command | Result |
|---|---|---|
| Kotlin compile | `.\gradlew.bat :app:compileDebugKotlin --offline` | exit 0 |
| Release/APK build | `.\gradlew.bat :app:assembleDebug --offline` | exit 0 (APK pushed as `999d71f`) |
| Unit/integration tests | — | **no test sources exist** (`app/src/test`, `app/src/androidTest` empty) |

There is nothing to regress test-wise; correctness must be demonstrated by build
plus protocol/QA runs, and stated as such.

## 4. Constraints the plan must respect (with evidence)

1. **Browsers are call-out only.** No inbound sockets; a worker must poll or hold
   an outbound stream to the phone. → poll/long-poll over the existing server.
2. **WebGPU is secure-context only** (see 2.7). LAN IP over plain HTTP = no GPU.
   → ship a loopback tunnel (`tools/karen-tunnel.ps1`) and document the flag.
3. **Formats do not transfer.** Phone holds GGUF (llama.cpp); browser runs MLC.
   A GGUF file cannot be handed to WebLLM. Weight transfer between devices is a
   Phase 3 concern and needs a browser-runnable format; do not fake it.
4. **VRAM is not pooled.** WebGPU is one device per context; "use the laptop's
   GPU" ≠ "combine GPUs". Model-parallel across browsers is Phase 7, documented
   as experimental-only.
5. **A worker tab must stay open.** Background suspension/close kills a running
   task; the master must time tasks out and mark workers offline.
6. **One runtime per worker.** WebLLM holds one loaded model per engine; the
   registry must respect `loadedModel` vs requested model (reload with progress).
7. **No new dependencies** (AGENTS.md). All of the above is buildable with the
   existing socket server + dashboard JS. WebLLM itself is loaded by the
   *browser* from a pinned CDN — client-side, not an app dependency.

## 5. Proposed change set (maps to the plan's phases)

| Plan phase | Change | Reuses |
|---|---|---|
| 2 — one master + one worker | Worker registry + task queue + task protocol on `LocalApiServer`; worker mode in the dashboard (join/leave, poll loop, execute, stream back); runner selector in dashboard chat | server, auth, SSE, node registry, WebLLM engine, chat UI |
| 3 — registry/transfer/caching | Worker reports supported + loaded models; master can request a model per task; worker downloads/caches via WebLLM (progress streamed). **No device-to-device weight transfer in this slice** | WebLLM cache, progress callbacks |
| 4 — reliability | Task states (`queued`…`cancelled`), heartbeats by poll cadence, staleness timeouts, cancel flag to worker, reconnection | node last-seen pattern |
| 5 — request-level scheduling | `worker: "auto" | "<id>"` in chat body; auto = online + idle + model match + measured GFLOPS | benchmark, worker registry |
| 6 — benchmarking | Per-task measured TTFT + tok/s reported by the worker and stored per worker; GFLOPS benchmark already ships | existing metrics plumbing |
| 7 — model-parallel | **Not attempted**; documented limitation (constraint 4) | — |
| 8 — remote networking | Out of scope this slice; token auth retained; no new exposure | bearer auth |

Deliverable docs to be produced next: `docs/distributed-ai-architecture.md` and the
Phase 2 implementation (this audit satisfies the plan's exit criterion: the system
is understood with code evidence, nothing was edited before this audit).
