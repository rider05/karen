# Thinai — Bundled Model Catalog

Catalog of the on-device LLM/embedding models that the Thinai Android app ships support for.

| | |
|---|---|
| **App** | Thinai |
| **Package** | `in.atmega.thinai` |
| **Version** | 2.3.11 (versionCode 10003) |
| **SDK** | min 24 → target 36 |
| **Runtime** | `libfllama.so` — llama.cpp FFI (ARM64, Adreno OpenCL / Mali Vulkan GPU offload) |
| **Catalog source** | `lib/arm64-v8a/libapp.so` (Dart AOT snapshot), `split_config.arm64_v8a.apk` |
| **Extracted** | 2026-10-21 |

> **"Bundled" means the catalog, not the weights.** No GGUF file is inside the APK. The app carries the list, descriptions and Hugging Face download URLs; weights are fetched on demand and stored in app-private storage. The app also supports importing a local `.gguf` and serving models over an OpenAI/Ollama-compatible API on port `11434`.

**Contents:** 30 chat models · 5 embedding models · 3 vision projector files — across 10 model families.

---

## 1. Chat models

`Quant` is the quantization of the file the catalog points at. `Vision` marks models that need a companion `mmproj` projector (listed in §2).

### Qwen 3.5 (Alibaba)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Qwen3.5-0.8B | 0.8B | Q8_0 | No | `ggml-org/Qwen3.5-0.8B-GGUF` | `Qwen3.5-0.8B-Q8_0.gguf` |
| Qwen3.5-2B | 2B | Q4_K_M | No | `unsloth/Qwen3.5-2B-GGUF` | `Qwen3.5-2B-Q4_K_M.gguf` |
| Qwen3.5-4B | 4B | Q4_K_M | No | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-Q4_K_M.gguf` |

### Qwen 3 / Qwen 2.5

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Qwen3-0.6B | 0.6B | Q8_0 | No | `Qwen/Qwen3-0.6B-GGUF` | `Qwen3-0.6B-Q8_0.gguf` |
| Qwen3-1.7B | 1.7B | Q8_0 | No | `Qwen/Qwen3-1.7B-GGUF` | `Qwen3-1.7B-Q8_0.gguf` |
| Qwen3-4B | 4B | Q4_K_M | No | `Qwen/Qwen3-4B-GGUF` | `Qwen3-4B-Q4_K_M.gguf` |
| Qwen3-4B-Instruct-2507 | 4B | Q4_K_M | No | `unsloth/Qwen3-4B-Instruct-2507-GGUF` | `Qwen3-4B-Instruct-2507-Q4_K_M.gguf` |
| Qwen2.5-0.5B-Instruct | 0.5B | Q4_K_M | No | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` |
| Qwen2.5-1.5B-Instruct | 1.5B | Q4_K_M | No | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |

### Gemma 4 / 3 / 3n / 2 (Google)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Gemma 4 E2B-it | E2B | Q4_0 | **Yes** | `ggml-org/gemma-4-E2B-it-GGUF` | `gemma-4-E2B-it-Q4_0.gguf` |
| Gemma 4 E4B-it | E4B | Q4_0 | **Yes** | `ggml-org/gemma-4-E4B-it-GGUF` | `gemma-4-E4B-it-Q4_0.gguf` |
| Gemma 3 270M-it | 270M | Q8_0 | No | `ggml-org/gemma-3-270m-it-GGUF` | `gemma-3-270m-it-Q8_0.gguf` |
| Gemma 3 1B-it | 1B | Q4_K_M | No | `ggml-org/gemma-3-1b-it-GGUF` | `gemma-3-1b-it-Q4_K_M.gguf` |
| Gemma 3 4B-it | 4B | Q4_K_M | **Yes** | `ggml-org/gemma-3-4b-it-GGUF` | `gemma-3-4b-it-Q4_K_M.gguf` |
| Gemma 3n E2B-it | E2B (5B raw, ~2B active) | Q4_K_M | No projector bundled | `unsloth/gemma-3n-E2B-it-GGUF` | `gemma-3n-E2B-it-Q4_K_M.gguf` |
| Gemma 2 2B-it | 2B | Q4_K_M | No | `bartowski/gemma-2-2b-it-GGUF` | `gemma-2-2b-it-Q4_K_M.gguf` |

### Llama 3.2 (Meta)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Llama 3.2 1B Instruct | 1B | Q4_K_M | No | `bartowski/Llama-3.2-1B-Instruct-GGUF` | `Llama-3.2-1B-Instruct-Q4_K_M.gguf` |
| Llama 3.2 3B Instruct | 3B | Q4_K_M | No | `bartowski/Llama-3.2-3B-Instruct-GGUF` | `Llama-3.2-3B-Instruct-Q4_K_M.gguf` |

