# Distributed Qwen AI --- Implementation Plan

## 1. Goal

Extend the existing application into a browser-based distributed AI
system where:

-   An Android mobile browser acts as the **Master Controller**.
-   A laptop or desktop browser acts as a **GPU Worker**.
-   Open-weight Qwen models provide LLM inference.
-   WebGPU is used when supported by the selected runtime.
-   Devices initially connect over the same Wi-Fi network.
-   The design can later support devices on different networks through a
    secure VPN or relay.

**Core rule:** Inspect and understand the existing repository before
changing code. Preserve working features, reuse existing architecture,
and do not replace the application unnecessarily.

## 2. Architecture Principles

1.  The master is the **control plane**: it coordinates workers, models,
    requests, scheduling, and monitoring.
2.  Workers are the **execution plane**: they load compatible models and
    perform inference.
3.  Model storage, model transfer, local caching, and distributed
    execution are separate capabilities.
4.  WebGPU runs computations on one device; it does not automatically
    combine GPU memory across devices.
5.  Begin with a working mobile-master/laptop-worker system. Treat
    single-model multi-GPU partitioning as a separate advanced phase.
6.  Never label simulated or request-level distribution as true
    model-parallel inference.

## 3. Phase 0 --- Repository Audit (Do Before Coding)

### Inspect

-   Project tree, README, `package.json`, lockfiles, build scripts,
    environment configuration, and existing plans.
-   Frontend framework, routing, state management, component
    conventions, and design system.
-   Backend services, API routes, authentication, and streaming
    responses.
-   Existing model-loading, tokenization, inference, and model-storage
    code.
-   Existing WebGPU, Web Workers, WebSocket, WebRTC, HTTP, or
    peer-to-peer features.
-   Tests, linting, type checks, and current build status.

### Trace the actual inference lifecycle

Answer with code evidence and file paths: 1. Where does a prompt enter
the system? 2. Where does inference execute? 3. Which model families and
formats are supported? 4. Where are model files stored? 5. How are
tokens streamed to the UI? 6. Which transport and session mechanisms
already exist? 7. What prevents adding workers or distributing
computation? 8. Which components can be reused?

### Baseline

-   Run available build, lint, type-check, and test commands.
-   Record existing failures separately from new failures.
-   Do not claim commands passed unless they were run.

### Deliverable

Create `docs/distributed-ai-audit.md` containing the existing
architecture, inference path, important files, reusable components,
constraints, baseline results, and proposed changes.

**Exit criterion:** The current system is understood well enough to make
evidence-based changes. Do not edit application code before completing
this audit.

## 4. Phase 1 --- Architecture and Detailed Plan

Create `docs/distributed-ai-architecture.md` and maintain this
`plan.md`.

Document: - Current and target architecture. - Master and worker
responsibilities. - Worker lifecycle and capability negotiation. -
Message schemas and request/task IDs. - Model manifest and storage
strategy. - Communication and security design. - Scheduling policies. -
Failure and recovery behavior. - Test strategy and acceptance
criteria. - Known browser/runtime limitations.

Prefer the smallest coherent change set that fits the existing project.
Do not add multiple competing frameworks or transports without a
demonstrated need.

## 5. Phase 2 --- One Mobile Master + One Laptop Worker

### Master

Build or extend a responsive mobile dashboard that can: - Pair with a
worker. - Show connection and readiness state. - Show the worker's
supported backend and model status. - Submit a prompt. - Stream
generated text. - Cancel an active request. - Show useful errors and
basic performance metrics.

### Worker

Implement a worker lifecycle: 1. Initialize the inference runtime. 2.
Detect supported backends and features. 3. Register with the master. 4.
Report capabilities and readiness. 5. Load or download the required
model. 6. Accept compatible inference tasks. 7. Stream output and report
completion/failure. 8. Release resources on shutdown.

### Local-network communication

Evaluate the existing stack first. Use an appropriate combination of: -
WebSocket or HTTP for APIs and coordination. - WebRTC DataChannels for
peer-to-peer transfer when useful. - A signaling mechanism if WebRTC is
selected.

Do not assume same Wi-Fi guarantees browser-to-browser connectivity.
Account for HTTPS, browser permissions, firewall rules, network
isolation, and signaling.

### Minimum vertical slice

A worker connects, reports capabilities, receives one prompt, runs
inference with a compatible Qwen model, and streams the response to the
mobile UI.

**Exit criterion:** End-to-end inference works on real devices, not only
mocks.

## 6. Phase 3 --- Model Registry, Transfer, and Caching

### Model metadata

Track: - Model ID and family. - Exact source repository and revision. -
Architecture and tokenizer requirements. - Format and quantization. -
File manifest, sizes, and checksums. - License and runtime
compatibility. - Required features and context limitations.

### Storage

