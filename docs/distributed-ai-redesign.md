# Distributed AI — Redesign (master-hosted weights + multi-node tasks)

Companion to `docs/distributed-ai-architecture.md` (Phase 1/2, request-level
distribution). This document is the design contract for the next slice:

1. **Workers pull weights from the master device**, not the internet.
2. **Slaves cache weights in the browser** and load them into GPU VRAM per task.
3. **Oversized models are split into chunks and spread across worker nodes**;
   the nodes are coordinated to finish **one** task together without
   collapse or conflict.

Status labels: **WORKING** = in the current code, **PLANNED** = specified here,
not built.

---

## 0. Why the current design cannot do this

| Current fact (code) | Consequence |
|---|---|
| `GET /v1/karen/models` returns metadata only (`LocalApiServer.kt:1692-1746`) | The worker page learns names, never bytes. |
| `GET /v1/files` returns metadata only (`LocalApiServer.kt:1748-1762`) | No way to download a weight file from the phone. |
| Worker imports WebLLM from `https://esm.run/@mlc-ai/web-llm@0.2.86` (`LocalApiServer.kt:3815, 3894`) | Engine JS comes from the CDN, not the phone. |
| `engine.reload(mlcId)` (`LocalApiServer.kt:4146`) | WebLLM downloads MLC weights from upstream (MLC/Hugging Face), caches in the browser. |
| One task = one worker (`WorkerTask.workerId`, `runOnWorker` `LocalApiServer.kt:404-414`) | No model splitting, no multi-node single task. |
| Phone stores GGUF (`ModelDownloader`, llama.cpp) | Browser (WebLLM/MLC) **cannot** load GGUF. |

The redesign keeps the transport (phone = HTTP master, workers = outbound
long-poll pages) and changes **where bytes come from** and **how a task is
sharded**.

---

## 1. Roles (unchanged + one new)

| Role | Runs on | Responsibility |
|---|---|---|
| **Master host** | Karen app (Android) | HTTP server, auth, registry, task queue, relay, node tracking. **Now also the weight store.** |
| **Weight store** | Master app storage | Serves model artifacts to workers with HTTP `Range`. Populated by Model Manager downloads / imports. |
| **Console** | Any dashboard page | Submit prompts, pick runner/model, watch stream + metrics, manage workers. |
| **Worker** | WebGPU browser page | Register, long-poll, **pull weights from master → cache in browser → load into VRAM per task**, run its shard, stream tokens. |
| **Shard** (new role, not a process) | A worker's VRAM | A contiguous layer range of the model, assigned by the master's plan. |

```
 phone (master + weight store, :11434)          laptop browsers (workers)
 ┌────────────────────────────────┐   Range GET   ┌──────────────────────────┐
 │ registry  queue  relay          │◄──────────────│ pull /v1/weights/{m}/... │
 │ weight store (manifest + chunks)│  SSE (task)   │ cache in Cache API       │
 │ shard planner  activation relay │──────────────►│ load shard → VRAM         │
 │ /v1/task/plan /v1/task/act      │  POSTs        │ run layers, stream tokens │
 └────────────────────────────────┘               └──────────────────────────┘
        ▲  master relays hidden states between shard workers (pipeline)
        ▼
 console (any dashboard) — one task may now have N shard workers
```

---

## 2. Weight source: master-hosted store

### 2.1 Store layout

Under `ModelDownloader.modelsDir(ctx)` each model gets a directory:

```
models/<model-slug>/
  manifest.json          # artifacts + chunk list (sizes, sha256)
  config.json            # model config (MLC format)
  tokenizer.json         # tokenizer
  weights/
    shard-000.bin        # chunk 0  (e.g. 64 MB)
    shard-001.bin        # chunk 1
    ...
```

`manifest.json` (example):

```json
{
  "model": "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
  "format": "mlc",
  "layers": 28,
  "vramRequiredMB": 1400,
  "artifacts": [
    { "file": "config.json", "size": 1234, "sha256": "…" },
    { "file": "tokenizer.json", "size": 6400000, "sha256": "…" },
    { "file": "weights/shard-000.bin", "size": 67108864, "sha256": "…" },
    { "file": "weights/shard-001.bin", "size": 67108864, "sha256": "…" }
  ]
}
```

### 2.2 Master endpoints (PLANNED)

| Endpoint | Purpose |
|---|---|
| `GET /v1/weights` | List models present in the store (name, format, size, installed, source). |
| `GET /v1/weights/{model}/manifest.json` | Chunk manifest for a model. |
| `GET /v1/weights/{model}/{file}` | Serve bytes. **Must support `Range` → `206 Partial Content`** so workers fetch only missing chunks and resume interrupted downloads. |
| `GET /v1/weights/{model}/peers` | (Later) which workers already cache which chunks — for relay sharing. |

