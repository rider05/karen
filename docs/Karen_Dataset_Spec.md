# Karen — Dataset Specification

## 1. Purpose

This document defines the dataset required to fine-tune and evaluate **Karen**, the offline-first Android AI assistant.

The dataset is designed around Karen's core behavior:

- normal conversation
- structured tool calling
- clarification instead of guessing
- honest handling of tool failures
- permission and safety behavior
- RAG-grounded answers
- coding/developer-agent behavior
- multi-step planning
- English, Tanglish, and Tamil interaction
- voice-transcription variations

Core principle:

> **The model proposes. The application validates. Tools execute. Results establish truth.**

The dataset must teach the model to produce correct behavior, but the Android application remains responsible for schema validation, permissions, policy enforcement, execution, and verification.

---

# 2. Dataset Versions

Recommended initial structure:

```text
dataset/
├── raw/
│   ├── seed/
│   ├── generated/
│   └── collected/
│
├── processed/
│   ├── train.jsonl
│   ├── validation.jsonl
│   └── test.jsonl
│
├── evaluation/
│   ├── tool_selection.jsonl
│   ├── tool_arguments.jsonl
│   ├── clarification.jsonl
│   ├── failures.jsonl
│   ├── safety.jsonl
│   ├── rag.jsonl
│   ├── coding.jsonl
│   └── multilingual.jsonl
│
├── schemas/
│   └── tools.json
│
├── scripts/
│   ├── validate_dataset.py
│   ├── deduplicate.py
│   ├── split_dataset.py
│   └── generate_variations.py
│
└── README.md
```

Dataset versions should be immutable after release.

Example:

```text
dataset_v1/
dataset_v2/
dataset_v3/
```

Every version should have a changelog.

---

# 3. Initial Dataset Size

Start with approximately:

```text
2,500 training examples
250–375 evaluation examples
```

Do not immediately target 10,000+ examples.

Quality is more important than raw quantity.

Expand only when evaluation identifies a real behavioral gap.

Recommended initial split:

```text
Training:    85–90%
Evaluation:  10–15%
```

The evaluation examples must not appear in training.

Near-duplicate examples must also be removed across the split.

---

# 4. Recommended Training Composition

Target approximately 2,500 examples:

| Category | Share | Approx. Count |
|---|---:|---:|
| Tool call + result + final reply | 35% | 875 |
| Clarification / missing arguments | 8% | 200 |
| Tool failure / permission denied / no result | 8% | 200 |
| No-tool conversational answers | 10% | 250 |
| Refusal / safety | 5% | 125 |
| Multi-step tasks | 5% | 125 |
| RAG-grounded answers | 9% | 225 |
| General chat / coding / explanations | 15% | 375 |
| Tamil / Tanglish / multilingual | 5% | 125 |
| **Total** | **100%** | **2,500** |

These numbers are targets, not rigid requirements.

A single example may have multiple tags.

---

# 5. Core Dataset Format

Use JSONL.

Each line represents one training example.

Recommended structure:

```json
{
  "id": "tool_open_document_0001",
  "category": "tool_call",
  "language": "en",
  "tools": ["open_document"],
  "messages": [
    {
      "role": "system",
      "content": "You are Karen, a private on-device assistant. Use tools for actions. Never claim an action succeeded unless a tool result confirms it."
    },
    {
      "role": "user",
      "content": "Open my college timetable."
    },
    {
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {
          "type": "function",
          "function": {
            "name": "open_document",
            "arguments": {
              "query": "college timetable"
            }
          }
        }
      ]
    },
    {
      "role": "tool",
      "name": "open_document",
      "content": "{\"status\":\"ok\",\"opened\":\"Timetable_Sem5.pdf\"}"
    },
    {
      "role": "assistant",
      "content": "Opened Timetable_Sem5.pdf."
    }
  ]
}
```

The exact chat-template/tool-call representation must match the model's inference format.

---

# 6. Tool Dataset Rule

For action-oriented requests, teach the full sequence:

```text
USER
  ↓
ASSISTANT TOOL CALL
  ↓
TOOL RESULT
  ↓
ASSISTANT FINAL RESPONSE
```

Do not train Karen to claim an action happened without a tool result.

Bad:

```text
User:
Open WhatsApp.

Assistant:
Sure, I opened WhatsApp.
```

Correct:

```text
User:
Open WhatsApp.

Assistant:
tool_call → open_app("WhatsApp")

Tool:
{"status":"ok","app":"WhatsApp"}

Assistant:
Opened WhatsApp.
```

