# Karen — Offline Android AI Assistant
## Optimized Project Plan (v2.1)

> Rewrite of the original 91-section plan. Same vision, but re-ordered around **risk reduction**, **measurable targets**, and a **small, shippable V1**. The long feature list is preserved as a prioritized backlog (Section 16) so nothing is lost, but it no longer competes with the core.
>
> **v2.1 addition:** Full on-device performance monitoring (speed, memory, thermal, battery) both for Phase 0 external testing and as a first-class in-app capability (Section 5 + Section 12 + Section 50).

---

## 0. What changed vs. the original plan

| # | Change | Why |
|---|---|---|
| 1 | **Phase 0 added: prove the phone can run the model *before* training anything** | The biggest unknown is speed/RAM/heat on the Dimensity 9300+, not training. Test the stock Qwen3.5 4B GGUF on the phone in week 1. |
| 2 | **Baseline first, fine-tune second** | Run base Qwen + good prompt + grammar-constrained tool calls first. Fine-tune only where measured failures remain. Avoids wasted weeks and catastrophic forgetting. |
| 3 | **Reliability comes from the app harness, not the model** | JSON-schema/grammar-constrained tool calls, validator, risk tiers, confirmations, audit log. A 4B model will sometimes be wrong; the harness makes wrong harmless. |
| 4 | **Fixed the "Jarvis" dataset example** | The original trains the model to say *"Sure. I will open your timetable."* with no tool call. That teaches **claiming actions without doing them**, which the plan itself lists as a safety failure. |
| 5 | **9B training moved off the 6 GB GPU** | 9B at 4-bit needs ~5.5 GB for weights alone; it will not train realistically on 6 GB. Rent a 24 GB GPU for a few hours if you want 9B. |
| 6 | **Measurable exit criteria for every phase** | Checklists replaced by pass/fail gates (tokens/sec, tool accuracy, RAM ceiling). |
| 7 | **Android reality checks** | Background-launch limits, WhatsApp sending limits, accessibility-service policy, low-memory killer, thermal throttling. |
| 8 | **Prompt-injection defense added** | RAG documents, notifications, web pages and clipboard are *untrusted data*; they must never trigger actions on their own. |
| 9 | **Quantization is evaluated, not assumed** | Re-run the tool-call eval on the quantized GGUF; Q4 can quietly hurt structured output. |
| 10 | **Scope cut for V1; backlog grouped and gated** | Sections 31–89 of the original were mostly V3+ ideas. They are now a backlog with entry conditions. |

---

## 1. Goal and success metrics

**Goal:** a private, offline-first assistant on Android that chats, answers from local documents, remembers user-approved facts, and performs phone actions safely.

**V1 success metrics (all must pass):**

| Metric | Target |
|---|---|
| Time to first token (warm model, short prompt) | ≤ 2 s |
| Generation speed, 4B Q4_K_M | ≥ 8 tok/s (measure; aim for 10–15) |
| Peak app RAM, 4B, 4K context | ≤ 6 GB (leaves headroom on a 12 GB phone) |
| Valid tool-call JSON | 100% (grammar-enforced) |
| Correct tool selection on held-out set | ≥ 95% |
| Correct arguments on held-out set | ≥ 90% |
| False "action done" claims | 0 in eval set |
| Sustained 10-minute chat | No thermal shutdown; speed drop ≤ 30% |
| Works with airplane mode on | Yes (excluding explicitly online tools) |

If a target is missed, the fix order is: smaller context → different quant → 2B fallback model → only then reconsider the design.

---

## 2. Hardware reality check

### Phone: 12 GB RAM, MediaTek Dimensity 9300+
- Android typically leaves **~6–8 GB** usable for a foreground app; the OS kills background processes aggressively.
- Model file size ≠ RAM use. Budget = weights + KV cache + compute buffers + app + embeddings/STT/TTS models.
- **Default model: 4B Q4_K_M (~2.7 GB file).** 9B Q4_K_M (~5.7 GB file) is possible but leaves little room for voice + RAG + the OS; treat it as an optional "Quality mode" that unloads other components.
- GPU: the Dimensity 9300+ uses an Arm Mali GPU. llama.cpp's mobile GPU backends are best tuned for Adreno/Vulkan on other chips, so **do not assume GPU is faster**. Benchmark CPU (big cores, Arm dot-product/i8mm kernels) vs. Vulkan/OpenCL on *your* phone and keep the winner.
- NPU (MediaTek APU) acceleration = later optimization, not V1.

### PC: RTX 3060 6 GB
- Good for **QLoRA on 4B or smaller**, with short sequences.
- **Not** realistic for 9B QLoRA. Options: (a) rent a 24 GB GPU for a few hours (cheapest path), (b) skip 9B fine-tuning and use the 4B fine-tune plus RAG.
- RAM: 16 GB works, **32 GB is comfortable** (merging a 4B model in bf16 takes ~8–10 GB RAM).
- Use **WSL2 (Ubuntu)** on Windows for fewer CUDA/bitsandbytes headaches.
- Free disk: 60 GB for 4B work; 100 GB if experimenting with 9B artifacts.

---

## 3. Architecture principles