These endpoints are token-authenticated like the rest. They never return
model-list metadata only — they are byte endpoints. Logs never contain the
token.

### 2.3 How the store gets filled

The phone cannot convert GGUF → MLC in reasonable time. Options, in order of
preference:

1. **Catalog carries an MLC bundle** (preferred). Each catalog entry gains an
   optional `mlcUrl` (and `mlcSha256`). Model Manager downloads the MLC bundle
   into the weight store alongside the GGUF it already fetches for llama.cpp.
   One model, two runtimes, both served from the phone.
2. **On-demand fetch + cache.** If a catalog entry has no `mlcUrl`, the master
   fetches the MLC bundle from upstream **once**, stores it, and serves it
   locally thereafter. First worker still needs internet; the phone becomes
   the cache for everyone after.
3. **GGUF-only models.** If no MLC build exists for a Karen model, the worker
   page marks it "no browser build" (already the behavior today for unmatched
   names) — no silent substitution.

### 2.4 Worker page changes (PLANNED)

- `ensureLlmMod()` still imports WebLLM from the CDN **once** (the JS engine).
  Weights must not come from the CDN.
- Build a custom `appConfig` per model whose `model` URL points at the master:

  ```js
  // masterHost = location.origin (the page is served by the phone)
  var cfg = {
    model:  masterHost + '/v1/weights/' + encodeURIComponent(slug) + '/',
    model_lib: masterHost + '/v1/weights/' + encodeURIComponent(slug) + '/lib/mlc_llm.wasm'
  };
  engine = new S.llmMod.MLCEngine(cfg);
  await engine.reload(slug); // fetches config+weights from the phone
  ```

- **Cache-first:** WebLLM/MLC already caches fetched artifacts in the browser
  Cache API. After the first master pull, later tasks are cache hits — no
  network. The page reports a new telemetry flag `cached: true|false` so the
  console shows which nodes have which weights.
- **Per-task VRAM load:** `workerEnsureEngine` (already exists,
  `LocalApiServer.kt:4127-4147`) unloads/reloads per assignment. Keep that; the
  only change is the byte source. A node that already holds the shard in VRAM
  answers instantly; otherwise it pulls chunks → cache → VRAM.

---

## 3. Chunked weights for large models

- The manifest splits weights into fixed-size chunks (default 64 MB, tunable).
  Large models (7B+, 20–50 GB) download as many small, resumable requests
  instead of one giant fetch.
- `Range` support means a dropped connection resumes at the last byte; the
  worker only re-fetches the missing tail.
- **Memory bound:** the worker writes chunks into the Cache API/IndexedDB
  rather than holding the whole model in JS heap. Only the active shard is
  uploaded to VRAM.
- **Peer sharing (optional, later):** if two workers are on the same LAN, the
  master can relay chunks worker→worker (`/v1/weights/.../relay`) so a chunk is
  fetched from the phone once. Browsers cannot connect to each other directly,
  so the relay is mandatory if peer sharing is wanted. Default is
  master-first; each worker pulls from the phone.

---

## 4. Multi-node single-task coordination

### 4.1 Goal

One prompt, one answer, produced by **N workers each holding a layer range**
(shard) of one model, coordinated by the master, without two nodes stepping on
each other.

### 4.2 Sharding model

- **Layer sharding (pipeline parallelism).** WebLLM/MLC supports loading a
  model into one GPU; it does not support tensor-parallel attention across
  devices. The feasible split is by **layers**: worker A runs layers 0–13,
  worker B runs layers 14–27. Hidden states pass forward through the chain,
  one token at a time. The last shard produces logits and the token stream.
- The master computes a **plan** from the model's layer count and each
  worker's reported free VRAM (`maxBufferMB` is already collected). Rule:
  give each shard enough VRAM for its layers + KV cache headroom; never
  assign a shard that does not fit; if no plan fits, refuse multi-node and
  fall back to the single fastest node (or the phone).

### 4.3 Protocol (PLANNED)

New endpoints, all token-authenticated:

| Endpoint | Direction | Purpose |
|---|---|---|
| `POST /v1/task/plan` | master (console) | Body `{model, workerIds[]}` → `{planId, shards:[{workerId, fromLayer, toLayer}], leader}`. Master assigns shard roles exclusively. |
| `POST /v1/task/shard/lease` | worker → master | Claim a shard with a **fencing token**; heartbeat extends the lease. Only the lease holder may stream for that shard. |
| `POST /v1/task/activation` | worker → master | Forward a hidden state (binary `ArrayBuffer`) to the next shard worker. Master relays it over that worker's held SSE poll. |
| `POST /v1/task/shard/stream` | worker → master | Per-shard progress (layer range, first-token delta, stats). |
| `POST /v1/task/shard/done` | worker → master | Terminal for a shard. Idempotent: `{taskId, shardId, seq}` dedupes replays. |