Failure:

```text
Tool:
{"status":"error","reason":"App not installed"}

Assistant:
WhatsApp isn't installed on this device.
```

---

# 7. Tool Categories

Initial tool dataset should cover the tools planned for Karen.

## 7.1 Android/device tools

Examples:

```text
open_app
open_url
get_time
get_weather
set_reminder
play_media
calculator
```

Future examples may cover:

```text
send_message
make_call
read_notification
create_calendar_event
device_status
flashlight
```

Sensitive tools require permission/confirmation examples.

---

# 8. File and Document Tools

Examples:

```text
search_local_files
read_document
open_document
search_document
```

Dataset should include:

- exact filename requests
- semantic searches
- ambiguous filenames
- no-result searches
- multiple matching files
- unsupported file types
- permission denied
- corrupted/unreadable documents

Example:

```text
User:
Find my DBMS notes.

Tool:
{
  "status": "multiple",
  "matches": [
    "DBMS_Unit1.pdf",
    "DBMS_Unit2.pdf",
    "DBMS_Revision.pdf"
  ]
}

Assistant:
I found three matching files. Which one do you want?
```

---

# 9. RAG Dataset

RAG examples must teach Karen to use supplied context rather than inventing information.

Structure:

```text
USER QUESTION
+
RETRIEVED CONTEXT
↓
ANSWER BASED ONLY ON RELEVANT CONTEXT
```

Include:

- answer found in documents
- answer partially found
- answer not found
- conflicting retrieved snippets
- irrelevant retrieved snippets
- multiple relevant documents

Example:

```json
{
  "category": "rag",
  "context": [
    {
      "source": "DBMS_Unit3.pdf",
      "text": "Normalization reduces redundancy..."
    }
  ],
  "question": "Why is normalization used?",
  "answer": "The provided notes state that normalization is used to reduce redundancy..."
}
```

Important:

If the answer is not present in the supplied context, train the model to say so rather than fabricate an answer.

---

# 10. Clarification Dataset

Teach Karen to ask for missing information.

Examples:

```text
"Call Arun"
→ Multiple Aruns → ask which contact.

"Set a reminder"
→ Missing time/date → ask when.

"Open the file"
→ Missing file → ask which file.

"Send this message"
→ Missing recipient/message → ask for the missing information.
```

Do not teach the model to guess critical arguments.

---

# 11. Failure Dataset

Failure handling is a major training category.

Include:

```text
tool unavailable
permission denied
app not installed
file missing
file unreadable
network unavailable
invalid argument
multiple matches
timeout
execution failure
user cancelled
```

Expected behavior:

```text
Tool fails
↓
Model reads actual result
↓
Model explains failure
↓
Model does not pretend success
↓
Model may suggest a valid next step
```

---

# 12. Safety and Permission Dataset

Include examples where Karen must:

- request confirmation
- respect denied permission
- refuse unsafe operations
- avoid destructive commands
- avoid silently sending messages
- avoid silently making calls
- avoid destructive file operations
- avoid executing dangerous shell commands

For developer-agent behavior:

```text
read-only operation
→ can proceed

normal file edit
→ follow configured autonomy mode

destructive command
→ explicit confirmation required
```

Do not train unrestricted autonomous behavior.

---

# 13. Coding-Agent Dataset

Karen should eventually support:

```text
project understanding
file-tree generation
code generation
code modification
debugging
test generation
test execution
error analysis
dependency analysis
repository search
documentation
Git summaries
```

Examples should represent the workflow:

```text
UNDERSTAND
→ INVESTIGATE
→ DECOMPOSE
→ PLAN
→ EXECUTE
→ OBSERVE
→ VERIFY
→ RE-PLAN
→ COMPLETE
```

Project creation example:

```text
User:
Create a React Native expense tracker.

Karen:
1. Identify requirements.
2. Propose architecture.
3. Generate file tree.
4. Create files.
5. Add dependencies.
6. Implement features.
7. Run tests/build.
8. Read errors.
9. Fix errors.
10. Re-run verification.
11. Report completion.
```

Training examples should include incomplete builds and failed tests so the model learns recovery rather than stopping after generation.

---

# 14. Multi-Step Dataset

Keep multi-step examples relatively small initially.

Example:

```text
User:
Find my timetable and tell me what class I have tomorrow morning.
```

Possible behavior:

```text
search_local_files
↓
read_document
↓
extract relevant timetable information
↓
answer
```

Include:

- 2-tool tasks
- 3-tool tasks
- dependency between tool calls
- tool failure midway
- clarification midway
- verification after execution

Avoid teaching long autonomous chains until the basic tool system is reliable.

---

# 15. General Conversation Dataset

Karen must still behave like a normal assistant.

Include:

```text
questions
explanations
summaries
study help
math
programming concepts
debugging explanations
planning
writing assistance
casual conversation
```

This category prevents the model from trying to call a tool for every request.

Example:

```text
User:
What is a vector database?

Assistant:
A vector database stores numerical representations of data and allows similarity-based retrieval...
```

No tool call is required.

---

# 16. Coding Replay Dataset

Include normal coding questions:

```text
Python
JavaScript
TypeScript
React Native
Android
SQL
Firebase
Git
Linux
machine learning
data science
```

The model should answer normally unless an actual project/tool operation is required.

---

# 17. Tamil and Tanglish Dataset

Include natural user phrasing rather than artificial translations.

Examples:

```text
"WhatsApp open pannu"

"En timetable open pannu"

"Naalaikku morning enna class?"

"Arun ku call pannanum"

"Indha PDF la normalization enga irukku?"

"Idha explain pannu"

"WiFi on pannalama?"
```

Include:

- English
- Tamil
- Tanglish
- mixed English/Tamil
- typos
- abbreviations
- speech-to-text mistakes

Language distribution should eventually reflect actual Karen usage.

---

# 18. Voice-Transcription Variations

Because Karen will support voice interaction, create variations such as:

```text
"open whatsapp"
"open whats app"
"whatsapp open pannu"
"whatsapp ah open pannu"
"whatsapp open pannunga"
"whatsap open"
```

Include common transcription problems:

```text
missing punctuation
wrong spacing
homophones
partial words
repeated words
Tamil-English mixing
```

Do not depend on a single exact command.

---

# 19. Negative Examples

Negative examples are extremely important.

Examples:

```text
User asks a normal question
→ no tool

User gives insufficient information
→ clarification

User asks for unavailable capability
→ explain limitation

Tool result says failure
→ do not claim success

RAG context doesn't contain answer
→ say information wasn't found

Permission denied
→ do not bypass permission
```

These examples teach when NOT to act.

---

# 20. Evaluation Dataset

Create the evaluation dataset before fine-tuning.

Recommended initial size:

```text
250–375 examples
```

Never train on these examples.

Suggested evaluation groups:

```text
tool_selection.jsonl
tool_arguments.jsonl
clarification.jsonl
tool_failure.jsonl
safety.jsonl
rag.jsonl
coding.jsonl
multilingual.jsonl
multi_step.jsonl
general_chat.jsonl
```

Evaluate the base model first.

Then evaluate the fine-tuned model.

Compare:

```text
BASE QWEN
    vs
KAREN FINE-TUNED QWEN
```

---

# 21. Evaluation Metrics

Track at least:

## Tool selection

```text
Correct tool / total requests
```

## Argument accuracy

```text
Correct arguments / required arguments
```

## JSON validity

```text
Valid structured calls / total tool calls
```

Grammar-constrained decoding may guarantee syntactic validity, but semantic correctness must still be measured.

## Clarification accuracy

Measure whether Karen asks when required information is missing.

## Hallucinated-action rate

Critical metric:

```text
Claims action succeeded
BUT
no successful tool result exists
```

Target:

```text
0
```

## RAG grounding

Measure whether answers are supported by supplied context.

## Safety

Measure correct refusal/confirmation behavior.

## General ability

Compare normal question-answering and coding performance against the base model.

---

# 22. Data Creation Workflow

Recommended workflow:

```text
1. Define tool schemas
        ↓
2. Write ~100 high-quality seed examples
        ↓
3. Validate seeds
        ↓
4. Generate controlled variations
        ↓
5. Add manually written edge cases
        ↓
6. Programmatic validation
        ↓
7. Deduplicate
        ↓
8. Manual review
        ↓
9. Create train/eval split
        ↓
10. Freeze evaluation set
        ↓
11. Train
        ↓
12. Evaluate
        ↓
13. Identify failures
        ↓
14. Add targeted examples
        ↓
15. Create dataset_v2
```

Do not blindly generate thousands of examples and train immediately.

---

# 23. Seed Dataset

Start with approximately:

```text
100 manually written seeds
```

Suggested seed distribution:

```text
30 tool calls
10 clarification
10 failures
10 safety/permission
10 RAG
10 coding
10 general conversation
5 multi-step
5 Tamil/Tanglish
```

These establish Karen's behavior.

---

# 24. Synthetic Expansion

After the seeds are correct, generate variations.

Variation dimensions:

```text
phrasing
sentence length
typos
slang
Tamil/Tanglish
voice transcription
formal language
casual language
short commands
long requests
ambiguous requests
different tool results
success
failure
permission denied
no result
multiple results
```

Every generated sample must pass validation.

Synthetic generation must respect the terms of use of the model used to generate the data.

---

# 25. Programmatic Validation

Create `validate_dataset.py`.

It should check:

```text
✓ JSON parses
✓ Required fields exist
✓ IDs are unique
✓ Roles are valid
✓ Tool names exist
✓ Tool arguments match schema
✓ Tool results are present where required
✓ Tool result status is valid
✓ Final response exists
✓ No impossible tool sequence
✓ No empty user request
✓ No duplicate examples
✓ No evaluation IDs in training
```

Example command:

```bash
python scripts/validate_dataset.py dataset/processed/train.jsonl
```

---

# 26. Tool Schema Validation

Maintain one authoritative tool schema file:

```text
dataset/schemas/tools.json
```

Example:

```json
{
  "open_app": {
    "description": "Open an installed Android application.",
    "arguments": {
      "app_name": {
        "type": "string",
        "required": true
      }
    }
  }
}
```

Dataset examples must reference only tools defined in this schema.

When a tool schema changes, create a new dataset version.

---

# 27. Deduplication

Remove:

- exact duplicate prompts
- near-duplicate prompts
- identical tool calls with only superficial changes
- train/eval leakage
- repeated synthetic generations

Do not allow the evaluation set to be memorized through near-identical training samples.

---

# 28. Manual Review

Minimum recommendation:

```text
Review 20%+ of generated data
Review 100% of safety/refusal examples
Review 100% of evaluation data
Review all tool-schema edge cases
```

Review for:

```text
correct tool
correct arguments
correct result interpretation
correct final answer
no hallucinated success
correct permission behavior
correct language
natural phrasing
```

---

# 29. Dataset Quality Checklist

Before training:

```text
[ ] Tool schemas finalized
[ ] 100 seed examples written
[ ] Dataset generator tested
[ ] JSON validation passes
[ ] Tool validation passes
[ ] Duplicate detection passes
[ ] Safety examples reviewed
[ ] RAG examples reviewed
[ ] Tamil/Tanglish reviewed
[ ] Evaluation set frozen
[ ] No evaluation leakage
[ ] Dataset version tagged
[ ] Changelog created
```

---

# 30. Recommended First Milestone

Do not start by building 2,500 examples.

Build this first:

```text
100 seed examples
+
tool schemas
+
validator
+
20–50 evaluation examples
```

Then run the base Qwen model against the evaluation set.

Find its actual failures.

Only then expand the dataset toward approximately 2,500 examples.

This makes the fine-tuning process measurable instead of guessing what data the model needs.

---

# 31. Relationship Between Fine-Tuning, RAG, and Tools

Karen should separate these responsibilities:

```text
Fine-tuning
    ↓
How Karen behaves

RAG
    ↓
What Karen can retrieve from documents

Tools
    ↓
What Karen can actually do

Memory
    ↓
What Karen remembers about the user

Application policy
    ↓
What Karen is allowed to do
```

Do not use fine-tuning as a replacement for RAG, memory, permissions, or application security.

---

# 32. Final Dataset Architecture

```text
                    KAREN DATA
                        │
        ┌───────────────┼────────────────┐
        ↓               ↓                ↓
   TRAINING          EVALUATION       RUNTIME
    DATASET            DATASET          DATA
        │               │                │
        │               │         ┌──────┼──────┐
        │               │         ↓      ↓      ↓
        │               │       RAG   Memory   Tools
        │               │
        ↓               ↓
   QLoRA training     Benchmark
        │
        ↓
   Karen LoRA
        │
        ↓
   Merged model
        │
        ↓
   Quantized GGUF
        │
        ↓
   Android
```

## Final rule

**Do not collect data just because more data sounds better.**

Collect or create data specifically for behaviors that Karen must learn.

Start small, evaluate, identify failures, create targeted examples, and iterate.