### Phi (Microsoft)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Phi-3.5-mini-instruct | — | Q4_K_M | No | `bartowski/Phi-3.5-mini-instruct-GGUF` | `Phi-3.5-mini-instruct-Q4_K_M.gguf` |
| Phi-4-mini-instruct | — | Q4_K_M | No | `bartowski/microsoft_Phi-4-mini-instruct-GGUF` | `microsoft_Phi-4-mini-instruct-Q4_K_M.gguf` |

### Granite (IBM)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| Granite 4.1 3B | 3B | Q4_K_M | No | `ibm-granite/granite-4.1-3b-GGUF` | `granite-4.1-3b-Q4_K_M.gguf` |
| Granite 4.0 h-micro | — | Q4_K_M | No | `ibm-granite/granite-4.0-h-micro-GGUF` | `granite-4.0-h-micro-Q4_K_M.gguf` |

### LFM2 / LFM2.5 (Liquid AI)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| LFM2.5-350M | 350M | Q8_0 | No | `LiquidAI/LFM2.5-350M-GGUF` | `LFM2.5-350M-Q8_0.gguf` |
| LFM2.5-1.2B-Instruct | 1.2B | Q4_K_M | No | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` | `LFM2.5-1.2B-Instruct-Q4_K_M.gguf` |
| LFM2.5-2.6B | 2.6B | Q4_K_M | No | `LiquidAI/LFM2.5-2.6B-GGUF` | `LFM2.5-2.6B-Q4_K_M.gguf` |
| LFM2-1.2B | 1.2B | Q4_K_M | No | `LiquidAI/LFM2-1.2B-GGUF` | `LFM2-1.2B-Q4_K_M.gguf` |

### SmolLM (Hugging Face)

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| SmolLM3 3B | 3B | Q4_K_M | No | `ggml-org/SmolLM3-3B-GGUF` | `SmolLM3-Q4_K_M.gguf` |
| SmolLM2 1.7B-Instruct | 1.7B | Q4_K_M | No | `bartowski/SmolLM2-1.7B-Instruct-GGUF` | `SmolLM2-1.7B-Instruct-Q4_K_M.gguf` |

### Other

| Model | Params | Quant | Vision | Repo | File |
|---|---|---|---|---|---|
| TinyLlama 1.1B Chat v1.0 | 1.1B | Q4_K_M | No | `TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF` | `tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf` |
| NVIDIA Nemotron 3 Nano 4B | 4B | Q4_K_M | No | `nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF` | `NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf` |

Download URL pattern: `https://huggingface.co/<repo>/resolve/main/<file>`

---

## 2. Vision projectors (`mmproj`)

Loaded alongside their base model to give it image input. All three are separate downloads.

| Companion to | Projector file | Quant |
|---|---|---|
| Gemma 3 4B-it | `mmproj-model-f16.gguf` | F16 |
| Gemma 4 E2B-it | `mmproj-gemma-4-E2B-it-Q8_0.gguf` | Q8_0 |
| Gemma 4 E4B-it | `mmproj-gemma-4-E4B-it-Q8_0.gguf` | Q8_0 |

---

## 3. Embedding models

Used for on-device RAG / vector search. Kept in a separate `embeddingCatalog` in the app.

| Model | Quant | Dimensions feature | Repo | File |
|---|---|---|---|---|
| EmbeddingGemma 300M | Q8_0 | — | `ggml-org/embeddinggemma-300M-GGUF` | `embeddinggemma-300M-Q8_0.gguf` |
| Qwen3-Embedding 0.6B | Q8_0 | — | `Qwen/Qwen3-Embedding-0.6B-GGUF` | `Qwen3-Embedding-0.6B-Q8_0.gguf` |
| BGE Small EN v1.5 | Q8_0 | — | `CompendiumLabs/bge-small-en-v1.5-gguf` | `bge-small-en-v1.5-q8_0.gguf` |
| Nomic Embed Text v1.5 | Q5_K_M | `dimensions` shortening | `nomic-ai/nomic-embed-text-v1.5-GGUF` | `nomic-embed-text-v1.5.Q5_K_M.gguf` |
| All-MiniLM L6 v2 | Q8_0 | — | `leliuga/all-MiniLM-L6-v2-GGUF` | `all-MiniLM-L6-v2.Q8_0.gguf` |

---

## 4. The app's own catalog copy

Quoted verbatim from the compiled snapshot, matched to the model it names or describes unambiguously.

**Chat**