-   The mobile may keep an authoritative model copy.
-   Workers should cache model files locally where possible.
-   Use IndexedDB or OPFS only where appropriate and supported.
-   Check storage quota and available space before transfer.
-   Support resumable transfer when practical.
-   Verify checksums before loading.
-   Report download, validation, and loading progress.

Do not assume a GGUF model can be loaded by every browser runtime.
Confirm the model architecture, format, tokenizer, and quantization are
supported by the selected engine.

Do not transfer the entire model for every prompt. Reuse local cached
files and loaded models when possible.

**Exit criterion:** A compatible model can be made available to a
worker, integrity-checked, loaded, cached, and reused.

## 7. Phase 4 --- Worker Management and Reliability

Implement: - Unique worker IDs and authenticated pairing. - Heartbeats
and connection state. - Worker readiness and capability reporting. -
Timeouts and cancellation. - Reconnection and clean disconnection. -
Explicit task states: `queued`, `assigned`, `loading`, `running`,
`streaming`, `completed`, `failed`, and `cancelled`. - Safe retry
behavior for failed tasks. - Backpressure for streaming and large
transfers. - Handling for tab closure, browser suspension, WebGPU device
loss, and memory exhaustion.

Do not promise uninterrupted background operation from a mobile browser
tab. Document browser lifecycle limitations and use a persistent service
where necessary.

**Exit criterion:** Disconnects and resource failures produce
recoverable, understandable states instead of stuck tasks.

## 8. Phase 5 --- Request-Level Scheduling

Start with request-level scheduling because it is simpler and easier to
validate.

The scheduler should consider: - Model and runtime compatibility. -
Worker readiness. - Cached model availability. - Recent measured
throughput. - Recent task latency. - Network latency. - Current
workload. - Estimated memory requirements. - User-selected objective.

Support distinct objectives: - Minimize time to first token. - Maximize
generation speed for a single request. - Maximize concurrent request
throughput. - Prefer a particular device or worker.

Do not dispatch tasks to incompatible workers. Do not assume adding
workers improves the latency of one generation request.

**Exit criterion:** Multiple workers can process independent requests,
and scheduling decisions are observable and testable.

## 9. Phase 6 --- Benchmarking

Create repeatable benchmarks using the same model revision,
quantization, prompt, context settings, and generation settings wherever
possible.

Measure: - Model download/transfer time. - Model load time. - Time to
first token. - Tokens per second. - Total response time. - Network
transfer overhead. - Memory pressure where observable. - Worker
utilization where measurable. - Error and reconnection rates.

Compare: 1. Laptop-only inference. 2. Mobile-only inference if
supported. 3. Mobile master with one laptop worker. 4. Multiple workers
serving independent requests. 5. Single-model distributed inference only
if genuinely implemented.

Label estimates separately from measured values. Record hardware,
browser, runtime, model revision, and test conditions.

**Exit criterion:** The application and documentation show whether a
strategy actually improves the target performance metric.

## 10. Phase 7 --- Experimental Single-Model Distributed Inference

Investigate the selected runtime's actual support for: - Layer-wise
partitioning. - Tensor or pipeline parallelism. - Transferring
intermediate activations. - Synchronization between workers. - KV-cache
ownership and management. - Memory allocation and model-shard
placement. - Cancellation and worker-failure handling.

A model-parallel request may need to transfer activations at every
generation step. Network overhead may outweigh additional compute,
especially on Wi-Fi or heterogeneous devices.

If the browser runtime cannot support the required operations, document
the limitation and propose a compatible backend rather than faking
distribution. A native or server-side worker may be necessary.

**Exit criterion:** Either a real, benchmarked partitioned inference
path exists, or the limitation and a feasible alternative are
documented.

## 11. Phase 8 --- Secure Remote Networking

Prepare for different networks through a private VPN or a properly
secured relay.

Requirements: - Authenticated pairing and sessions. - Encrypted
transport. - Per-session authorization. - Revocation and worker
removal. - Strict message and file validation. - Model checksum
verification. - Resource limits and cancellation. - No arbitrary remote
code execution. - No unrestricted remote file or shell access. - No
unauthenticated public RPC endpoints. - No secrets in frontend bundles
or logs.

Do not expose development ports directly to the public internet.

**Exit criterion:** Remote connectivity is authenticated, documented,
and tested without weakening browser or network security.

## 12. UI Requirements

Extend the existing UI and design system rather than replacing them.

### Master dashboard

Show: - Connected workers and readiness. - Available models. - Active
tasks. - Scheduler mode. - Generation speed and time to first token. -
Transfer/loading progress. - Recent errors and logs.

### Worker details

Show: - Device name and connection state. - Supported backend. - Loaded
model and revision. - Cache state. - Measured or estimated resource
metrics. - Current workload and throughput. - Disconnect/reconnect
controls.

### Model manager