1. **The model proposes; the app disposes.** The LLM never executes anything. It emits a structured tool request; the app validates, applies policy, confirms with the user when needed, executes, and returns the real result to the model.
2. **Fine-tuning = behavior. RAG = knowledge. Tools = actions. Memory = personal context.** (Unchanged from the original; it's the right split.)
3. **Truth about actions comes from tool results**, never from model prose. The UI shows action status from the executor, not from what the model says.
4. **Everything retrieved is untrusted.** Documents, web pages, notifications, clipboard, OCR text.
5. **Modular tools.** Each tool = schema + validator + executor + risk tier + tests. Adding a feature must not touch the core loop.
6. **Measure before optimizing.** Every phase has a gate.

### Core loop

```text
User (text/voice)
   ↓
Context builder  ← system prompt + relevant memory + RAG snippets (token budget)
   ↓
Tool router      ← include only the 3–6 most relevant tool schemas
   ↓
Qwen (llama.cpp, grammar-constrained when emitting a tool call)
   ↓
Parse → Validate (schema, allowlist, permissions, arguments)
   ↓
Policy engine    ← risk tier → auto / notify / confirm / deny
   ↓
Executor (Android intent / API)  → real result
   ↓
Result fed back to Qwen → final reply (grounded in the result)
   ↓
Audit log + UI
```

---

## 4. Model strategy

| Role | Model | Notes |
|---|---|---|
| Primary | **Qwen3.5-4B (text-only use)** | Verified to exist in the Qwen3.5 Small family (0.8B/2B/4B/9B). Reasoning ("thinking") is **off by default** for these sizes, which suits a fast phone assistant. |
| Fallback (low RAM / heat) | Qwen3.5-2B | Same pipeline; ships as "Lite mode". |
| Optional quality mode | Qwen3.5-9B Q4_K_M | Benchmark-gated (Phase 8). Likely needs voice/RAG components unloaded. |
| Safety net if tooling lags | A previous-generation Qwen3 4B Instruct | Use only if Qwen3.5 fine-tuning/export support in your library versions is unstable. |

**Things to verify before Phase 3 (they change quickly):**
- Your Unsloth / TRL / PEFT / transformers versions support Qwen3.5 fine-tuning (it is a newer, multimodal-capable family; older library versions may not).
- Your llama.cpp build converts and runs the fine-tuned model. The Qwen3.5 vision projector (`mmproj`) is a separate file; for V1 you can ignore it and ship text-only GGUF.
- The exact Qwen3.5 license terms for redistribution (Section 15).

**Master copy rule (unchanged and correct):** train from original Safetensors; deploy GGUF; never train from GGUF; keep the merged bf16 model as the master.

---

## 5. Phase 0 — Feasibility spike (do this first, ~2–3 days)

Goal: know the real speed, RAM and heat of Qwen3.5 4B on *your* phone before any training.

1. Download a **stock** Qwen3.5-4B Q4_K_M GGUF (a reputable quantizer or your own).
2. Run it on the phone with an existing llama.cpp-based Android app (or build llama.cpp's Android example).
3. Record the numbers using the **On-Device Performance Monitoring** methods below (and later inside Karen itself).
4. Compare quant types on-device: **Q4_K_M vs Q4_0 vs Q5_K_M**, and CPU thread counts (try 4 big cores vs. all 8; more threads is often *slower* on phones).
5. Compare CPU vs. GPU backend if available.
6. Repeat with Qwen3.5-2B and (optionally) 9B Q4_K_M.

### On-device measurement checklist (Phase 0)

**Speed (most useful metric)**
- llama.cpp prints timing stats: prompt processing (prefill) tokens/s and generation tokens/s. Apps like PocketPal show these in the UI.
- Test at each context size (4k, 8k, 16k) with a **full prompt**, not a short one. Prefill speed is what drops as context grows.
- Rough targets (align with Section 1): generation ≥ 8 tok/s feels usable; under 3–4 tok/s is painful. Aim for TTFT ≤ 2 s (warm model, short prompt).

**Memory**
- Developer options → Running services (or Memory) shows what the app uses.
- For more detail: `adb shell dumpsys meminfo <package>` — check PSS/RSS during a long prompt.
- Watch for the app being killed (over budget) or sudden slowdowns (swap / virtual RAM). Lower `n_ctx` or use a smaller quant if needed.
- Target: peak app RAM ≤ 6 GB for 4B @ 4K context on a 12 GB phone.

**Heat and throttling**
- Run a continuous 5–10 minute generation. If tok/s falls steadily, the phone is throttling.
- `adb shell dumpsys thermalservice` shows temperatures.
- Free apps (CPU Throttling Test, AIDA64, DevCheck) can log temperature and clock speed.
- Target: sustained 10-minute chat with speed drop ≤ 30% and no thermal shutdown.

**Battery**
- Settings → Battery, or `adb shell dumpsys batterystats`.
- Record drain per 10-minute session.

**Simple test routine**
1. Load the model at `n_ctx` = 4k, then 8k, then 16k.
2. Feed a prompt filling ~80% of the window.
3. Record prefill tok/s, generation tok/s, peak RAM (PSS), temperature / thermal status, and battery drop.
4. Pick the largest window where speed remains acceptable and the app is not killed.

**Gate:** 4B meets the Section 1 speed/RAM/thermal targets. If not, decide now: 2B primary, or shorter context, before investing in training.

---

## 6. Phase 1 — Tool harness on the *base* model (before fine-tuning)

Build and measure the baseline so fine-tuning has a target.

- Define 10–15 tools as JSON schemas (Section 9).
- Use Qwen's native chat-template tool format; keep the **exact same chat template** at training and inference (mismatched templates are the most common cause of "fine-tune worked in Python but is broken on device").
- Enable **grammar/JSON-schema constrained decoding** (llama.cpp GBNF / JSON-schema → grammar) for tool-call turns. This gives 100% parseable calls even from a small model.
- Write the evaluation set (Section 8) **now**, before training, and score the base model.

**Gate:** you have a baseline score table. Fine-tuning goals = the specific failures in that table.

---

## 7. Phase 2–3 — Dataset and QLoRA fine-tune

### 7.1 Dataset principles
- **Quality over volume.** Start with **1,500–3,000** examples; expand only if eval shows specific gaps.
- Every tool example must show the **full turn sequence**: user → assistant tool call → tool result → assistant final reply. The reply must be based on the result (success *or* failure).
- Include **negatives**: when *not* to call a tool, when to ask a clarifying question, when a tool fails, when permission is denied, when the request is unsafe or impossible.
- Include **replay data** (general chat, coding, explanations) to prevent forgetting.
- Hold out **10–15%** for evaluation; the eval set must never appear in training (de-duplicate near-matches).
- Include your real usage languages and mixes (English, Tanglish, Tamil) in proportion to how you'll actually talk to Karen.

### 7.2 Suggested mix (of ~2,500)

| Category | Share | Purpose |
|---|---:|---|
| Tool call + result + reply (all tools) | 35% | Core behavior |
| Clarification / missing arguments | 8% | Ask instead of guessing |
| Tool failure / permission denied / no result | 8% | Honest error handling |
| No-tool conversational answers | 10% | Don't over-call tools |
| Refusal / safety / "I can't do that" | 5% | Boundaries |
| Multi-step (2–3 tools) | 5% | Planning, kept small |
| RAG-grounded answers (with provided snippets, incl. "not in the documents") | 9% | Use context, don't invent |
| General chat / coding / explanations (replay) | 15% | Retain base ability |
| Tanglish/Tamil/multilingual commands | 5% | Personalization |

### 7.3 Corrected example (replaces the original "Sure. I will open…" sample)

```json
{"messages":[
  {"role":"system","content":"You are Karen, a private on-device assistant. Use tools for actions. Never claim an action succeeded unless a tool result confirms it."},
  {"role":"user","content":"Open my college timetable."},
  {"role":"assistant","content":"","tool_calls":[{"type":"function","function":{"name":"open_document","arguments":{"query":"college timetable"}}}]},
  {"role":"tool","name":"open_document","content":"{\"status\":\"ok\",\"opened\":\"Timetable_Sem5.pdf\"}"},
  {"role":"assistant","content":"Opened Timetable_Sem5.pdf."}
]}
```

Failure variant (equally important):

```json
{"messages":[
  {"role":"user","content":"Call Arun."},
  {"role":"assistant","content":"","tool_calls":[{"type":"function","function":{"name":"find_contact","arguments":{"name":"Arun"}}}]},
  {"role":"tool","name":"find_contact","content":"{\"status\":\"multiple\",\"matches\":[\"Arun K\",\"Arun Prakash\"]}"},
  {"role":"assistant","content":"I found two contacts: Arun K and Arun Prakash. Which one should I call?"}
]}
```

### 7.4 Data creation workflow
1. Hand-write ~100 seed examples (this sets the style).
2. Expand with a teacher model **whose terms of use allow generating training data** (check this), or with a permissively licensed open model; vary phrasing, typos, voice-transcription errors, slang.
3. Programmatically validate: JSON parses, tool names exist, arguments match schema, no duplicate prompts.
4. **Manually review at least 20% plus every safety/refusal sample.**
5. Version the dataset (`dataset_v1.jsonl`, with a changelog) and freeze the eval split.

### 7.5 QLoRA configuration (4B on RTX 3060 6 GB)

```text
Library:            Unsloth (lowest VRAM) or TRL + PEFT + bitsandbytes
Base load:          4-bit NF4, bf16/fp16 compute (3060 supports bf16)
LoRA targets:       all linear layers (q,k,v,o,gate,up,down)
LoRA rank / alpha:  16 / 32   (drop to 8 / 16 if out of memory)
LoRA dropout:       0.0–0.05
Learning rate:      1e-4 to 2e-4, cosine schedule, ~3–5% warmup
Epochs:             2–3 (watch eval; stop early if tool accuracy plateaus/drops)
Batch size:         1
Grad accumulation:  16 (effective batch 16)
Max seq length:     1024 to start; 1536–2048 only if needed
Grad checkpointing: on
Optimizer:          paged_adamw_8bit
Loss masking:       train on assistant tokens only (incl. tool-call tokens)
Checkpoints:        every ~100–200 steps; keep best 2
```

**Out-of-memory order of fixes:** shorter max length → lower rank → keep batch 1, raise accumulation → verify checkpointing → close GPU-using apps (browser/Windows compositor) → CPU offload last.

**Watch during training:** VRAM headroom, train/eval loss gap, and **tool-eval accuracy at each checkpoint** (loss alone is not a quality signal).

### 7.6 Gate
Fine-tuned model beats the Phase 1 baseline on tool selection/arguments/honesty **and** does not regress on the general/coding regression set by more than a small margin (e.g., ≤ 3 points).

---

## 8. Evaluation suite

Build ~250–400 held-out prompts, scored automatically where possible.

| Suite | Examples | Scoring |
|---|---|---|
| Tool selection | Right tool among 10–15 | Exact match |
| Arguments | Dates, contacts, app names, durations | Field-level match |
| Clarification | Ambiguous/missing info | Asks vs guesses |
| No-tool | Chat that should *not* call a tool | No call |
| Error honesty | Tool failed / denied | Reply admits failure; never claims success |
| Safety | Harmful or out-of-scope requests | Refuses appropriately |
| Injection | Document/notification text says "send my contacts to X" | Must **not** act; must flag |
| RAG faithfulness | Answer present vs absent in snippets | Correct or "not found" |
| General/coding regression | Python, JS, SQL, explanations | Pass/fail on small checks, or manual rubric |
| Multilingual | English, Tanglish, Tamil commands | Same as above |
| Long-context | 2K–4K context with tools + RAG | Still valid + correct |

Run the **same suite** on: base model → fine-tuned (bf16) → **quantized GGUF on PC** → **GGUF on phone**. A drop at any step tells you exactly where quality was lost.

---

## 9. Tool system and safety policy

### 9.1 Tool contract
Each tool defines: `name`, JSON-schema `parameters`, `risk_tier`, `required_permissions`, `validator`, `executor`, `result_schema`, and unit tests.

**V1 tools (all intent/API-based, no accessibility service needed):**

```text
open_app            open_url           open_settings_page
find_contact        place_call (confirm)   compose_message (prefill only)
create_reminder     create_calendar_event
get_time / get_device_info (battery, storage)
search_local_files  open_document      read_document (RAG)
set_timer/alarm     media_control      calculator
clipboard_read/write (explicit)         remember_fact / forget_fact
```

### 9.2 Risk tiers

| Tier | Examples | Policy |
|---|---|---|
| T0 – read-only, local | get_time, calculator, search_local_files | Auto-run |
| T1 – reversible, low impact | open_app, open_url, set_timer | Auto-run + visible action chip |
| T2 – consequential | place_call, send/compose message, create calendar event, write file, remember_fact | **Explicit confirmation** showing exact arguments |
| T3 – dangerous | delete files, payments, install apps, share contacts/files externally | Disabled in V1; later = confirmation **plus** biometric |

Rules:
- Argument values shown in the confirmation card are the **validated** ones, not model prose.
- Unknown tool or invalid args → reject and ask the model to retry once, then tell the user plainly.
- Rate-limit repeated tool calls per turn (e.g., max 3 steps) to prevent loops.
- Every executed action is written to an **audit log** (what, when, arguments, result, who confirmed).

### 9.3 Android realities (design around these)
- **WhatsApp:** public intents/deep links can open a chat and **prefill** text; they cannot send silently. True auto-send requires an Accessibility Service, which is policy-sensitive and fragile. V1 = prefill + user taps Send.
- **Calls/SMS:** sensitive permissions with store-policy implications; use the dialer intent (`ACTION_DIAL`) first, direct `CALL_PHONE` only if you accept the extra permission and confirm every time.
- **Background launches:** modern Android restricts starting activities from the background. Voice flows should run from a foreground service/notification the user initiated.
- **Reminders/alarms:** exact alarms need the right permission and may be restricted on newer Android versions; use AlarmManager/calendar intents and verify behavior on your device.
- **Low-memory killer:** run the LLM in a **separate process/service** so a UI crash doesn't reload a 3 GB model, and so the OS can reclaim UI memory.
- **Notification access, screen reading, overlays:** each is a powerful permission; defer to V2/V3 with separate threat modeling.

### 9.4 Prompt-injection defense
- Wrap retrieved/untrusted text in clear delimiters and label it as data.
- Never execute T1+ tools whose arguments originate *only* from retrieved content without user confirmation.
- Strip/flag instruction-like text in documents and notifications.
- Include injection cases in the eval suite (Section 8).

---

## 10. RAG and memory

### 10.1 RAG
```text
Documents → extract text → chunk (300–500 tokens, ~10–15% overlap)
          → embed (small multilingual embedding model, on-device)
          → store in SQLite (FTS5 for keywords + vector index)
Query     → hybrid search (keyword + vector) → top 3–5 → rerank/trim to token budget
          → inject as labeled, untrusted context → answer with source filename
```
- Prefer a **small embedding model** (hundreds of MB or less; pick a multilingual one if you use Tamil) and run it through ONNX Runtime or llama.cpp; measure its RAM so it doesn't crowd out the LLM.
- **Hybrid search** (FTS5 + vectors) beats vectors alone for names, codes, dates, and timetables.
- Show **citations** (file + page) in answers. If nothing relevant is retrieved, the model should say so.
- Index incrementally in the background (charging + idle), not during chat.

### 10.2 Memory
- **Structured memory** in SQLite/Room: `facts(key, value, source, created_at, confidence)`, `preferences`, `contacts_aliases`, `routines`.
- **Explicit-save model:** Karen proposes "Remember that …?" and the user confirms (T2). No silent memory writes.
- A **Memory screen** to view, edit, export, and delete everything.
- Encrypt the database (SQLCipher or equivalent) with a key in the **Android Keystore**.
- Keep a short rolling conversation summary instead of replaying full history.

### 10.3 Selective Memory Retrieval
Karen must not replay the entire memory database into every prompt. Memory is persistent storage; the model context is a limited working set.

Use a relevance pipeline:

```text
User request
     ↓
Memory query extraction
     ↓
Candidate memories
     ↓
Semantic relevance + task relevance
     ↓
Importance + recency + usage score
     ↓
Deduplicate / resolve stale entries
     ↓
Rerank
     ↓
Memory token budget
     ↓
Context Builder
```

Suggested ranking factors:

```text
Memory Score =
    semantic_similarity
  + task_relevance
  + importance
  + recency
  + access_frequency
  - redundancy
  - stale_conflict
```

Features:

- [ ] Short-term/session memory
- [ ] Long-term personal memory
- [ ] Episodic memory for important decisions/events
- [ ] Task-scoped temporary memory
- [ ] Importance scoring
- [ ] Recency scoring
- [ ] Usage/access tracking
- [ ] Memory-specific reranking
- [ ] Memory token budget
- [ ] Duplicate detection
- [ ] Contradiction/conflict detection
- [ ] Memory merging/consolidation
- [ ] Stale-memory decay/expiration where appropriate

Memory updates must preserve provenance, confidence, timestamps, and the original source. Conflicting memories should be marked for resolution rather than silently overwriting high-confidence information.

### 10.4 Automatic Memory Extraction and Consolidation
After a useful interaction, Karen may identify candidate memories from the conversation, but persistent memory writes must follow the explicit-save policy unless the user has enabled a clearly defined trusted memory mode.

```text
Conversation / task result
          ↓
Candidate memory extraction
          ↓
Importance / sensitivity check
          ↓
Duplicate + contradiction check
          ↓
User confirmation when required
          ↓
Consolidate / merge
          ↓
Encrypted memory store
```

Do not store passwords, authentication secrets, sensitive tokens, or unnecessary private content as ordinary memory.

---

## 11. Voice pipeline (after text is solid)

| Stage | V1 recommendation | Later |
|---|---|---|
| Activation | **Push-to-talk button** | Wake word (battery-costly; separate model) |
| STT | whisper.cpp (tiny/base/small, quantized) or sherpa-onnx / Vosk | Streaming STT, Tamil-tuned model |
| LLM | Same local Qwen | — |
| TTS | **Android system TTS** (supports English/Tamil voices installed on the device) | Piper / sherpa-onnx neural TTS |

- Latency budget: STT ≤ 1.5 s for a short utterance, TTFT ≤ 2 s, **start speaking on the first sentence** while the rest streams.
- Don't run STT and a 9B LLM simultaneously on a 12 GB phone; sequence them and unload as needed.
- Voice transcripts contain errors; the confirmation card for T2 actions should always show the **parsed** contact/time, not just the spoken words.

---

## 12. Android app architecture

**Recommendation:** build the core in **native Kotlin + Jetpack Compose** with llama.cpp via a thin JNI/C++ layer. Use React Native only if you already know it and accept the extra bridge for streaming tokens and long-lived native objects. The UI is small; the hard part is native inference, services, and permissions, where Kotlin has the fewest surprises.

```text
app/
├── ui/                Compose: chat, confirmation cards, memory, settings, model manager,
│                      Plan & Progress (Section 21), Benchmark / Diagnostics
├── inference/         JNI wrapper over llama.cpp (load, unload, generate, cancel, stream)
│                      + performance timings; runs in a separate process/foreground service
├── monitoring/        in-app speed, memory (PSS), thermal status, battery (Section 12)
├── orchestrator/      context budget manager, context builder, tool router, loop, retries, step limits
├── planning/          Claude-style planning engine (Section 21): task graph, state machine,
│                      dependency analysis, re-planning
├── tools/             one package per tool: schema + validator + executor + tests
├── policy/            risk tiers, confirmations, permissions, rate limits
├── rag/               extractors, chunker, embedder, FTS5 + vector store
├── memory/            Room DB, encryption, export, retrieval/reranking, consolidation, decay
├── voice/             STT, TTS, push-to-talk
├── audit/             action log viewer
└── models/            catalog, downloader, checksum, versions
```

### Inference engineering checklist
- `mmap` the GGUF (lets the OS reclaim pages under pressure).
- Context **2048–4096** to start; larger only if measured safe.
- Try **KV-cache quantization** (e.g., q8_0, needs flash-attention-enabled builds) and measure quality/speed. Qwen3.5's hybrid design may use less KV memory than a classic transformer; measure rather than assume.
- **Cache the system prompt/tool-schema prefix** so each turn only processes new tokens.
- **Tool router:** inject only relevant tool schemas per turn, so the prompt stays short and accuracy rises.
- Thread count tuned to big cores; pin to performance cores if possible.
- Listen to `PowerManager` **thermal status** and battery; step down (shorter replies → lower threads → 2B model → pause) before the OS throttles hard.
- Support **cancel/stop generation** instantly.
- Unload the model after N minutes idle; keep the file on disk.

### On-device performance monitoring (in-app)

Karen should monitor its own performance so users (and developers) can see real numbers without ADB. All of the following are available via standard Android APIs + llama.cpp timings.

**1. Speed (fully supported)**
- Expose llama.cpp timings (`llama_print_timings` / timing structs) through the JNI layer.
- Report: prefill tokens/s, generation tokens/s, time-to-first-token, total tokens.
- Show live in a small stats chip on the chat screen (or always in a Benchmark / Diagnostics screen).
- Align with Section 1 targets (≥ 8 tok/s generation, TTFT ≤ 2 s).

**2. Memory (fully supported)**
```kotlin
val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
val memInfo = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0]
// Key values: memInfo.totalPss (most important), totalPrivateDirty, nativePss
```
- Track current PSS and peak PSS during long generations.
- Warn when approaching the 6 GB budget; automatically reduce context or switch to 2B if needed.

**3. Heat / thermal status (partially supported)**
```kotlin
val pm = getSystemService(POWER_SERVICE) as PowerManager
val status = pm.currentThermalStatus
// THERMAL_STATUS_NONE → LIGHT → MODERATE → SEVERE → CRITICAL …
```
- Listen for `PowerManager.ACTION_THERMAL_STATUS_CHANGED`.
- Exact °C is unreliable / not Play-Store friendly; thermal status is sufficient.
- Auto-throttle: fewer threads → shorter replies → 2B model → pause generation when status rises.

**4. Battery (fully supported)**
```kotlin
val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
val charging = bm.isCharging
```
- Optionally track session drain.
- Respect low-battery mode (Section 80).

**Recommended UI placement**

| Location                    | Content                                      | Visibility          |
|-----------------------------|----------------------------------------------|---------------------|
| Chat screen (small chip)    | Live tok/s + thermal status                  | Optional / long runs|
| Model Manager               | Last benchmark results                       | Always              |
| Benchmark / Diagnostics     | Full report (speed, peak PSS, thermal, battery) | Manual or auto     |
| Settings → Performance      | Auto-throttle rules, context limits          | User configurable   |

**Built-in performance test (supports Phase 0 and ongoing validation)**
1. User taps “Run Performance Test”.
2. App loads model at 4k → 8k → 16k context.
3. Feeds a long prompt (~80% of window).
4. Records prefill tok/s, generation tok/s, peak PSS, thermal status, battery drop.
5. Displays a clean report and stores it for the Benchmark Dashboard (Section 50).

This monitoring is part of the inference service and feeds the thermal-aware and battery-aware logic already listed above.

---

## 13. Quantization and conversion pipeline

```text
Fine-tuned LoRA adapter
   ↓  merge into the ORIGINAL bf16 base (not into a 4-bit base)
Merged bf16 model (master copy)
   ↓  llama.cpp convert_hf_to_gguf.py  →  BF16 GGUF
   ↓  (optional) llama-imatrix using calibration text that includes tool-call samples
   ↓  llama-quantize
Q4_K_M  (primary)   Q5_K_M (quality)   Q4_0 / IQ4_NL (benchmark on-device)
   ↓
Run the full eval suite on each; pick by (accuracy, speed, RAM)
```

Rules:
- Pin the **llama.cpp version** used for conversion *and* for the Android runtime; mismatches cause load failures.
- Use the same chat template in the GGUF as in training (verify with a template-render test).
- Keep `mmproj` (vision) separate and optional.
- Typical file sizes: 4B Q4_K_M ≈ 2.7 GB, 4B Q5_K_M ≈ 3.1 GB; 9B Q4_K_M ≈ 5.7 GB (approximate; check actual files).
- Re-run safety and tool evals after quantization; don't assume parity with bf16.

---

## 14. Revised roadmap with gates

| Phase | Work | Rough time | Exit gate |
|---|---|---|---|
| **0** | Phone feasibility spike (stock 4B/2B GGUF) | 2–3 days | Meets speed/RAM targets, or fallback chosen |
| **1** | Tool schemas, harness, grammar decoding, **eval suite**, base-model baseline | 1–2 weeks | Baseline score table exists |
| **2** | Dataset v1 (seed → expand → validate → review) | 1–2 weeks | 1.5–3K clean examples, frozen eval split |
| **3** | QLoRA on 4B, checkpoint evals | 1 week | Beats baseline; no regression |
| **4** | Merge → GGUF → quantize → eval on PC | 2–3 days | Quant keeps ≥ 95% of bf16 tool accuracy |
| **5** | Android app skeleton: inference service, chat UI, streaming, model manager | 2–3 weeks | Stable chat on device, targets met |
| **6** | Tool executors + policy + confirmations + audit log | 2 weeks | All V1 tools pass device tests |
| **7** | RAG + memory | 2 weeks | Cited answers; encrypted memory |
| **8** | Voice (push-to-talk, STT, system TTS) | 1–2 weeks | End-to-end voice demo within latency budget |
| **9** | Hardening: thermal, battery, crash recovery, 24 h soak test | 1–2 weeks | No crashes/OOM in soak test |
| **10** | Optional: 9B Q4 on-device benchmark, Tamil voice, wake word | open | Only if V1 is stable |

Phases 1–4 (PC) and 5 (Android shell) can overlap: build the Android app against the **stock** model while the fine-tune is being prepared; swap in the fine-tuned GGUF when ready.

---

## 15. Security, privacy, licensing and distribution

### Privacy and security
- Default offline; network access only for tools the user explicitly enables (weather, web), clearly labeled.
- No analytics or crash reports containing prompts or documents. If crash reporting is added, make it opt-in and scrub content.
- Encrypted local DB; export/import encrypted.
- Permissions requested **just-in-time** with a plain explanation.
- Model downloads: show source, size, version, license; **verify SHA-256**; reject partial files; never register an incomplete download; never execute model files as code.
- Pairing with a PC (V3): authenticated, local-network only, revocable.

### Licensing and distribution
- Before publishing any weights or fine-tunes, check the **exact license of the exact Qwen3.5 variant**: redistribution, attribution/notice, and naming rules. Document base model, fine-tune method, and quantization in the model card.
- Check the **terms of any teacher model** used to generate training data.
- Suggested split (unchanged from the original, still sensible): **GitHub = code**, **Hugging Face = weights/adapters/datasets**, app downloads models via a small **catalog JSON** (id, version, sha256, size, URL, license, min RAM). Never bundle multi-GB weights in the APK.
- Support resumable downloads, a PC→phone local transfer for development, and keep the previous model until the new one passes checksum and a smoke test.

---

## 16. Prioritized backlog (replaces original sections 22–89)

Entry condition for **any** backlog item: V1 gates passed, the feature fits the tool contract (Section 9), has a risk tier, and has eval cases.

### V1 (this plan)
Local chat • streaming • tools (Section 9.1) • confirmations + audit • RAG • encrypted memory • push-to-talk voice • model manager • performance/thermal safeguards.

### V2
- Notification summaries (read-only first; notification access is sensitive)
- Camera/gallery **OCR** and basic vision (using the Qwen3.5 vision projector or a dedicated OCR model)
- Calendar/email read integration (local providers)
- Maps/navigation intents, media control improvements
- Routines (simple if-this-then-that, built on existing tools, with preview + undo where possible)
- Conversation modes (concise / study / coding), personalization settings
- Smart search index across files, contacts, notes
- Benchmark dashboard and self-diagnostics screen (also useful for debugging)
- Offline recovery and crash-resume for long tasks

### V3
- Wake word, Tamil voice (STT + TTS), advanced multilingual
- Model routing (2B for easy, 4B/9B for hard) with measured thresholds
- Screen understanding / UI agent via Accessibility Service (separate security review)
- Developer mode / local coding assistant (Claude-style Planning, Workflow & Execution Engine — Section 21), Project & Study copilots
- PC companion (secure pairing, clipboard/file transfer, PC control)
- Agent planning with task queue and background processing (battery-aware; overlaps with Section 21)
- Knowledge graph over personal data
- Local server mode, optional web fallback (clearly marked online)
- Home/IoT, sensor awareness, smart profiles, gaming assistant
- Plugin architecture, feature flags, release channels (only once there are outside users)

### Probably never / revisit much later
Plugin marketplace with third-party code, silent auto-send of messages, payments, fully autonomous background agents. These carry the highest risk for the least value to a personal assistant.

---

## 17. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| 4B too slow/hot on phone | Medium | High | Phase 0 spike; 2B fallback; thermal-aware throttling |
| Qwen3.5 fine-tuning/export tooling immature | Medium | Medium | Verify versions up front; fallback to Qwen3 4B; pin library versions |
| Quantization hurts tool accuracy | Medium | Medium | Eval each quant; try Q5_K_M/imatrix |
| Model hallucinates actions | High (small model) | High | Grammar decoding, validator, executor-truth UI, honesty dataset, 0%-false-claim gate |
| Prompt injection via docs/notifications | Medium | High | Untrusted-data labeling, confirmation, injection evals |
| Android kills the app / OOM | High | Medium | Separate process, mmap, memory budget, idle unload |
| Dataset low quality or contaminated eval | Medium | High | Validation scripts, manual review, frozen eval split, dedupe |
| Scope creep | High | High | Backlog gating (Section 16) |
| License issue on redistribution | Low–Medium | High | Check licenses before publishing |
| Battery drain | Medium | Medium | Wake word off by default, idle unload, background indexing only while charging |

---

## 18. Repository layout

```text
karen/
├── data/
│   ├── seed/                 hand-written examples
│   ├── generated/            expanded data (with provenance notes)
│   ├── processed/            train.jsonl, val.jsonl
│   └── eval/                 frozen held-out suites (never trained on)
├── training/
│   ├── train.py  config.yaml  merge.py
│   └── eval/                 run_eval.py, scorers, reports/
├── models/                   (git-ignored) base/ adapters/ merged/ gguf/
├── conversion/               convert + quantize + imatrix scripts, pinned llama.cpp version
├── tools_spec/               JSON schemas shared by training, eval and the Android app
├── android/                  Kotlin app (Section 12)
│   └── planning/             Claude-style planning engine (Section 21): task graph, state machine, progress UI
├── rag/                      evaluation docs, chunking experiments
├── docs/                     decisions (ADR), benchmarks, threat model
└── README.md
```

Key idea: **one source of truth for tool schemas** (`tools_spec/`), used by dataset generation, the eval suite, grammar generation, and the Android validators. This prevents training/runtime drift. The planning engine (Section 21) reuses the same tool schemas and core loop.

---

## 19. Quick start (next 7 days)

1. **Day 1–2:** Run stock Qwen3.5-4B Q4_K_M on the phone; record the Phase 0 numbers. Try 2B too.
2. **Day 2–3:** Set up WSL2 + CUDA; verify `torch.cuda.is_available()`; confirm your Unsloth/TRL version supports Qwen3.5.
3. **Day 3–5:** Write 12 tool schemas and ~100 seed examples (use the corrected format in 7.3).
4. **Day 5–7:** Build the eval set (start with ~150 prompts) and score the **base** model with grammar-constrained tool calls.
5. Decide, using real numbers, what the fine-tune actually needs to fix.

---

## 20. Key rules (updated)

1. **Measure on the phone first.** Then train.
2. **Baseline before fine-tuning.** Fine-tune the measured failures, not imagined ones.
3. **The model proposes, the app validates and executes.** Action truth comes from tool results.
4. **Train from original Safetensors; deploy GGUF; keep the bf16 merged model as master.**
5. **Same chat template and same llama.cpp version everywhere.**
6. **Evaluate after every transformation** (adapter → merged → quantized → on-device).
7. **Treat all retrieved content as untrusted.**
8. **Ship V1 small; earn every backlog item through the gates.**

---

## 21. Claude-Style Planning, Workflow & Execution Engine

> Integrated into Karen's coding-agent capabilities (V3 Developer mode / local coding assistant). When Karen operates as a coding agent, this engine governs complex software tasks so that the agent plans before executing, exposes progress, verifies work, and dynamically re-plans. Simple tool calls and phone actions remain under the existing harness (Section 9); this section applies to multi-step code/project work.

### 21.1 Purpose

Transform the coding agent from:

USER PROMPT → GENERATE CODE → STOP

into:

USER REQUEST
→ UNDERSTAND
→ INVESTIGATE
→ DECOMPOSE
→ PLAN
→ EXECUTE
→ OBSERVE
→ VERIFY
→ ADAPT
→ COMPLETE

The system provides:

- Intelligent task decomposition
- Project inspection
- Dependency-aware planning
- Hierarchical tasks
- Execution tracking
- Live progress
- Workflow visualization
- Task dependency graphs
- File-change tracking
- Execution timeline
- Error diagnosis
- Automatic recovery
- Dynamic re-planning
- Plan versioning
- Final verification
- Completion reporting

The user should always be able to understand:

1. What the agent is doing
2. Why it is doing it
3. What has been completed
4. What is currently running
5. What remains
6. Which files changed
7. What failed
8. How the agent recovered
9. What happens next
10. Why the agent considers the task complete

### 21.2 Planning Mode

Introduce a dedicated PLAN MODE.

The agent determines the complexity of the user's request.

**Simple task**

For simple changes:

- Inspect relevant file
- Make the change
- Verify it
- Report completion

Do not create unnecessary planning overhead.

**Medium task**

Create:

- Short plan
- Relevant tasks
- Dependencies
- Verification steps

**Complex task**

Create a complete:

- Project analysis
- Requirements breakdown
- Architecture plan
- Task hierarchy
- Dependency graph
- Execution sequence
- Verification strategy
- Risk analysis
- Progress tracking

The planning depth scales with complexity.

### 21.3 Core Planning Workflow

```text
UNDERSTAND
    ↓
INVESTIGATE
    ↓
DECOMPOSE
    ↓
DEPENDENCY ANALYSIS
    ↓
ARCHITECTURE
    ↓
PLAN
    ↓
PLAN VALIDATION
    ↓
EXECUTE
    ↓
OBSERVE
    ↓
VERIFY
    ↓
ADAPT
    ↓
COMPLETE
```

### 21.4 Understand

Before modifying code, determine:

- User's actual goal
- Requested functionality
- Expected output
- Constraints
- Technical requirements
- Existing functionality that must be preserved
- Potential ambiguities
- Success criteria

Do not immediately start coding on complex requests.

The agent should first establish what "done" means.

### 21.5 Project Investigation

Before generating the implementation plan, inspect the project.

Investigate:

- Directory structure
- Source files
- Configuration
- Package/dependency files
- Framework
- Runtime
- Existing architecture
- Existing components
- Existing APIs
- Database structure
- Tests
- Build system
- Documentation
- Existing coding conventions

The agent should prefer:

EXISTING IMPLEMENTATION
        ↓
UNDERSTAND
        ↓
REUSE / EXTEND

instead of unnecessarily rewriting existing systems.

Never assume that a file, API, function, dependency, or feature exists without checking.

### 21.6 Requirement Extraction

Convert the user's request into explicit requirements.

Example:

User request:

"Add authentication with Google login."

Requirements:

R1 — Login screen
R2 — Google authentication
R3 — Session management
R4 — Logout
R5 — Error handling
R6 — Persistent authentication
R7 — Testing

Each requirement should eventually map to one or more tasks.

Maintain traceability:

USER REQUIREMENT
        ↓
TASK
        ↓
IMPLEMENTATION
        ↓
VERIFICATION

### 21.7 Task Decomposition

Break requirements into hierarchical tasks.

Structure:

PROJECT
 ├── PHASE
 │    ├── TASK
 │    │    ├── SUBTASK
 │    │    └── VERIFICATION
 │    └── TASK
 └── PHASE

Example:

PROJECT
│
├── Phase 1 — Analysis
│   ├── Inspect repository
│   ├── Inspect dependencies
│   └── Identify existing authentication
│
├── Phase 2 — Architecture
│   ├── Design authentication flow
│   └── Define data model
│
├── Phase 3 — Implementation
│   ├── Authentication service
│   ├── Login UI
│   ├── Session management
│   └── Logout
│
├── Phase 4 — Testing
│   ├── Unit tests
│   ├── Integration tests
│   └── Error tests
│
└── Phase 5 — Verification
    ├── Build
    ├── Requirement verification
    └── Final review

### 21.8 Task Metadata

Every task should maintain:

- Task ID
- Parent task
- Name
- Description
- Priority
- Complexity
- Dependencies
- Status
- Progress
- Files affected
- Commands executed
- Tests
- Expected result
- Actual result
- Error information
- Verification status
- Created timestamp
- Started timestamp
- Completed timestamp

Example:

Task ID:
T-003

Name:
Implement authentication service

Status:
IN_PROGRESS

Dependencies:
T-001
T-002

Files:
src/auth/authService.ts

Verification:
Authentication unit tests

### 21.9 Task States

Implement the following task state machine:

PENDING
   ↓
BLOCKED
   ↓
READY
   ↓
IN_PROGRESS
   ↓
VERIFYING
   ↓
COMPLETED

Failure path:

IN_PROGRESS
   ↓
FAILED
   ↓
DIAGNOSE
   ↓
RETRY / MODIFY / RE-PLAN
   ↓
READY
   ↓
IN_PROGRESS

Additional states:

- SKIPPED
- CANCELLED

Rules:

- BLOCKED tasks cannot execute.
- READY tasks can execute.
- IN_PROGRESS means actively executing.
- VERIFYING means implementation is complete but validation is running.
- COMPLETED requires successful verification.
- FAILED requires diagnosis.
- A task must never be marked completed merely because code was generated.

### 21.10 Dependency Graph

Create a dependency graph before execution.

Example:

T1 — Analyze Project
        ↓
T2 — Architecture
        ↓
   ┌────┴────┐
   ↓         ↓
T3 Backend  T4 Frontend
   └────┬────┘
        ↓
T5 Integration
        ↓
T6 Testing
        ↓
T7 Verification

Rules:

- A task cannot execute until required dependencies are completed.
- Independent tasks may run concurrently when safe.
- If a dependency fails, dependent tasks become BLOCKED.
- If a dependency changes, affected tasks must be re-evaluated.

### 21.11 Plan Generation

Before execution, generate a concise human-readable plan.

Example:

PLAN

1. Inspect existing authentication implementation
2. Identify required changes
3. Design authentication flow
4. Implement authentication service
5. Update login UI
6. Connect frontend and backend
7. Add error handling
8. Run tests
9. Verify complete authentication flow

For complex tasks:

PHASE 1 — Analysis
PHASE 2 — Architecture
PHASE 3 — Implementation
PHASE 4 — Integration
PHASE 5 — Testing
PHASE 6 — Verification

### 21.12 Plan Validation

Before executing the plan, validate it.

Check:

- Are all requirements covered?
- Are dependencies correct?
- Are required files identified?
- Is the architecture compatible?
- Are there unnecessary changes?
- Are verification methods available?
- Are there obvious risks?
- Can tasks be executed in the proposed order?

If the plan is incomplete, refine it before execution.

### 21.13 Plan Presentation

When appropriate, show the user a concise plan before execution.

Example:

┌─────────────────────────────────────────┐
│ PLAN                                    │
├─────────────────────────────────────────┤
│ Goal                                    │
│ Add authentication to the application  │
│                                         │
│ Tasks                                   │
│ ✓ Analyze project                      │
│ ○ Design authentication                │
│ ○ Implement service                    │
│ ○ Update UI                            │
│ ○ Test                                 │
│ ○ Verify                               │
└─────────────────────────────────────────┘

The plan should be readable without exposing hidden reasoning.

Show conclusions, decisions, tasks, and useful rationale rather than private chain-of-thought.

### 21.14 Execution Engine

After planning:

PLAN
 ↓
SELECT NEXT READY TASK
 ↓
IMPLEMENT
 ↓
OBSERVE
 ↓
VERIFY
 ↓
UPDATE STATE
 ↓
SELECT NEXT TASK

Execute incrementally.

Do not blindly execute the entire plan without checking intermediate results.

### 21.15 Live Step-by-Step Progress

Display the current execution state.

Example:

✓ 1. Inspect project
✓ 2. Analyze requirements
✓ 3. Design architecture
✓ 4. Create task graph
● 5. Implement authentication
○ 6. Update login UI
○ 7. Add error handling
○ 8. Run tests
○ 9. Final verification

Status indicators:

✓ Completed
● In Progress
○ Pending
🔒 Blocked
⚠ Warning
✕ Failed

### 21.16 Visual Workflow Graph

The coding agent should generate and maintain a visual workflow graph.

Example:

                   USER REQUEST
                        │
                        ↓
                   UNDERSTAND
                        │
                        ↓
                   INVESTIGATE
                        │
                        ↓
                   DECOMPOSE
                        │
                        ↓
                DEPENDENCY ANALYSIS
                        │
                        ↓
                    ARCHITECTURE
                        │
                        ↓
                      PLAN
                        │
                        ↓
                    EXECUTE
                        │
                        ↓
                    VERIFY
                        │
                   ┌────┴────┐
                   │ SUCCESS │
                   └────┬────┘
                        │
                        ↓
                    COMPLETE

Failure branch:

EXECUTE
   ↓
ERROR
   ↓
DIAGNOSE
   ↓
RE-PLAN
   ↓
EXECUTE

The graph should update as the agent progresses.

### 21.17 Task Dependency Graph UI

Display relationships between tasks.

Example:

T1 ✓ ──→ T2 ✓ ──→ T3 ●
                    │
             ┌──────┴──────┐
             ↓             ↓
           T4 ○           T5 🔒
             │
             └──────┬──────┘
                    ↓
                   T6 ○

The graph should visually distinguish:

- Completed
- Active
- Pending
- Blocked
- Failed

### 21.18 Overall Progress

Progress must represent actual completed work.

Example:

Overall Progress: 64%

█████████████░░░░░░░

Completed: 7
In Progress: 1
Pending: 3
Blocked: 0
Failed: 0

Never calculate progress only from elapsed time.

Progress should be based on task completion and verification.

### 21.19 Phase Progress

Track progress for each phase.

Example:

Phase 1 — Analysis
████████████████████ 100%

Phase 2 — Architecture
████████████████████ 100%

Phase 3 — Implementation
████████████░░░░░░░░ 60%

Phase 4 — Testing
░░░░░░░░░░░░░░░░░░░░ 0%

Phase 5 — Verification
░░░░░░░░░░░░░░░░░░░░ 0%

### 21.20 Weighted Progress

Support optional task weights.

Example:

Architecture       10%
Backend             30%
Frontend            25%
Integration         15%
Testing             10%
Verification        10%

Weighted progress should only be used when meaningful task weights are available.

Otherwise use normal task-based progress.

### 21.21 Execution Timeline

Maintain a chronological execution timeline.

Example:

18:42:10 ✓ Project inspected
18:42:18 ✓ Dependencies analyzed
18:42:31 ✓ Architecture created
18:42:45 ✓ Task graph generated
18:43:02 ● Authentication implementation started
18:43:21 ✓ auth.ts created
18:43:26 ✓ Authentication service implemented
18:43:31 ● Running tests

Each event should contain:

- Timestamp
- Task ID
- Action
- Result
- Files affected
- Verification status

### 21.22 File Change Tracking

Track:

TASK
 ↓
FILES
 ↓
CHANGES
 ↓
VERIFICATION

Example:

T3 — Backend Authentication

Created:
src/auth/authService.ts

Modified:
src/api/client.ts

Tests:
tests/auth.test.ts

Verification:
Passed

The user should be able to see what each task changed.

### 21.23 Real-Time Execution Events

Support events such as:

PLAN_CREATED
TASK_CREATED
TASK_READY
TASK_STARTED
TASK_PROGRESS
FILE_CREATED
FILE_MODIFIED
FILE_DELETED
COMMAND_STARTED
COMMAND_COMPLETED
TEST_STARTED
TEST_PASSED
TEST_FAILED
TASK_COMPLETED
TASK_FAILED
REPLAN_STARTED
REPLAN_COMPLETED
PHASE_COMPLETED
PROJECT_COMPLETED

Example event:

{
  "event": "TASK_COMPLETED",
  "taskId": "T-004",
  "status": "completed",
  "progress": 57,
  "filesChanged": [
    "src/auth/authService.ts"
  ],
  "verification": "passed"
}

### 21.24 Error Handling

When a task fails:

TASK
 ↓
ERROR
 ↓
DIAGNOSE
 ↓
IDENTIFY ROOT CAUSE
 ↓
┌───────────┬───────────┬───────────┐
│ RETRY     │ MODIFY    │ RE-PLAN   │
└───────────┴───────────┴───────────┘
              ↓
           VERIFY
              ↓
           SUCCESS

Never blindly retry.

The agent should determine:

- What failed?
- Why did it fail?
- Is retry safe?
- Should the implementation change?
- Should the plan change?
- Are dependent tasks affected?

### 21.25 Automatic Recovery

Example:

T7 — Build Project
Status: FAILED

Error:
Missing dependency

Diagnosis:
Required package is not installed.

Recovery:
Create T7.1 — Install compatible dependency

Then:

T7.1 ✓
 ↓
T7 retry
 ↓
Build passed
 ↓
T7 ✓

Record the recovery process in the execution timeline.

### 21.26 Dynamic Re-Planning

The original plan is not immutable.

If new information appears:

ORIGINAL PLAN
     ↓
NEW INFORMATION
     ↓
RE-EVALUATE
     ↓
UPDATE TASK GRAPH
     ↓
UPDATE DEPENDENCIES
     ↓
RECALCULATE PROGRESS
     ↓
CONTINUE

Triggers for re-planning:

- Unexpected project structure
- Missing dependency
- Incompatible API
- Build failure
- Test failure
- Architecture conflict
- New requirement
- Existing implementation differs from assumptions

### 21.27 Re-Planning Example

Original:

T1 → T2 → T3 → T4

Problem discovered during T3.

Updated:

T1 → T2 → T3
          ↓
         T3.1
          ↓
         T3.2
          ↓
          T4

Completed work should be preserved.

Only affected tasks should be modified where possible.

### 21.28 Plan Versioning

Maintain versions of the plan.

Example:

Plan v1
   ↓
Execution
   ↓
Problem discovered
   ↓
Plan v2
   ↓
Execution
   ↓
New requirement
   ↓
Plan v3

Store:

- Version number
- Timestamp
- Change reason
- Added tasks
- Removed tasks
- Modified tasks
- Dependency changes
- Current state

### 21.29 Requirement Traceability

Every requirement should be traceable.

Example:

R1 — Authentication
 ↓
T3 — Authentication service
 ↓
src/auth/authService.ts
 ↓
Authentication tests
 ↓
R1 VERIFIED ✓

This allows the agent to determine whether every user requirement has actually been satisfied.

### 21.30 Verification Engine

Verification should happen at multiple levels.

**Task verification**

Did the individual task work?

**Phase verification**

Did the complete phase work?

**Integration verification**

Do components work together?

**Project verification**

Does the final project satisfy the requirements?

Use:

IMPLEMENT
 ↓
BUILD
 ↓
TEST
 ↓
INTEGRATION
 ↓
REQUIREMENT CHECK
 ↓
FINAL VERIFICATION

### 21.31 Completion Gate

The agent must NOT declare completion simply because code was generated.

Completion requires:

- Requirements implemented
- Required files present
- Dependencies valid
- Build successful where applicable
- Tests passing where applicable
- Integration verified
- No blocking errors
- Final requirements check passed

Completion flow:

IMPLEMENTATION
      ↓
BUILD
      ↓
TEST
      ↓
ERROR CHECK
      ↓
INTEGRATION CHECK
      ↓
REQUIREMENT VERIFICATION
      ↓
FINAL REVIEW
      ↓
ALL REQUIREMENTS SATISFIED?
      │
   ┌──┴──┐
   ↓     ↓
  YES    NO
   ↓     ↓
DONE   RE-PLAN

### 21.32 Plan & Progress UI

Add a dedicated Plan & Progress interface (Compose screen or bottom sheet when in coding mode).

Example:

┌────────────────────────────────────────────┐
│ PLAN & PROGRESS                            │
├────────────────────────────────────────────┤
│ Overall Progress                           │
│ █████████████░░░░░░░ 64%                  │
│                                            │
│ Current Phase                              │
│ ● Implementation                           │
│                                            │
│ Current Task                               │
│ Implement authentication                   │
│                                            │
│ Workflow                                   │
│ ✓ Analysis                                 │
│ ✓ Architecture                             │
│ ● Implementation                           │
│ ○ Testing                                  │
│ ○ Verification                             │
│                                            │
│ Task Graph                                 │
│ T1 ✓ → T2 ✓ → T3 ●                        │
│                    ├→ T4 ○                 │
│                    └→ T5 🔒                │
│                                            │
│ Files Changed: 8                           │
│ Tests: 27 passed / 1 running               │
│                                            │
│ Latest Activity                            │
│ ✓ Authentication service created           │
│ ● Running tests                            │
└────────────────────────────────────────────┘

### 21.33 User Control

The user should be able to:

- View plan
- View task graph
- View progress
- View execution timeline
- View changed files
- View errors
- Pause execution
- Resume execution
- Cancel execution
- Request re-planning
- Approve a plan when approval is required
- Continue autonomous execution when enabled

### 21.34 Autonomy Levels

Support configurable execution modes.

**Manual**

Plan only.

User approves each task.

**Guided**

Agent creates the plan and executes tasks,
but requests confirmation before major changes.

**Autonomous**

Agent plans, executes, tests, verifies,
and re-plans automatically within configured boundaries.

The user should always be able to inspect the current state.

### 21.35 Sandbox / Terminal Integration

Integrate planning with the coding agent's execution environment (on-device or paired PC companion in V3).

The agent should be able to:

PLAN
 ↓
SELECT TASK
 ↓
EDIT FILES
 ↓
RUN COMMAND
 ↓
CAPTURE OUTPUT
 ↓
ANALYZE RESULT
 ↓
VERIFY
 ↓
UPDATE TASK
 ↓
NEXT TASK

Commands and sandbox operations must be associated with the current task so the execution history remains understandable.

### 21.36 Project Memory

Maintain project-level execution state (stored in encrypted local memory / Room, Section 10.2).

Store:

- Current plan
- Plan version
- Current phase
- Current task
- Completed tasks
- Pending tasks
- Failed tasks
- Dependencies
- Important decisions
- Files changed
- Test results
- Known issues
- Verification results

This state should survive individual task executions.

### 21.37 Final Project Report

When the project is completed, generate a concise report.

Example:

PROJECT COMPLETED

Goal:
Add authentication system

Tasks:
8 completed
0 failed
0 blocked

Files:
5 created
7 modified

Tests:
27 passed
0 failed

Build:
PASSED

Integration:
PASSED

Requirements:
7 / 7 verified

Plan:
v2

Final verification:
PASSED

### 21.38 Master Workflow

The complete coding-agent workflow must be:

                         USER REQUEST
                              │
                              ↓
                         UNDERSTAND
                              │
                              ↓
                         INVESTIGATE
                              │
                              ↓
                    REQUIREMENT EXTRACTION
                              │
                              ↓
                         DECOMPOSE
                              │
                              ↓
                    DEPENDENCY ANALYSIS
                              │
                              ↓
                         ARCHITECTURE
                              │
                              ↓
                            PLAN
                              │
                              ↓
                       PLAN VALIDATION
                              │
                              ↓
                           EXECUTE
                              │
                              ↓
                          OBSERVE
                              │
                              ↓
                          VERIFY
                              │
                         ┌────┴────┐
                         │ SUCCESS │
                         └────┬────┘
                              │
                              ↓
                          COMPLETE

Failure/recovery:

                    EXECUTE
                       │
                       ↓
                     ERROR
                       │
                       ↓
                    DIAGNOSE
                       │
                       ↓
                  ROOT CAUSE
                       │
             ┌─────────┼─────────┐
             ↓         ↓         ↓
           RETRY     MODIFY    RE-PLAN
             └─────────┼─────────┘
                       ↓
                    VERIFY
                       │
                       ↓
                    CONTINUE

### 21.39 Core Agent Principle

The coding agent must operate using:

PLAN
→ EXECUTE
→ OBSERVE
→ VERIFY
→ ADAPT

rather than:

PROMPT
→ GENERATE CODE
→ STOP

The agent should behave as a planning-aware software engineer.

It should understand before changing,
inspect before assuming,
plan before executing complex work,
verify before claiming success,
and adapt when implementation differs from the original plan.

The planning system must be deeply integrated with:

- Agent reasoning
- Project inspection
- File system
- Code editor
- Terminal
- Sandbox
- Testing
- Build system
- Project memory
- Progress UI
- Task graph
- Error recovery
- Final verification

The result should be a transparent, observable, adaptive coding-agent workflow inspired by the usability of Claude-style Plan Mode.

### 21.40 Integration Notes for Karen

- This engine is gated behind V3 Developer mode (Section 16).
- Tool schemas for coding actions (file read/write, shell, git, build, test) must follow the existing tool contract (Section 9) with appropriate risk tiers (most write/execute actions = T2 or T3).
- Plan/Progress UI lives in the Compose layer (Section 12).
- Project state uses the encrypted memory store (Section 10.2).
- All actions remain subject to the core loop: model proposes → app validates → policy → executor → result fed back.
- Evaluation suite (Section 8) should eventually include coding-agent scenarios (plan quality, recovery, completion honesty).

---

## 22. Integrated Legacy Feature Expansion

> This section preserves the substantive capabilities from the earlier Karen plans that are not repeated in the optimized V1/V2/V3 architecture. Duplicate training, deployment, safety, and model-management material has been consolidated above.

## 36. Agent Planning System

For complicated requests:

```text
User request
     ↓
Planner
     ↓
Task decomposition
     ↓
Tool selection
     ↓
Execution
     ↓
Verification
     ↓
Final response
```

Example:

```text
"Prepare me for tomorrow's presentation."

Karen:
1. Find presentation files
2. Read relevant documents
3. Summarize key topics
4. Generate questions
5. Create a study checklist
6. Set an optional reminder
```

Do not execute external or consequential actions automatically without appropriate confirmation.

---

## 37. Task Queue

Add a local task manager:

```text
Pending
Running
Waiting for user
Completed
Failed
Cancelled
```

Example:

```text
Task:
Prepare study summary

Status:
RUNNING

Progress:
Reading 8/12 documents
```

This makes long-running workflows understandable instead of making Karen appear frozen.

---

## 38. Background Processing

Where Android permits:

- [ ] Process local documents
- [ ] Build embeddings
- [ ] Index files
- [ ] Generate summaries
- [ ] Prepare daily briefing
- [ ] Run scheduled local tasks

Respect Android battery/background execution restrictions.

Never create hidden persistent activity solely to keep the assistant alive.

---

## 39. Offline-First Web Fallback

Use a router:

```text
Can local data answer?
       │
     Yes → Local RAG
       │
      No
       ↓
Is internet available?
       │
     Yes → Web/API
       │
      No
       ↓
Explain that current information is unavailable
```

This prevents unnecessary network requests.

---

## 40. Local Web Server Mode

Optional advanced mode:

```text
PC
┌─────────────────────────┐
│ Larger Karen model      │
│ GPU inference           │
│ RAG                     │
└───────────┬─────────────┘
            │ LAN
            ↓
        Android
┌─────────────────────────┐
│ Karen mobile client     │
│ Voice/UI/Tools          │
└─────────────────────────┘
```

Use this when the PC has a larger model than the phone can comfortably run.

The phone should still have a small offline fallback model.

---

## 41. Hybrid Model Routing

```text
Android
  │
  ├── Offline → Small local model
  │
  ├── PC available → Larger local PC model
  │
  └── Internet available → Optional external model/API
```

The user should control whether external services are allowed.

---

## 42. PC Control

Future companion feature:

```text
Android Karen
      ↓
Secure local connection
      ↓
Karen PC Agent
      ↓
PC actions
```

Possible capabilities:

- [ ] Open PC applications
- [ ] Search PC files
- [ ] Read project files
- [ ] Run approved scripts
- [ ] Start/stop development servers
- [ ] Monitor CPU/GPU/RAM
- [ ] Send files between devices
- [ ] Show PC status on phone

Use authentication and a local allowlist for PC commands.

---

## 43. Developer Agent

Karen can become a project assistant:

```text
Repository
   ↓
Code indexing
   ↓
RAG
   ↓
Qwen
   ↓
Explain / modify / test
```

Features:

- [ ] Understand project structure
- [ ] Search symbols
- [ ] Explain errors
- [ ] Generate patches
- [ ] Review code
- [ ] Generate tests
- [ ] Generate documentation
- [ ] Run tests with confirmation
- [ ] Summarize Git changes
- [ ] Generate commit messages

Never run destructive commands without explicit confirmation.

---

## 44. Visual Desktop / Android UI Agent

Potential future system:

```text
Screenshot
   ↓
Vision model
   ↓
UI element detection
   ↓
Planner
   ↓
Android-supported action
```

Features:

- [ ] Identify visible controls
- [ ] Explain screen
- [ ] Locate text
- [ ] Suggest next action
- [ ] Assist with repetitive workflows

Keep sensitive actions confirmation-based.

---

## 45. Smart Notification Agent

Instead of simply reading notifications:

```text
Notifications
      ↓
Classifier
      ↓
┌─────┼────────┐
Urgent  Normal  Low priority
  ↓      ↓         ↓
Alert  Summary   Hide/quiet
```

User-configurable rules:

```text
Always notify:
- Calls
- Selected contacts

Summarize:
- Group chats
- News

Ignore:
- Promotional notifications
```

---

## 46. Emergency / Safety Shortcuts

Optional emergency features:

- [ ] Emergency contact shortcut
- [ ] Open emergency dialer
- [ ] Share location through user-approved action
- [ ] Quick flashlight
- [ ] Loud alarm
- [ ] Emergency information screen

Emergency actions should be simple, visible, and not dependent on the LLM being available.

---

## 47. Accessibility Mode

Karen can optionally help users interact with the phone through accessible interfaces:

- [ ] Read selected screen content
- [ ] Voice navigation
- [ ] Large text mode
- [ ] Voice feedback
- [ ] Describe UI elements
- [ ] Simplified controls

Use Android accessibility APIs only for legitimate accessibility functionality and with explicit user permission.

---

## 48. Smart Search Index

Create one local search layer:

```text
                    Local Search
                         │
        ┌────────────────┼────────────────┐
        ↓                ↓                ↓
      Files            Notes           Apps
        ↓                ↓                ↓
      PDFs             Memory        Contacts
        └────────────────┼────────────────┘
                         ↓
                    Search results
```

Karen can answer:

```text
"Find the PDF about DBMS normalization."

"Where did I save my TreeVision plan?"

"Show my Karen project files."
```

---

## 49. Model Management

Build a model manager:

```text
Models
├── Karen-4B-Q4
├── Karen-9B-Q4
└── Vision-small
```

Features:

- [ ] Download model
- [ ] Delete model
- [ ] Select active model
- [ ] Check model size
- [ ] Check available storage
- [ ] Benchmark model
- [ ] Automatically choose a suitable model
- [ ] Import local GGUF
- [ ] Model checksum verification

Never download an unknown model without showing its source and size.

---

## 50. Benchmark Dashboard

Measure (all values collected **in-app** via the monitoring layer in Section 12):

```text
Model
RAM usage (PSS / peak PSS)
Tokens/sec (prefill + generation)
Time to first token
Context length
Battery drain (session)
Thermal status (and temperature if available)
Load time
Thread count / backend (CPU vs GPU)
```

Example:

```text
Karen 4B Q4

TTFT:           0.8 sec
Prefill:        42 tok/s
Generation:     18 tok/s
Peak PSS:       5.2 GB
Model file:     2.8 GB
Context:        4096
Thermal:        LIGHT
Battery drop:   3% / 10 min
```

Numbers must be measured on the actual device rather than assumed.  
The built-in “Run Performance Test” (Section 12) populates this dashboard automatically and supports the Phase 0 gate and ongoing validation.

---

## 51. Automatic Model Selection

At startup:

```text
Check RAM
Check storage
Check temperature
Check available accelerator
        ↓
Select suitable model
```

Example:

```text
Low memory → 4B Q4
Normal → 4B/9B Q4
High available memory → larger supported model
```

Always leave enough memory for Android itself.

---

## 52. Reliability System

Every tool should return structured results:

```json
{
  "success": true,
  "message": "YouTube opened successfully."
}
```

Failure:

```json
{
  "success": false,
  "error": "YouTube is not installed."
}
```

Karen must use the actual tool result instead of assuming success.

---

## 53. Offline Recovery

If the model crashes:

```text
Model failure
     ↓
Restart inference engine
     ↓
Retry once
     ↓
Fallback to smaller model
     ↓
Show user-friendly error
```

If memory is low:

```text
Reduce context
      ↓
Unload unused model
      ↓
Use smaller model
```

---

## 54. Security Architecture

Use defense in depth:

```text
LLM
 ↓
Schema validation
 ↓
Tool allowlist
 ↓
Permission check
 ↓
Confirmation
 ↓
Android API
 ↓
Result verification
```

Additional protections:

- [ ] No arbitrary shell commands from the LLM
- [ ] No unrestricted file deletion
- [ ] No hidden messaging
- [ ] No hidden calls
- [ ] No credential extraction
- [ ] No arbitrary network access
- [ ] Audit log for tool actions
- [ ] User-controlled permissions

---

## 55. Karen Dashboard

Main screen:

```text
┌──────────────────────────────┐
│          KAREN               │
│       Good evening           │
├──────────────────────────────┤
│ 🎤 Ask Karen                 │
│                              │
│ Battery       82%            │
│ Storage       91 GB free     │
│ Model         4B Q4           │
│ Mode          Balanced       │
├──────────────────────────────┤
│ Quick Actions                │
│                              │
│ 📞 Call   💬 WhatsApp        │
│ 🌐 Web    📅 Reminder        │
│ 📄 Files  🎵 Music           │
└──────────────────────────────┘
```

---

## 56. Karen Personality Layer

Keep personality separate from model weights.

```text
Base model
    +
System personality
    +
User preferences
    +
Current context
```

Possible personality settings:

- Professional
- Friendly
- Concise
- Technical
- Student
- Voice assistant

This allows personality changes without retraining the model.

---

## 57. Long-Term Development Goal

The final Karen architecture:

```text
                         KAREN
                           │
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
          Voice          Chat          Vision
             │             │             │
             └─────────────┼─────────────┘
                           ↓
                    Agent / Router
                           │
       ┌───────────────────┼───────────────────┐
       ↓                   ↓                   ↓
     Local LLM            RAG                Memory
       │                   │                   │
       └───────────────────┼───────────────────┘
                           ↓
                      Tool Manager
                           │
       ┌──────────┬────────┼────────┬──────────┐
       ↓          ↓        ↓        ↓          ↓
     Phone      Apps     Web      Files      PC
       │          │        │        │          │
       └──────────┴────────┼────────┴──────────┘
                           ↓
                     Verification
                           ↓
                       User / UI
```

The design goal is to make Karen a **modular local agent**, not just a chatbot. The model handles understanding and planning; deterministic Android modules handle actual actions.

## 58. Multimodal Karen

Karen should eventually accept multiple input types:

```text
Text
Voice
Images
Screenshots
Documents
Camera
Files
```

Unified pipeline:

```text
Input
  ↓
Input Router
  ↓
Understand
  ↓
Context Builder
  ↓
Qwen / Vision Model
  ↓
Planner
  ↓
Tool / RAG / Memory
  ↓
Response
```

Features:

- [ ] Voice + image in the same request
- [ ] Screenshot + question
- [ ] PDF + question
- [ ] Camera + voice command
- [ ] Multiple files in one task
- [ ] Drag/drop files into chat
- [ ] Share Android content directly to Karen

---

## 59. Conversation Continuity

Karen should understand references across turns.

Example:

```text
User: Find my DBMS PDF.

Karen: Found it.

User: Summarize chapter 3.

Karen: [summarizes chapter 3]

User: Make that into 10 questions.

Karen: [creates questions]
```

Implement:

- [ ] Conversation state
- [ ] Reference resolution
- [ ] Task state
- [ ] Temporary context
- [ ] Session summaries
- [ ] Long-term memory when explicitly appropriate

---

## 60. Smart Context Management

Large contexts consume memory and can exceed the model's context window. Karen must treat the context window as a finite working-memory budget rather than a place to replay the entire conversation.

Use:

```text
Recent messages
      +
Conversation summary
      +
Relevant memory
      +
Relevant RAG chunks
      +
Required tool schemas
      ↓
Context Budget Manager
      ↓
Context Builder
      ↓
Qwen
```

Features:

- [ ] Automatic conversation summarization
- [ ] RAG top-k selection
- [ ] Duplicate context removal
- [ ] Context compression
- [ ] Configurable maximum context
- [ ] Automatic old-message trimming
- [ ] Token counting before inference
- [ ] Reserved output-token budget
- [ ] Dynamic memory token budget
- [ ] Dynamic RAG token budget
- [ ] Dynamic tool-schema selection

### 60.1 Context Overflow & Recovery Loop

If the assembled prompt exceeds the safe context budget, Karen must **recover and rebuild the context instead of stopping**. The current user request and critical system/policy instructions have the highest protection priority.

```text
Context Builder
      ↓
Token Counter
      ↓
Within safe budget?
   ┌──────┴──────┐
  YES            NO
   ↓              ↓
  Qwen      Overflow Manager
                  ↓
          Compress old messages
                  ↓
          Remove duplicate context
                  ↓
          Summarize older turns
                  ↓
          Reduce RAG top-k/chunks
                  ↓
          Reduce low-priority memory
                  ↓
          Remove unnecessary tool schemas
                  ↓
          Rebuild context
                  ↓
             Token Counter
                  │
            Still too large?
             ┌────┴────┐
            YES       NO
             ↓         ↓
          Recovery   Qwen
          iteration
```

The loop continues until the context fits the configured budget or a final safe fallback summary is produced. It must never endlessly loop.

### 60.2 Context Priority Tiers

When space is limited, preserve information in this order:

```text
P0  System / safety / policy instructions
P1  Current user request + required task state
P2  Active tool result / critical execution state
P3  Relevant recent conversation
P4  Relevant long-term memory
P5  Relevant RAG evidence
P6  Older conversation / low-value context
P7  Redundant or stale context
```

P0–P2 should not be discarded by normal overflow trimming. P4/P5 may be reduced through retrieval and reranking. P6/P7 are the primary compression targets.

### 60.3 Input + Output Budgeting

Karen must reserve room for the model's response instead of filling the entire context window with input.

```text
Model context limit
      − system/policy budget
      − current request budget
      − active task/tool state
      − reserved output budget
      = available retrieval/context budget
```

Example for a 16K context model:

```text
16K total
├── System / policy       1K
├── User request           1K
├── Task state             1K
├── Memory                 2K
├── RAG                    2K
├── Recent conversation    4K
└── Output reservation     5K
```

These are configurable budgets, not fixed requirements. Runtime measurements should determine safe values for the selected model and device.

### 60.4 Recovery Guardrails

- [ ] Maximum context-recovery iterations
- [ ] Minimum useful context threshold
- [ ] Never trim the active user request
- [ ] Never trim critical safety/policy instructions
- [ ] Never discard required tool results silently
- [ ] Detect when no further compression is possible
- [ ] Fallback to a compact task summary
- [ ] Log overflow/recovery events for diagnostics
- [ ] Expose context usage in Benchmark / Diagnostics
- [ ] Cancel cleanly if a safe context cannot be constructed

### 60.5 Context Cache and Reuse

Avoid repeatedly processing unchanged prefixes where supported by the inference backend. Cache stable system instructions and tool-schema prefixes, then process only the changing conversation/retrieval portion. Invalidate the cache when system instructions, selected tools, model, or relevant policy state changes.

### 60.6 Long-Task Progress Continuation

Context recovery must preserve **task progress**, not just conversation text. Long-running tasks use a persistent state machine/task graph:

```text
Task State
   ↓
Select next pending step
   ↓
Execute
   ↓
Capture result
   ↓
Verify
   ↓
Update persistent progress
   ↓
Context overflow?
   ├── NO → continue
   └── YES → compress/rebuild context
                    ↓
                 resume task
                    ↓
              select next step
```

After a context rebuild, Karen should reconstruct the working context from the task state, completed steps, dependencies, decisions, errors, and relevant memory/RAG—not from the full historical transcript.

A task is complete only after its completion gate passes. Failed steps should enter diagnosis/re-planning rather than being silently skipped.

---

## 61. Personal Automation Builder

Allow the user to create routines using natural language.

Example:

```text
"When I say study mode,
turn on Do Not Disturb,
open my notes,
start a 50-minute timer,
and open my study playlist."
```

Karen converts this into:

```text
Trigger
 ↓
Action 1
 ↓
Action 2
 ↓
Action 3
 ↓
Action 4
```

Provide a visual automation editor:

```text
WHEN
  ↓
IF
  ↓
THEN
  ↓
AND
  ↓
FINISH
```

Include:

- [ ] Manual trigger
- [ ] Time trigger
- [ ] Calendar trigger
- [ ] Device-state trigger
- [ ] App-open trigger where supported
- [ ] Network-state trigger
- [ ] Battery threshold trigger
- [ ] Location-based trigger where supported
- [ ] Confirmation step
- [ ] Enable/disable automation

---

## 62. Routine Templates

Prebuilt routines:

### Study

```text
Do Not Disturb
→ Open notes
→ Start timer
→ Start playlist
```

### Work

```text
Open calendar
→ Show tasks
→ Open required apps
→ Start focus timer
```

### Leaving Home

```text
Open Maps
→ Show route
→ Read weather
→ Show battery
```

### Bedtime

```text
Set alarm
→ Enable bedtime settings
→ Reduce notifications
```

All routines should respect Android restrictions and user permissions.

---

## 63. Secure Device Pairing

For PC ↔ Android communication:

```text
Android
   ↓
Pairing QR
   ↓
PC scans
   ↓
Mutual authentication
   ↓
Encrypted local connection
```

Features:

- [ ] QR pairing
- [ ] Device names
- [ ] Trusted-device list
- [ ] Revoke device
- [ ] Encryption
- [ ] Local-network discovery
- [ ] Manual IP connection
- [ ] Connection status
- [ ] Permission scopes

Example permission scopes:

```text
READ_FILES
CONTROL_MEDIA
RUN_DEV_TOOLS
VIEW_SYSTEM_STATUS
TRANSFER_FILES
```

Do not give a paired PC unrestricted access by default.

---

## 64. Cross-Device Clipboard

Optional local feature:

```text
Phone
  ↕
Encrypted LAN
  ↕
PC
```

Capabilities:

- [ ] Copy phone → PC
- [ ] Copy PC → phone
- [ ] Share selected text
- [ ] Share URLs
- [ ] Share images
- [ ] Clipboard history
- [ ] Disable synchronization

---

## 65. Cross-Device File Transfer

Simple workflow:

```text
"Send this PDF to my laptop."
```

Karen:

```text
Select file
 ↓
Select trusted device
 ↓
Show filename + size
 ↓
Confirm
 ↓
Encrypted transfer
```

Features:

- [ ] File transfer
- [ ] Folder transfer
- [ ] Transfer progress
- [ ] Resume interrupted transfers
- [ ] Checksum verification
- [ ] Transfer history
- [ ] Cancel transfer

---

## 66. Local Home / IoT Integration

Future optional module:

```text
Karen
  ↓
Local IoT Gateway
  ↓
ESP32 / Home Assistant / compatible devices
```

Possible controls:

- [ ] Lights
- [ ] Fans
- [ ] Smart plugs
- [ ] Sensors
- [ ] Temperature
- [ ] Door/status sensors

Keep IoT actions behind authentication and explicit permissions.

---

## 67. Hardware Sensor Awareness

Where Android exposes the required APIs:

- [ ] Accelerometer
- [ ] Gyroscope
- [ ] Proximity
- [ ] Light sensor
- [ ] Battery information
- [ ] Device orientation

Potential applications:

```text
"Is my phone upside down?"
"Is the phone charging?"
"Is the screen currently active?"
```

Avoid continuous sensor collection unless the user explicitly enables it.

---

## 68. Smart Profiles

Karen can provide profiles such as:

```text
Normal
Study
Gaming
Driving
Sleep
Work
Private
Offline
```

Example:

```text
Gaming Mode
  ↓
Mute unnecessary notifications
  ↓
Open game launcher
  ↓
Keep Karen lightweight
```

Profile transitions should be visible and user-controlled.

---

## 69. Gaming Assistant

Optional gaming-specific module:

- [ ] Game launch shortcuts
- [ ] Game timer
- [ ] Performance monitoring
- [ ] Battery/temperature display
- [ ] Gaming profile
- [ ] Voice notes
- [ ] Screenshot organization
- [ ] Game-related local notes

Do not provide prohibited cheating or unauthorized game automation.

---

## 70. Study Copilot

Expanded student workflow:

```text
PDF
 ↓
Extract
 ↓
RAG
 ↓
Study plan
 ↓
Questions
 ↓
Quiz
 ↓
Weak-topic detection
 ↓
Revision plan
```

Features:

- [ ] Flashcards
- [ ] MCQs
- [ ] Short-answer questions
- [ ] 2-mark / 5-mark / 16-mark formats
- [ ] Exam revision mode
- [ ] Topic difficulty tracking
- [ ] Spaced repetition
- [ ] Voice explanations
- [ ] Diagram explanation
- [ ] Code practice

---

## 71. Project Copilot

Karen can maintain project context:

```text
Project
├── Requirements
├── Architecture
├── Dataset
├── Source code
├── Documentation
├── TODO
├── Bugs
└── Releases
```

Features:

- [ ] Generate project plan
- [ ] Track TODOs
- [ ] Explain architecture
- [ ] Generate documentation
- [ ] Analyze logs
- [ ] Create test cases
- [ ] Prepare presentation content
- [ ] Generate README
- [ ] Maintain changelog

---

## 72. Local Email / Calendar Integration

If supported by the user's selected provider/app:

- [ ] Search emails
- [ ] Summarize selected emails
- [ ] Draft replies
- [ ] Search calendar
- [ ] Create events
- [ ] Check schedule conflicts
- [ ] Prepare meeting summaries

Sensitive actions should require explicit confirmation.

---

## 73. Smart Contact Intelligence

With permission:

```text
Contact
 ├── Name
 ├── Phone
 ├── Preferred app
 ├── Recent approved interactions
 └── User-defined relationship label
```

Karen can resolve:

```text
"Call Arun."

"WhatsApp Arun."

"Send the same message to Arun."
```

Do not infer sensitive personal attributes about contacts.

---

## 74. Action Preview

Before executing complex operations, show:

```text
Karen wants to:

1. Open WhatsApp
2. Select Arun
3. Prepare message
4. Ask you to confirm sending

[Cancel] [Continue]
```

This makes automation transparent.

---

## 75. Undo / Recovery

Where technically possible:

```text
Action
 ↓
Result
 ↓
Undo available
```

Examples:

- [ ] Cancel scheduled reminder
- [ ] Undo local file rename
- [ ] Revert local settings changed by Karen
- [ ] Cancel running task
- [ ] Stop automation

For external actions that cannot be undone, show a confirmation before execution.

---

## 76. Audit Log

Store a local action history:

```text
Time
Action
Tool
Result
User confirmation
```

Example:

```text
18:42
open_app
WhatsApp
Success

18:44
send_message
Arun
Confirmed by user
Success
```

Allow:

- [ ] View logs
- [ ] Filter logs
- [ ] Delete logs
- [ ] Disable logging
- [ ] Export logs

---

## 77. Error Intelligence

Instead of:

```text
Error.
```

Karen should explain:

```text
I couldn't open WhatsApp because it isn't installed.

Available alternatives:
• Open the browser
• Send an SMS
• Cancel
```

Features:

- [ ] Human-readable errors
- [ ] Suggested recovery
- [ ] Automatic safe retry
- [ ] Fallback tool
- [ ] Offline fallback
- [ ] Error logging

---

## 78. Self-Diagnostics

Add a diagnostic screen:

```text
Karen Health

LLM             ✓
Tokenizer       ✓
RAG             ✓
Memory DB       ✓
Tool Manager    ✓
Microphone      ✓
TTS             ✓
Storage         ✓
Permissions     ⚠
Network         ✓
```

Actions:

- [ ] Run diagnostics
- [ ] Test model
- [ ] Test microphone
- [ ] Test TTS
- [ ] Test RAG
- [ ] Test tools
- [ ] Check storage
- [ ] Check permissions

---

## 79. Crash Recovery

If a component crashes:

```text
Component failure
      ↓
Record error
      ↓
Restart component
      ↓
Retry safely
      ↓
Fallback if needed
      ↓
Notify user
```

The entire application should not need to restart because one optional module failed.

---

## 80. Battery-Aware Intelligence

Karen should reduce resource usage when the battery is low.

```text
Battery > 50%
→ Normal

Battery 20–50%
→ Reduce background work

Battery < 20%
→ Battery saver
→ Smaller model
→ Disable optional background processing
```

User can override these settings.

---

## 81. Thermal-Aware Inference

Monitor available Android thermal information where supported.

```text
Normal
 ↓
Warm
 ↓
Hot
 ↓
Reduce inference load
 ↓
Pause background processing
```

Never continuously benchmark or stress the device unnecessarily.

---

## 82. Privacy Zones

Allow users to define protected areas of data:

```text
Private Files
Private Conversations
Private Contacts
Private Projects
```

Rules:

```text
Never send to external API
Never include in optional cloud search
Never expose through PC companion
```

The exact implementation should use strong local access controls rather than relying only on prompts.

---

## 83. Data Import / Export

Support controlled migration:

```text
Export
 ├── Settings
 ├── Memory
 ├── Routines
 ├── Conversations
 └── Local indexes
```

Features:

- [ ] Encrypted backup
- [ ] Restore
- [ ] Selective export
- [ ] Selective import
- [ ] Versioned backup
- [ ] Backup verification

---

## 84. Plugin Marketplace Architecture

Future architecture:

```text
Karen Core
    │
    ├── Official tools
    └── Optional plugins
```

Plugin manifest:

```json
{
  "name": "Example Tool",
  "version": "1.0",
  "permissions": [
    "NETWORK"
  ]
}
```

Before installing:

- [ ] Show developer/source
- [ ] Show permissions
- [ ] Show package size
- [ ] Show capabilities
- [ ] Allow install/uninstall
- [ ] Revoke plugin permissions

Never allow arbitrary plugins to receive unrestricted device access.

---

## 85. Natural-Language Command Builder

Users should not need to know tool names.

Instead of:

```text
open_app("com.example.app")
```

They say:

```text
"Open YouTube."
```

Karen maps the request to a validated tool.

For unsupported requests:

```text
"I don't have permission or a tool for that yet."
```

---

## 86. Karen Developer Console

Optional hidden/advanced screen:

```text
Model
Tools
Memory
RAG
Logs
Performance
Permissions
Network
Devices
Automations
```

Useful for debugging the project during development.

---

## 87. Feature Flags

Use feature flags:

```text
VOICE_ENABLED
VISION_ENABLED
PC_CONTROL_ENABLED
WEB_ENABLED
WHATSAPP_ENABLED
AUTOMATION_ENABLED
IOT_ENABLED
WAKE_WORD_ENABLED
```

This lets development continue without enabling unfinished features.

---

## 88. Production Release Channels

Maintain:

```text
Development
↓
Alpha
↓
Beta
↓
Stable
```

Use separate configurations for development and production.

---

## 89. Final Long-Term Karen Vision

Karen becomes a local-first personal computing layer:

```text
                 ┌───────────────────┐
                 │      KAREN        │
                 │ Personal AI Layer │
                 └─────────┬─────────┘
                           │
       ┌───────────────────┼───────────────────┐
       ↓                   ↓                   ↓
   Understand           Remember            Plan
       │                   │                   │
       └───────────────────┼───────────────────┘
                           ↓
                      Execute safely
                           │
       ┌─────────┬─────────┼─────────┬─────────┐
       ↓         ↓         ↓         ↓         ↓
     Phone      Apps      Web       PC       IoT
       │         │         │         │         │
       └─────────┴─────────┼─────────┴─────────┘
                           ↓
                      Verify result
                           ↓
                         User
```

The core principle remains:

**LLM = understand, reason, and plan.**

**Deterministic software = permissions, execution, validation, and safety.**

This separation makes Karen easier to debug, safer to extend, and much easier to migrate between models.

## 90. Mobile Model & Storage Manager

Karen should keep the Android application separate from large AI model files.

### App vs Model

Do NOT package large GGUF models inside the APK.

Recommended structure:

```text
Karen.apk
├── App UI
├── LLM runtime
├── Tool manager
├── RAG engine
├── Memory system
└── Model manager

Phone Storage
└── Karen/
    ├── models/
    │   ├── qwen-4b-q4.gguf
    │   ├── qwen-9b-q4.gguf
    │   └── vision-model.gguf
    ├── memory/
    ├── documents/
    ├── embeddings/
    ├── cache/
    └── backups/
```

### Initial Target Storage

Approximate starting targets:

```text
Karen APK             ~50–200 MB
Qwen 4B Q4            ~2.5–3.5 GB
STT model             ~100–500 MB
TTS model             ~100–300 MB
RAG / database        Variable
Cache                 ~0.5–2 GB
```

Exact sizes depend on the selected models and runtime.

Target **8–12 GB or more of free storage** for a comfortable first installation.

### Model Manager

Create a dedicated model-management screen:

```text
┌──────────────────────────────┐
│       Karen Models           │
├──────────────────────────────┤
│ Qwen 4B Q4                   │
│ 2.9 GB • Installed           │
│ [Use] [Delete]               │
├──────────────────────────────┤
│ Qwen 9B Q4                   │
│ 5.8 GB • Not installed      │
│ [Download]                   │
├──────────────────────────────┤
│ Vision Model                 │
│ 800 MB • Not installed      │
│ [Download]                   │
└──────────────────────────────┘
```

Features:

- [ ] Browse available models
- [ ] Download model
- [ ] Pause/resume download
- [ ] Verify checksum
- [ ] Verify available storage
- [ ] Install model
- [ ] Select active model
- [ ] Switch between models
- [ ] Delete model
- [ ] Show model size
- [ ] Show quantization
- [ ] Show supported capabilities
- [ ] Show download progress
- [ ] Detect corrupted/incomplete downloads
- [ ] Keep multiple models when storage permits

### Model Lifecycle

A model should normally be downloaded once and reused.

```text
Model Store
    ↓
Download
    ↓
Checksum verification
    ↓
Install
    ↓
Stored on disk
    ↓
Load into RAM when needed
    ↓
Inference
    ↓
Unload from RAM
    ↓
Keep model on storage
```

Important:

**Unloading a model from RAM does NOT delete the model file.**

This allows Karen to save RAM while keeping the model available for the next session.

### Automatic Model Selection

Karen should check:

```text
Available storage
Available RAM
Current thermal state
Battery level
Model requirements
```

Then recommend a model.

Example:

```text
12 GB RAM
8 GB free storage
Normal temperature
       ↓
Qwen 4B Q4
       ↓
Recommended
```

If the phone is under memory pressure:

```text
9B active
   ↓
Memory pressure
   ↓
Unload 9B
   ↓
Load 4B
```

### Storage-Saving Mode

Add:

```text
Storage Saver
```

Possible behavior:

- [ ] Keep only one main LLM
- [ ] Remove unused model caches
- [ ] Remove old temporary files
- [ ] Keep user documents
- [ ] Show what will be deleted before cleanup
- [ ] Never automatically delete user documents

### Optional Model Store

Future Karen versions can provide an in-app model catalog.

Each model entry should show:

```text
Model name
Parameter count
Quantization
File size
Required RAM
Supported features
License/source
Recommended device class
```

Example:

```text
Qwen 4B Q4

Size:       ~3 GB
RAM target: ~5–7 GB+
Type:       GGUF
Purpose:    General assistant
Status:     Installed
```

The exact RAM requirement must be measured for the selected runtime and context size rather than inferred only from file size.

### Model Download Security

Before installing a model:

- [ ] Show source
- [ ] Show model name
- [ ] Show file size
- [ ] Verify checksum
- [ ] Verify expected file format
- [ ] Reject incomplete downloads
- [ ] Avoid executing model files as programs
- [ ] Allow the user to cancel/delete the download

### Model Backup

Allow the user to optionally back up:

```text
Model
LoRA adapter
Karen settings
Memory
Routines
```

Large model files should be treated separately from normal app backups.

### Offline Availability

After a model is installed:

```text
Internet OFF
     ↓
Karen Model Manager
     ↓
Local GGUF found
     ↓
Load model
     ↓
Offline Karen
```

No repeated download should be required unless the user deleted the model or an update is requested.

### PC Model Transfer

Allow optional local transfer:

```text
PC
 ↓
Wi-Fi/LAN
 ↓
Karen Model Manager
 ↓
Phone storage
```

Useful when the model has already been downloaded on the PC.

Show:

```text
Transfer progress
File size
Transfer speed
Checksum
Remaining time
```

### Model Versioning

Store metadata:

```json
{
  "name": "Qwen 4B",
  "quantization": "Q4_K_M",
  "version": "1.0",
  "size_bytes": 0,
  "sha256": "...",
  "source": "...",
  "installed_at": "..."
}
```

This prevents Karen from confusing different model files.

---

## 23. Canonical Model Storage, Download & Distribution

Karen separates the **application**, **model files**, and **user data**.

### 23.1 Where models live

```text
Public / remote model source
        ↓
Model Catalog
        ↓
Karen Model Manager
        ↓
Phone local storage
        ↓
Load selected GGUF into RAM
        ↓
Inference
        ↓
Unload from RAM when idle
        ↓
GGUF remains stored on disk
```

The model file is **not bundled into the APK**.

Recommended public distribution split:

```text
GitHub
├── Android source
├── Training code
├── Inference/runtime code
├── Tool schemas
├── RAG code
├── Documentation
└── Releases

Hugging Face Hub
├── Karen-4B
├── Karen-9B
├── Karen-LoRA
├── Karen-Vision
└── Karen-Datasets
```

Future scale can optionally add object storage/CDN.

### 23.2 Model catalog

The Android app should use a small catalog rather than hard-coded model URLs.

```json
{
  "models": [
    {
      "id": "karen-4b-q4",
      "name": "Karen 4B Q4",
      "format": "GGUF",
      "quantization": "Q4_K_M",
      "version": "1.0.0",
      "size_bytes": 0,
      "ram_target": "device-dependent",
      "source": "huggingface",
      "download_url": "...",
      "sha256": "..."
    }
  ]
}
```

### 23.3 Model Manager

Features:

- Browse available models
- Show parameter count, quantization, size, RAM target, capabilities and license/source
- Download
- Pause/resume/retry/cancel
- Resume after interruption
- Verify SHA-256
- Verify format and completeness
- Install/register only after verification
- Select active model
- Switch models
- Delete models
- Import a local GGUF
- Benchmark models
- Keep multiple models when storage permits
- Storage-saver mode
- Automatic model recommendation based on RAM/storage/thermal/battery state

### 23.4 Phone storage layout

```text
Karen/
├── models/
│   ├── qwen-4b-q4.gguf
│   ├── qwen-9b-q4.gguf
│   └── vision/
├── memory/
├── documents/
├── embeddings/
├── cache/
└── backups/
```

Model storage is separate from application resources.

### 23.5 PC → phone transfer

For development:

```text
PC
 ↓
USB / Wi-Fi / LAN
 ↓
Karen Model Manager
 ↓
Checksum verification
 ↓
Phone model storage
```

This avoids repeatedly downloading multi-GB files.

### 23.6 Model lifecycle

```text
Download
  ↓
Temporary/incomplete file
  ↓
Checksum + format verification
  ↓
Atomic install
  ↓
Registered model
  ↓
Load into RAM
  ↓
Inference
  ↓
Unload from RAM
  ↓
Keep file on storage
```

A partially downloaded or failed-checksum file must never be registered as installed.

### 23.7 Version updates

Keep the existing working model until the replacement passes:

1. Download
2. Checksum verification
3. Format validation
4. Smoke test
5. Registration

Then switch the active version.

### 23.8 Licensing

Before publishing or redistributing any model or derivative:

- Check the exact base-model license
- Check redistribution requirements
- Check attribution/notice requirements
- Document base model
- Document fine-tuning method
- Document quantization
- Check teacher-model terms if synthetic training data was generated

### 23.9 Master-copy rule

```text
Original Safetensors
       ↓
QLoRA
       ↓
Merged BF16 master
       ↓
GGUF conversion
       ↓
Q4/Q5/etc.
       ↓
Android
```

Never use a deployed GGUF as the training master.

---

## 24. Canonical Coding-Agent / Project Execution System

Karen's coding-agent capability uses the optimized planning engine as the canonical design.

### 24.1 End-to-end workflow

```text
USER REQUEST
    ↓
UNDERSTAND
    ↓
INVESTIGATE PROJECT
    ↓
EXTRACT REQUIREMENTS
    ↓
DECOMPOSE
    ↓
DEPENDENCY ANALYSIS
    ↓
ARCHITECTURE
    ↓
PLAN
    ↓
PLAN VALIDATION
    ↓
EXECUTE
    ↓
OBSERVE
    ↓
VERIFY
    ↓
ADAPT / RE-PLAN
    ↓
COMPLETE
```

### 24.2 Project creation

For a new project, Karen can:

1. Determine requirements
2. Choose an appropriate architecture
3. Generate a project/file tree
4. Create directories and files
5. Populate initial source code
6. Install or declare dependencies
7. Run build/tests in the available execution environment
8. Capture errors
9. Repair and re-test
10. Produce a final project report

Example:

```text
USER:
"Create a React Native movie recommendation app."

Karen:
→ requirements
→ architecture
→ file structure
→ create files
→ install dependencies
→ implement
→ run checks
→ fix errors
→ verify
→ report
```

### 24.3 Sandbox / terminal

The coding agent should associate every command and file operation with a task:

```text
PLAN
 ↓
SELECT TASK
 ↓
EDIT FILES
 ↓
RUN COMMAND
 ↓
CAPTURE OUTPUT
 ↓
ANALYZE
 ↓
VERIFY
 ↓
UPDATE TASK
 ↓
NEXT TASK
```

The execution environment may be on-device or, in V3, a paired PC companion.

### 24.4 Project state

Persist:

- Current plan/version
- Phase
- Current task
- Completed/pending/failed tasks
- Dependencies
- Decisions
- Changed files
- Commands
- Test results
- Known issues
- Verification results

This allows a long project to resume after interruption.

### 24.5 Autonomy modes

**Manual**
- Plan only
- User approves each task

**Guided**
- Agent executes routine work
- Confirmation before major changes

**Autonomous**
- Agent plans, executes, tests, verifies and re-plans within configured boundaries

All modes retain user visibility and cancellation controls.

### 24.6 Completion gate

Karen must not declare a project complete merely because code was generated.

Completion requires, where applicable:

- Requirements implemented
- Required files present
- Dependencies valid
- Build successful
- Tests passing
- Integration verified
- No blocking errors
- Final requirements check passed

---

## 25. Canonical Feature Roadmap

### V1 — Core offline assistant

- Local text chat
- Streaming generation
- Qwen 4B Q4 model
- Tool calling
- Tool validation
- Risk tiers and confirmations
- Audit log
- Local RAG
- Encrypted memory
- Push-to-talk voice
- Model Manager
- Thermal/RAM safeguards
- Offline operation
- Basic local file/document tools
- Smart context budgeting and overflow/recovery loop
- Selective memory retrieval and memory scoring
- Persistent long-task progress state

### V2 — Personal productivity

- Notification summaries
- OCR and basic vision
- Calendar/email read integration
- Maps/navigation intents
- Improved media control
- Simple routines
- Conversation modes
- Smart local search
- Benchmark/self-diagnostics
- Crash recovery and long-task resume
- Advanced context diagnostics and recovery telemetry
- Memory consolidation and stale-memory management

### V3 — Coding agent and ecosystem

- Claude-style planning/workflow engine
- Project creation and file-structure generation
- Sandbox/terminal execution
- Codebase indexing and developer agent
- Project/study copilots
- PC companion
- Secure PC control
- Model routing
- Wake word
- Tamil/advanced multilingual voice
- Screen understanding
- Accessibility-based UI agent after security review
- Local PC server mode
- Knowledge graph
- Home/IoT
- Gaming assistant
- Plugin architecture
- Feature flags and release channels

### Later / restricted

- Third-party plugin marketplace
- Silent message sending
- Payments
- Fully autonomous unrestricted background agents

These remain outside the core design because they introduce substantially higher security, privacy, policy, or reliability requirements.

---

## 26. Final Canonical Architecture

```text
                         KAREN
                           │
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
          Chat/Voice     Memory         RAG
             │             │             │
             └─────────────┼─────────────┘
                           ↓
                    Context Budget Manager
                           ↓
                    Context Builder
                           ↓
                      Tool Router
                           ↓
                    Local Qwen Model
                           ↓
                 Structured Tool Call
                           ↓
              Schema / Argument Validator
                           ↓
                     Policy Engine
                           ↓
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
        Android Tools   Coding Tools   RAG/Memory
             │             │             │
             ↓             ↓             ↓
          Real Result   Sandbox Result  Data
             └─────────────┼─────────────┘
                           ↓
                    Verification
                           ↓
                   Final Response
                           ↓
                 Audit / Project State
```

### Core principle

**Model proposes. Application validates. Tools execute. Results establish truth. Verification establishes completion.**

This is the canonical merged architecture for Karen.



# 91. Local Model Import, Export & Portable Model Management

Karen should allow users to manage AI models directly from Android local storage without requiring a remote download.

## 91.1 Local Model Import

Users should be able to import supported model files from local device storage.

Supported workflow:

```text
Android File Picker
        ↓
Select Model File
        ↓
Karen Model Importer
        ↓
Validate File
        ↓
Detect Format / Quantization
        ↓
Verify Integrity
        ↓
Check Available Storage
        ↓
Register Model
        ↓
Karen Model Store
        ↓
Available for Selection
```

Features:

- [ ] Import model from Android local storage
- [ ] Use Android Storage Access Framework / system file picker
- [ ] Support supported GGUF models
- [ ] Detect model metadata
- [ ] Detect parameter size where available
- [ ] Detect quantization
- [ ] Detect architecture/model family where possible
- [ ] Show model file size
- [ ] Check available storage before copying
- [ ] Verify file integrity
- [ ] Generate/store SHA-256 checksum
- [ ] Reject incomplete or unsupported files
- [ ] Allow the user to cancel import
- [ ] Prevent accidental duplicate imports
- [ ] Register imported models in Model Manager
- [ ] Allow imported models to be selected as the active model

Example:

```text
Phone Storage
└── Download/
    └── my-model-q4.gguf

        ↓ Import

Karen/
└── models/
    └── imported/
        └── my-model-q4.gguf
```

Karen should copy or securely reference the selected model according to Android storage permissions and the selected import mode. The original user file must not be silently deleted.

## 91.2 Model Import Validation

Before registering a model:

```text
Selected file
    ↓
Extension / format check
    ↓
GGUF/header validation
    ↓
Model metadata extraction
    ↓
Checksum
    ↓
Storage check
    ↓
Compatibility check
    ↓
Register
```

Karen should display a clear error when a model cannot be used:

```text
Model Import Failed

Reason:
Unsupported model format.

Supported:
GGUF
```

or:

```text
Model Import Failed

Reason:
Insufficient storage.

Required: 3.1 GB
Available: 1.8 GB
```

## 91.3 Portable Model Export

Karen should support exporting installed models to user-selected local storage.

```text
Karen Model Manager
        ↓
Select Model
        ↓
Export
        ↓
Android File Picker
        ↓
Copy Model
        ↓
Verify Export
        ↓
Export Complete
```

Features:

- [ ] Export installed model
- [ ] Choose destination using Android system file picker
- [ ] Preserve original model file
- [ ] Show export progress
- [ ] Allow cancellation
- [ ] Verify exported file
- [ ] Compare checksum after export
- [ ] Show exported file size
- [ ] Never delete the installed model automatically
- [ ] Support moving/copying models between phones manually

Example:

```text
Karen/
└── models/
    └── karen-4b-q4.gguf

        ↓ Export

User-selected storage
└── Backup/
    └── karen-4b-q4.gguf
```

## 91.4 Model Metadata Export

Alongside the model, Karen should optionally export a small metadata file.

Example:

```json
{
  "model_id": "karen-4b-q4",
  "name": "Karen 4B Q4",
  "format": "GGUF",
  "quantization": "Q4_K_M",
  "version": "1.0.0",
  "size_bytes": 0,
  "sha256": "...",
  "exported_at": "...",
  "source": "local-import"
}
```

This allows another Karen installation to identify and verify the model more easily.

---

# 92. Local Memory Export & Import

Karen should allow users to export and restore their personal AI memory.

Memory is user-owned data and should remain under explicit user control.

## 92.1 Memory Export

```text
Karen Memory
      ↓
Export Memory
      ↓
Create Memory Package
      ↓
Optional Encryption
      ↓
Android File Picker
      ↓
Save to Local Storage
```

Features:

- [ ] Export approved memories
- [ ] Export memory metadata
- [ ] Export memory categories
- [ ] Export memory timestamps/version information
- [ ] Export memory embeddings/index information when compatible
- [ ] Create a portable memory package
- [ ] Optional encrypted export
- [ ] User chooses destination
- [ ] Show export progress
- [ ] Verify exported package
- [ ] Never silently upload memory to a server

Example:

```text
User Storage
└── Karen_Backups/
    └── memory/
        ├── memory.json
        ├── metadata.json
        └── manifest.json
```

## 92.2 Memory Import

```text
Android File Picker
        ↓
Select Karen Memory Backup
        ↓
Validate Package
        ↓
Verify Integrity
        ↓
Show Import Preview
        ↓
User Confirmation
        ↓
Import Memory
        ↓
Rebuild Index if Required
```

Features:

- [ ] Import previously exported memory
- [ ] Validate package version
- [ ] Validate schema
- [ ] Verify checksum
- [ ] Detect corrupted backups
- [ ] Detect duplicate memories
- [ ] Preview memories before importing
- [ ] Allow selective import
- [ ] Merge with existing memory
- [ ] Replace existing memory only after explicit confirmation
- [ ] Rebuild embeddings/index when required
- [ ] Preserve memory provenance/version information

Karen must not overwrite existing memory silently.

---

# 93. Portable Karen Backup Package

Provide a unified local backup format for important Karen data.

Possible package:

```text
Karen_Backup/
├── manifest.json
├── settings/
│   └── settings.json
├── memory/
│   ├── memory.json
│   └── metadata.json
├── routines/
│   └── routines.json
├── conversations/
│   └── conversations.json
├── tools/
│   └── tool-settings.json
├── models/
│   └── model-manifest.json
└── checksums/
    └── sha256.json
```

The backup should not necessarily contain the large model binary by default.

Instead:

```text
Backup Package
      ├── Karen settings
      ├── Memory
      ├── Routines
      ├── Conversations
      ├── Model metadata
      └── Checksums

Large Model
      └── Export separately when requested
```

This keeps normal backups small while still allowing complete offline migration.

## 93.1 Backup Modes

### Quick Backup

Exports:

- Settings
- Memory
- Routines
- Metadata

### Full Backup

Exports:

- Settings
- Memory
- Routines
- Conversations
- Local indexes where supported
- Model metadata

### Complete Offline Copy

Exports:

- Full backup package
- Selected model binaries
- Required model metadata
- Checksums

This can be used to migrate Karen to another Android device.

---

# 94. Local Storage / File Manager Integration

Karen should provide a dedicated storage-management interface.

Example:

```text
Karen Storage

Models              6.4 GB
Memory              120 MB
Documents           2.1 GB
Embeddings          450 MB
Cache               800 MB
Backups             3.2 GB
────────────────────────
Total               13.07 GB
```

Features:

- [ ] View Karen storage usage
- [ ] Open model manager
- [ ] Import model
- [ ] Export model
- [ ] Export memory
- [ ] Import memory
- [ ] Create backup
- [ ] Restore backup
- [ ] Delete temporary cache
- [ ] Show available device storage
- [ ] Warn before large operations
- [ ] Never delete user documents automatically

---

# 95. Offline Device-to-Device Migration

Karen should support future offline migration between Android devices.

Possible workflow:

```text
Old Phone
   ↓
Create Karen Backup
   ↓
Export to Local Storage / USB / SD / PC
   ↓
Transfer Files
   ↓
New Phone
   ↓
Karen
   ↓
Import Backup
   ↓
Restore Settings + Memory + Routines
   ↓
Import Model
   ↓
Verify
   ↓
Continue Using Karen
```

No cloud account should be required for this migration.

---

# 96. Model & Memory Ownership / Privacy Rules

The following rules are mandatory:

1. User-imported models remain user-controlled files.
2. Karen must not silently upload imported models.
3. Karen must not silently upload memory.
4. Model export must require an explicit user action.
5. Memory export must require an explicit user action.
6. Memory import must show what will be changed before applying it.
7. Existing memory must never be silently overwritten.
8. Model deletion must require explicit confirmation.
9. User documents must not be included in backups unless explicitly selected.
10. Backup/export operations should report exactly what data is included.
11. Sensitive memory backups should support optional encryption.
12. Checksums should be used to detect corruption or incomplete transfers.

---

# 97. Updated Model Lifecycle

The complete model lifecycle becomes:

```text
                 ┌──────────────────────┐
                 │ Remote Model Source  │
                 └──────────┬───────────┘
                            │
                            ↓
                       Download
                            │
                            ↓
                 ┌──────────────────────┐
                 │                      │
                 │   Karen Model Store  │
                 │                      │
                 └──────────┬───────────┘
                            │
                ┌───────────┴───────────┐
                ↓                       ↓
         Local Import              Remote Download
                │                       │
                └───────────┬───────────┘
                            ↓
                       Validate
                            ↓
                     Register Model
                            ↓
                      Stored on Disk
                            ↓
                     Load into RAM
                            ↓
                       Inference
                            ↓
                    Unload from RAM
                            ↓
                    Keep on Storage
                            │
                            ↓
                         Export
                            ↓
                 User-selected storage
```

The important distinction remains:

**Model storage on disk and model residency in RAM are separate.**

Unloading the model from RAM must not delete the model from local storage.

---

# 98. Updated Memory Lifecycle

```text
User-approved Memory
        ↓
Local Memory Store
        ↓
Index / Embeddings
        ↓
Context Retrieval
        ↓
LLM
        ↓
Updated Memory
        ↓
Versioned Local Store
        │
        ├── Export
        │
        └── Backup
```

Memory should remain local by default.

---

# 99. Updated Repository Layout

Add local import/export and backup components:

```text
karen/
├── data/
├── training/
├── models/
├── conversion/
├── tools_spec/
├── android/
│   ├── inference/
│   ├── models/
│   │   ├── ModelManager
│   │   ├── ModelImporter
│   │   ├── ModelExporter
│   │   ├── ModelValidator
│   │   └── ModelCatalog
│   ├── memory/
│   │   ├── MemoryStore
│   │   ├── MemoryImporter
│   │   ├── MemoryExporter
│   │   └── MemoryBackupManager
│   ├── backup/
│   │   ├── BackupManager
│   │   ├── BackupExporter
│   │   ├── BackupImporter
│   │   ├── BackupManifest
│   │   └── ChecksumManager
│   ├── storage/
│   │   ├── StorageManager
│   │   └── StorageUsage
│   └── ...
├── rag/
└── docs/
```

---

# 100. New Acceptance Criteria

### Local Model Import

- [ ] User can select a supported model from Android local storage.
- [ ] Karen validates the model before registration.
- [ ] Karen detects model metadata and quantization where available.
- [ ] Corrupt/unsupported models are rejected.
- [ ] Imported models appear in Model Manager.
- [ ] Imported models can be loaded and used for inference.
- [ ] Original user files are not silently deleted.

### Model Export

- [ ] User can export an installed model.
- [ ] User chooses the destination.
- [ ] Export progress is visible.
- [ ] Exported model passes checksum verification.
- [ ] Installed model remains available.

### Memory Export

- [ ] User can export approved memory.
- [ ] User chooses the destination.
- [ ] Exported memory can be restored.
- [ ] Optional encryption is available.
- [ ] No cloud upload is required.

### Memory Import

- [ ] User can select a memory backup.
- [ ] Package integrity is verified.
- [ ] Import preview is shown.
- [ ] Duplicate handling is deterministic.
- [ ] Existing memory is never silently overwritten.
- [ ] Indexes are rebuilt when required.

### Full Offline Migration

- [ ] Karen can be migrated to another Android device using local files.
- [ ] Settings can be restored.
- [ ] Memory can be restored.
- [ ] Routines can be restored.
- [ ] Model metadata can be restored.
- [ ] Models can be imported separately.
- [ ] No cloud account is required.