The existing `/v1/worker/poll` SSE gains new events:
`event: shard` (assigned shard + plan), `event: activation` (hidden state from
the previous shard), `event: shard_done`.

### 4.4 Task state machine (master)

```
queued → planned → assigned → loading → running → completed
                                        ↘ failed | cancelled
```

- `planned`: master has a shard plan with N workers; each worker has a lease.
- `loading`: workers pull their chunks → cache → VRAM, post progress.
- `running`: leader streams tokens; intermediate shards forward activations.
- Terminal states are single-writer: the first `shard/done` or the stall
  timeout wins; later posts are deduped by `(taskId, shardId, seq)`.

### 4.5 Coordination without collapse or conflict

| Hazard | Guard |
|---|---|
| Two workers think they own a shard | Master assigns shard roles **exclusively** in the plan; lease + fencing token; a worker without a valid lease refuses to stream. |
| Worker dies mid-task | Lease heartbeat (15 s) + stall timeout (120 s, existing `TASK_STALL_MS`). Master reassigns the dead shard to another online worker (which pulls those chunks from the master) **or** degrades to single-node if the model fits one GPU. |
| Duplicate token output | Only the **leader** shard streams to the console. Intermediate shards stream nothing; they forward activations. Single writer, no interleaving. |
| Replayed/duplicate posts | Every stream/done post carries `seq`; master dedupes per `(taskId, shardId, seq)`. |
| Cancel from console | `cancelRequested` propagates to **all** shard workers (not just one); each ends as `cancelled`. |
| Plan computed on stale worker list | Plan is built from the live registry at assignment time; a worker that drops between plan and lease gets a `410`/`404` and the master re-plans. |
| Master restart mid-task | Workers re-register (existing `404` path); tasks older than the stall window fail cleanly and the console can resubmit. |
| Activation relay lag | Master relays hidden states as binary frames (base64 in JSON or raw `application/octet-stream`); per-token latency is dominated by this relay — see §6. |

### 4.6 Worker page changes (PLANNED)

- On `event: shard`: fetch the plan, pull **this shard's** chunks from the
  master (Range, resumable), cache, load into VRAM, then run layers
  `[fromLayer, toLayer]`.
- Each generated token: run local layers, post the hidden state to
  `/v1/task/activation` (`{taskId, toWorkerId, seq, data}`), and if this shard
  is the leader, stream the token.
- The page keeps one shard per worker (existing invariant: one task per
  worker). Multi-node is a property of the **task**, not the worker.

---

## 5. What stays the same

- Phone = master, workers = outbound long-poll pages. No inbound connections
  to browsers, no new ports.
- Request-level distribution for models that fit one GPU (the common case):
  unchanged. Multi-node is opt-in per task when the model is too big.
- Auth, token, `Cache-Control: no-store`, telemetry-on-existing-requests, and
  the failure/recovery table from §7 of the current architecture doc.
- Phone llama.cpp path (GGUF) untouched.

---

## 6. Honest constraints

- **Latency.** Pipeline sharding relays one hidden state per token through
  the master. For a 1–3 GB/s LAN and 28 layers this is viable for
  interactive use; for a slow link it will feel slower than one local GPU.
  Multi-node is a **fit** feature, not a speed feature.
- **Engine.** WebLLM/MLC is the only browser runtime wired in. It cannot do
  GGUF and cannot tensor-parallel across devices. A GGUF-capable wasm
  runtime (llama.cpp) would change §2.3/§4.2 but is a larger swap.
- **Phone bandwidth.** The master is the byte source; a 20 GB model over
  Wi-Fi to N workers is N× the LAN traffic unless peer relay is added (§3).
- **VRAM reporting is a proxy.** `maxBufferMB` is not a precise free-VRAM
  number; the planner must be conservative and workers must fail a shard
  loudly (not OOM the tab) when it does not fit.

---

## 7. Implementation phases

All three phases are now **implemented** and compile into the debug APK
(`:app:assembleDebug`). What is exercised end-to-end vs. what remains an
integration point is marked per phase.

### Phase A — Master-hosted weights — **WORKING**
- `WeightStore` (`app/src/main/java/com/karen/ui/WeightStore.kt`): model dirs,
  `manifest.json`, 64 MB chunking, sha256, safe path resolution.
- `GET /v1/weights`, `GET /v1/weights/{model}/manifest.json`,
  `GET /v1/weights/{model}/{file}` with `Range` → `206 Partial Content`
  (`LocalApiServer.kt`, `writeBinaryRange`).