- **Qwen3-0.6B** — "Latest Qwen generation with hybrid thinking. Tiny, and Q8 keeps quality high."
- **Qwen3-4B** — "The original Qwen 3 at 4B. Flagship phone recommended; expect slower tokens."
- **Qwen3-4B-Instruct-2507** — "Refreshed Qwen3 4B: big gains over the original, and no thinking preamble."
- **Qwen3.5-0.8B** — "Newest tiny Qwen: a 256K window under a gigabyte, and Q8 keeps it sharp."
- **Qwen3.5-2B** — "The mid-size Qwen 3.5. Strong multilingual chat, and 256K of context."
- **Qwen2.5-1.5B** — "Noticeable quality jump over 0.5B. Good sweet spot for mid-range phones."
- **Gemma 4 E2B** — "Gemma 4's phone tier. 256K context, 140+ languages, and QAT weights that stay sharp at Q4."
- **Gemma 4 E4B** — "The larger on-device Gemma 4. Biggest download here, so it needs a flagship with RAM to spare."
- **Gemma 3 270M** — "The smallest model here. Instant replies on any phone; best for simple tasks."
- **Gemma 3 1B** — "Google's newest small Gemma. 32K context and quick on modest hardware."
- **Gemma 3 4B** — "Gemma 3 at 4B: broad knowledge and long context. Best on flagship devices."
- **Gemma 3n E2B** — "Designed for on-device: 5B weights but only ~2B active per token."
- **Gemma 2 2B** — "Google's efficient 2B model. High-quality reasoning for its size."
- **Llama 3.2 (1B / 3B)** — "Meta's mobile-tuned Llama 3.2. Strong instruction following at a small size."
- **Phi-3.5-mini** — "Microsoft's reasoning-focused mini model. Punches well above its size."
- **Phi-4-mini** — "Newest Phi generation. Excellent at maths and reasoning for its size."
- **Granite 4.1 3B** — "IBM's newest Granite. Aimed at tool calling, RAG, and long documents."
- **Granite 4.0 h-micro** — "IBM's hybrid-Mamba model. Tuned for tool calling and long documents."
- **LFM2.5-350M** — "Liquid's smallest. Near-instant replies on any phone, and a 125K window."
- **LFM2.5-1.2B** — "LFM2 rebuilt: same download, four times the context, better at instructions."
- **LFM2.5-2.6B** — "Built for on-device agents: tool calling and 128K context in 1.6 GB."
- **LFM2-1.2B** — "Trained specifically for on-device use. Snappy, with a friendly chat style."
- **SmolLM3 3B** — "Newest SmolLM, with optional thinking mode and multilingual support."
- **Nemotron 3 Nano 4B** — "NVIDIA's on-device Nemotron. Hybrid-Mamba layers and a million-token window."

**Embeddings**

- **EmbeddingGemma 300M** — "Google's embedding model, trained for 100+ languages. Best multilingual retrieval."
- **Qwen3-Embedding 0.6B** — "Top-ranked multilingual embedder. The most accurate option, and the largest."
- **BGE Small EN v1.5** — "Tiny English retrieval model. Punches far above 35 MB for search and RAG."
- **Nomic Embed Text v1.5** — "The default for on-device RAG. Supports shortening via `dimensions`."
- **All-MiniLM L6 v2** — "The classic lightweight embedder. Smallest and fastest option at 24 MB."

---

## 5. Notes and limits

- **Weights are not bundled.** Only the catalog metadata lives in the APK. First use requires network + free storage.
- **`mmproj` is mandatory for vision.** A Gemma 4 / Gemma 3 4B chat without its projector is text-only; the app surfaces this as "This model cannot read images. Try Gemma 3 4B instead."
- **Quantization** — `Q8_0` ≈ 1 byte/weight (best quality, largest); `Q5_K_M` ≈ 0.7; `Q4_K_M` / `Q4_0` ≈ 0.55–0.6 (smallest, fastest). Sizes matter on phones; that is why sub-2B models dominate the list.
- **Runtime** is llama.cpp via `libfllama.so`, with GPU offload through Adreno OpenCL and Mali Vulkan, falling back to CPU.
- **Referenced but not in the catalog** — UI copy mentions *MiniCPM* for vision and a *"5B weights, ~2B active"* model (matches Gemma 3n E2B) that have no download entry in this build. Leftover strings do not imply availability.
- **Unattributed copy** — a few description strings could not be tied to one model ("Full-capability chat model…", "Strong reasoning for its size…", "Small chat model distilled for phones…", "Tiny and fast. Runs on almost any phone…"), so they are omitted rather than guessed.

### Provenance

Objective fields — repo paths, filenames, quantizations, projector files, version/package — are read directly from the AOT string table and the APK manifests. Parameter labels come from model names. Note-to-model pairing is quoted app text where the model is named or the description is unambiguous; it is inference, not a machine-read record.

Source bundle: `D:\karen\thinai` (`base.apk` + 9 split APKs, vivo "Office Kit" package split).