Show: - Model compatibility and license. - Storage requirements. -
Download/transfer progress. - Integrity verification. - Cache and
loading status.

### Chat

Preserve current functionality. Add streaming, cancellation, selected
worker/scheduling mode, and useful metrics without cluttering the UI.

## 13. Testing

Add unit and integration tests for: - Worker registration, pairing, and
removal. - Capability negotiation. - Model manifest validation and
checksums. - Transfer interruption and resume. - Scheduler selection. -
Task state transitions. - Streaming and cancellation. - Worker
disconnection and recovery. - Invalid messages and unsupported
features. - Storage failures and runtime errors.

Use mocks for deterministic unit tests, then test real
browser-to-browser behavior where supported. Re-run the existing
test/build suite after each major phase and compare against the
baseline.

## 14. Agent Operating Rules

-   Audit before editing.
-   Use evidence from the repository; do not guess its architecture.
-   Reuse existing infrastructure and conventions.
-   Preserve current features and APIs unless a change is necessary.
-   Keep changes incremental, typed, documented, and testable.
-   Explain major dependency or architecture choices.
-   Do not fabricate benchmark values or test results.
-   Do not claim WebGPU automatically combines GPUs.
-   Do not call request-level distribution model parallelism.
-   Do not assume a runtime supports every Qwen checkpoint or file
    format.
-   Clearly separate working, experimental, and planned capabilities.
-   If blocked, document the exact blocker and implement the closest
    truthful, useful alternative.
-   Update this plan as tasks are completed.

## 15. Acceptance Criteria

The initial release is done when: - The existing project builds, or
baseline failures are documented. - A mobile master connects to a laptop
worker on the same Wi-Fi. - The worker reports its supported
capabilities accurately. - A compatible Qwen model can be loaded through
the selected runtime. - A prompt submitted on the mobile produces
streamed output. - Model transfer/cache behavior is documented and
validated. - Performance metrics are real and clearly labeled. -
Disconnects, cancellation, and errors are handled. - Existing
application features remain functional. - Relevant tests pass. -
Documentation clearly distinguishes working request distribution from
experimental model-level GPU distribution.

## 16. Immediate Next Actions

1.  Inspect the repository and run baseline checks.
2.  Create `docs/distributed-ai-audit.md`.
3.  Trace and document the current inference path.
4.  Create `docs/distributed-ai-architecture.md`.
5.  Update this plan with real file paths, dependencies, and task owners
    if applicable.
6.  Implement the one-master/one-worker vertical slice.
7.  Validate it on actual devices before adding multi-worker scheduling.
8.  Benchmark before attempting model-level partitioning.

## 17. Build status (append-only log)

- Phase 0 (repository audit): **DONE** — `docs/distributed-ai-audit.md` (evidence at commit `999d71f`; no app code was edited before it).
- Phase 1 (architecture + protocol contract): **DONE** — `docs/distributed-ai-architecture.md`.
- Phase 2 (one mobile master + one laptop GPU worker): **IN PROGRESS** — worker registry + task protocol + SSE relay on the phone server; worker mode + runner selector on the dashboard. Exit criterion (real-device run) stays open until the manual checklist in the architecture doc is executed on real hardware.
- Phases 3–8: not started. Documented boundaries: no phone?worker weight transfer, no model-parallel claims, request-level scheduling only, remote networking untouched.


- Phase 2 implementation + QA update (2026-10-18): server routes, dashboard worker mode + runner picker, worker line in Nodes, and tools/karen-tunnel.ps1 are built and compile clean. Browser protocol QA against a local mock master PASSED (join -> task -> real-WebGPU streamed deltas -> done with measured stats; cancel mid-stream -> done(cancelled); master Disconnect -> 410 -> worker leaves and stays off across reloads; tunnel verified end-to-end). Found + fixed during QA: JSON API responses now send Cache-Control: no-store (a browser could otherwise replay a cached 410 on /v1/worker/poll). The real-device run (phone master + laptop worker, one Wi-Fi) remains the open exit criterion - manual checklist in docs/distributed-ai-architecture.md section 9.

- Phase 2 slave-node default (2026-10-18): other devices now work as slave (worker) nodes automatically - opening the dashboard in a WebGPU browser and saving the token once is enough; the page auto-joins on load, rejoins on reload with zero clicks, and an explicit switch-off persists (karen_worker=0). The phone itself is refused worker registration (loopback and its own LAN IP get {ok, master:true}) and the dashboard disables the switch with an explanation. Phone Nodes screen relabelled: SLAVE NODES section, Slaves metric, updated copy. Verified against the mock master in a real browser: token gate, auto-join, auto-rejoin, opt-out persistence, manual re-enable, master refusal (no register call), no console errors. Docs updated: distributed-ai-architecture sections 1, 2.1, 3, 7, 9. Real-device run is still the open exit criterion.