- Worker page: `workerEnsureEngine` builds a master-hosted `appConfig`
  (`model` + `model_lib` point at the phone), so weight bytes come from the
  master and land in the browser Cache API. `cached: true` rides on
  `POST /v1/worker/register`.

### Phase B — Chunked/resumable — **WORKING**
- `WeightStore.chunkFile` splits a weight file into `weights/shard-NNN.bin`
  (64 MB), `buildManifest` hashes every artifact into the manifest.
- `Range` serving means a dropped download resumes at the last byte; the
  worker only re-fetches the missing tail. Peer-relay sharing is **PLANNED**
  (not built) — default is master-first.

### Phase C — Multi-node single task — **PROTOCOL WORKING, engine step PLANNED**
- `POST /v1/task/plan` (master computes a layer-shard plan from each
  worker's reported VRAM), `POST /v1/task/sharded` (console/app trigger),
  `POST /v1/task/shard/lease` (exclusive lease + fencing token + chunk
  list), `POST /v1/task/activation` (hidden-state relay),
  `POST /v1/task/shard/stream`, `POST /v1/task/shard/done`.
- Poll delivers `event: shard` and `event: activation`; worker runs
  `runShardTask` (lease → pull chunks → load → run → forward → stream if
  leader → done).
- Conflict avoidance: exclusive shard roles, lease + fencing token, `seq`
  dedupe on every post, single-writer token stream (leader only), terminal
  state machine, cancel to all shards, `sweepShardFailover` reassigns a dead
  shard or fails the task cleanly.
- Console UI: a **Multi-node: on/off** button in the top bar. When on, a
  prompt is sent to `POST /v1/task/sharded` with every online, unpaused slave
  (the selected runner first, capped at 6); the console streams the leader's
  tokens with a `Multi-node · N shards` meta line, and Stop cancels the whole
  plan.
- **Engine integration point (PLANNED):** WebLLM loads the whole model; a
  true layer-sharded build is needed to execute only `[fromLayer, toLayer]`
  and emit a hidden state. Until then the protocol is exercised with the
  full model and empty activations.

| Phase | Scope | Status |
|---|---|---|
| **A** | Weight store, `/v1/weights/*` Range endpoints, worker `appConfig` override, `cached` telemetry flag | **WORKING** |
| **B** | 64 MB chunks, manifest sha256, resume after drop | **WORKING** (peer-relay PLANNED) |
| **C** | Shard plan, lease/fencing, activation relay, leader streaming, failover, dedupe | **PROTOCOL WORKING**; layer-sharded engine PLANNED |

---

## 8. What if WebLLM doesn't work? (alternatives)

WebLLM can fail for several reasons: no WebGPU (insecure origin, no GPU,
adapter consumed), the CDN is unreachable (offline), the model has no MLC
build, or it is too big for one GPU. The fallback ladder, in order:

| If WebLLM fails because… | Alternative | Status |
|---|---|---|
| No worker can run at all | **Phone is the compute node.** The phone already runs GGUF via llama.cpp (`KarenLlama.kt`). Tasks route to the phone — the existing "Auto falls back to the phone" behavior. The local server stays the master; the phone is the guaranteed worker. | WORKING today |
| CDN (`esm.run`) unreachable | **Self-host the engine.** Serve the WebLLM JS module + `mlc_llm.wasm` from the phone's weight store (`/v1/weights/<slug>/lib/…`). Workers still need WebGPU, but no internet at all. | PLANNED (trivial once §2 lands) |
| No WebGPU / adapter consumed | **llama.cpp WASM in the browser.** Swap the worker runtime to a GGUF-capable wasm engine. The phone serves GGUF chunks with `Range` (already in §2–3); the browser runs GGUF on CPU/wasm. Slower, but no conversion, no MLC, no WebGPU. | PLANNED (engine swap) |
| Model has no MLC build | **Run it on the phone** (llama.cpp) or mark "no browser build" (current behavior). No silent substitution. | WORKING today |
| Model too big for one GPU | **Multi-node sharding** (§4). If WebLLM cannot run it on one device, shard layers across workers. | PLANNED (Phase C) |
| Everything local fails | **Cloud fallback** via the existing `CloudChatApi` providers. | WORKING today |

### Design consequence: pluggable worker runtime

The worker should declare a **backend** at registration, not assume WebLLM:

- `backend: "webllm" | "llamawasm"` (today only `webllm` exists).
- The master's resolver (`resolveWorker`) only picks a worker whose backend
  can run the requested model, exactly as it already filters by model list.
- The weight store serves **both** formats: GGUF chunks for `llamawasm`,
  MLC artifacts for `webllm`. The manifest's `format` field already allows this.
- The phone is always the baseline; browser workers are an **optional
  accelerator**, never the only path.

With this, "WebLLM doesn't work" degrades to "use the phone" or "use the
wasm engine" — never to "no inference".
