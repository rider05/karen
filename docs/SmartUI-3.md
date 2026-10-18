# ChatGPT-Style Intelligent UI: Complete Visual Component Checklist & Build Guide

A practical inventory of UI visuals, plus the architecture, safety rules
and build plan for a ChatGPT-like AI assistant, especially with React
Native/Expo.

**How to use this document**

-   Part A (sections 1-17) is the component inventory.
-   Part B (sections 18-29) is how to build, secure and ship it.
-   **Bold items = MVP.** Build these first. Everything else is added
    when a real use case needs it.
-   Items that appear in more than one place are listed once and
    cross-referenced.

------------------------------------------------------------------------

# Part A: Component Inventory

## 1. Core Chat Interface

-   [ ] **User and AI message bubbles**
-   [ ] **Markdown-formatted responses** (headings, lists, tables, links)
-   [ ] **Code blocks with syntax highlighting** (see section 7)
-   [ ] **Inline citations and source links**
-   [ ] **Typing indicator and streaming text**
-   [ ] **Message actions: copy, edit, retry, share, report**
-   [ ] **Stop-generation control**
-   [ ] **Regenerate response control**
-   [ ] **Thumbs up/down feedback**
-   [ ] Conversation history and search
-   [ ] Attachments and image previews
-   [ ] Voice input and audio playback
-   [ ] Follow-up question suggestions
-   [ ] Starter prompts for an empty chat
-   [ ] Message timestamps and delivery states
-   [ ] Message branching / version switcher (edit or regenerate)
-   [ ] Collapsible long messages
-   [ ] Scroll-to-bottom button
-   [ ] Reasoning or "thinking" summary panel (collapsed by default)
-   [ ] Math rendering (LaTeX) in messages
-   [ ] Share sheet and deep links to a conversation

## 2. Data Visualization

Choose the chart from the shape of the data, not from habit: trend over
time → line/area; category comparison → bar; part-to-whole (few parts)
→ donut; distribution → histogram/box; relationship → scatter.

-   [ ] **Line chart**
-   [ ] **Bar and grouped bar chart**
-   [ ] **Pie and donut chart** (limit to about 5-6 slices)
-   [ ] Stacked bar chart
-   [ ] Area and stacked area chart
-   [ ] Scatter plot and bubble chart
-   [ ] Histogram
-   [ ] Heatmap
-   [ ] Box plot
-   [ ] Radar chart
-   [ ] Funnel chart
-   [ ] Waterfall chart
-   [ ] Treemap
-   [ ] Candlestick chart
-   [ ] Sparkline and gauge (for KPI cards)
-   [ ] Gantt / timeline chart
-   [ ] **Interactive legends, tooltips, filters, and zoom controls**
-   [ ] **Table fallback for every chart** (accessibility and copy/export)

## 3. Interactive Controls

-   [ ] **Primary and secondary buttons**
-   [ ] **Quick-reply chips** (single and multi-select)
-   [ ] **Dropdown menus**
-   [ ] **Radio buttons, checkboxes and toggle switches**
-   [ ] **Sliders and range sliders** (with live value and live output)
-   [ ] **Text, numeric, and password inputs**
-   [ ] **Confirmation dialogs**
-   [ ] Button groups
-   [ ] Numeric stepper
-   [ ] Search fields and autocomplete
-   [ ] Date and time pickers
-   [ ] Multi-step forms with validation
-   [ ] File upload controls
-   [ ] Expandable sections
-   [ ] Tabs and segmented controls
-   [ ] Rating input
-   [ ] Color picker

(Pagination lives in section 10.)

## 4. Information Cards and Dashboards

-   [ ] **Information cards**
-   [ ] **KPI/statistic cards**
-   [ ] **Summary cards**
-   [ ] **Status and alert cards**
-   [ ] Comparison cards
-   [ ] Profile cards
-   [ ] Product and service cards
-   [ ] Weather cards
-   [ ] News cards
-   [ ] Recommendation cards
-   [ ] Metric trend cards
-   [ ] Expandable detail cards
-   [ ] Card carousels

## 5. Maps and Location

-   [ ] **Interactive maps with pins and markers**
-   [ ] **Address cards**
-   [ ] Route and navigation maps
-   [ ] Multi-stop itinerary maps
-   [ ] Distance and travel-time cards
-   [ ] Nearby-place carousel
-   [ ] Geolocation previews
-   [ ] Traffic visualizations
-   [ ] Location selection controls
-   [ ] Permission-gated location (ask before using the device location)
-   [ ] Static-image map fallback when offline or on low-end devices

## 6. Media and Creative Visuals

-   [ ] **Image gallery and image grids**
-   [ ] **Full-screen image viewer**
-   [ ] **File and document previews**
-   [ ] Video player
-   [ ] Audio player and waveform
-   [ ] Voice recording interface
-   [ ] Podcast and music cards
-   [ ] Generated image previews
-   [ ] Before-and-after image comparison slider
-   [ ] Media download and share controls
-   [ ] Alt text for every generated or fetched image
-   [ ] Blur-hash or skeleton placeholders while images load

## 7. Code and Developer Tools

-   [ ] **Syntax-highlighted code blocks with copy control**
-   [ ] **Error and stack-trace panels**
-   [ ] **JSON and YAML viewers**
-   [ ] Download code control
-   [ ] Editable code snippets
-   [ ] Terminal output panels (collapsible when long)
-   [ ] API request/response viewers
-   [ ] Database table viewers
-   [ ] Architecture diagrams
-   [ ] Flowcharts and decision trees
-   [ ] Mermaid diagrams
-   [ ] Code execution results (run in a remote sandbox only, never on
    the device)
-   [ ] Diff and code comparison views
-   [ ] App and website previews

## 8. AI Agent and Workflow Visuals

-   [ ] **Task progress indicator**
-   [ ] **Tool invocation status**
-   [ ] **Approval and confirmation cards** (human-in-the-loop)
-   [ ] **Success, warning, and error states**
-   [ ] **Retry and cancellation controls**
-   [ ] Plan preview before execution ("here is what I will do")
-   [ ] Multi-step execution timeline
-   [ ] Agent status cards
-   [ ] Pending action queue
-   [ ] Workflow diagrams
-   [ ] Task checklist
-   [ ] Background task status and completion notification
-   [ ] Execution result summaries
-   [ ] Undo / rollback for reversible actions
-   [ ] Activity log (what the agent did and when)
-   [ ] Usage or cost indicator, where relevant

## 9. Search, Browsing, and Research

-   [ ] **Search result cards**
-   [ ] **Source and citation panels**
-   [ ] **Comparison tables**
-   [ ] Website preview cards
-   [ ] Article summary cards
-   [ ] News carousels
-   [ ] Research finding cards
-   [ ] Reference lists
-   [ ] Timeline of events
-   [ ] Search filters
-   [ ] Evidence and source comparison panels (flag conflicting sources)
-   [ ] Source date / freshness labels

## 10. Tables and Structured Data

-   [ ] **Basic data tables** (horizontal scroll, sticky header)
-   [ ] **Sortable tables**
-   [ ] Filterable tables
-   [ ] Editable tables
-   [ ] Spreadsheet-like grids
-   [ ] Row selection and bulk actions
-   [ ] Pagination or virtualized rows for large tables
-   [ ] Key-value property lists
-   [ ] Database record cards
-   [ ] Pivot-table summaries
-   [ ] Table-to-chart switching
-   [ ] CSV and Excel file previews
-   [ ] Export to CSV

## 11. Education and Productivity

-   [ ] Study-plan timelines
-   [ ] Interactive flashcards
-   [ ] Multiple-choice quizzes
-   [ ] Quiz score summaries
-   [ ] Step-by-step math solutions
-   [ ] Formula cards
-   [ ] Notes and document viewers
-   [ ] Kanban boards
-   [ ] Calendar views
-   [ ] Timetables
-   [ ] Habit trackers
-   [ ] Progress rings
-   [ ] Project task boards
-   [ ] Meeting summaries
-   [ ] Reminder and deadline cards

## 12. Shopping and Comparison

-   [ ] Product cards
-   [ ] Product image galleries
-   [ ] Price comparison tables
-   [ ] Feature comparison matrices
-   [ ] Ratings and review summaries
-   [ ] Product specification panels
-   [ ] Filter and sorting controls
-   [ ] Shopping lists
-   [ ] Cart summaries
-   [ ] Recommendation carousels
-   [ ] Availability indicators
-   [ ] Sponsored / affiliate disclosure label, if any links earn money

## 13. Finance and Numerical Visuals

-   [ ] Expense breakdown charts
-   [ ] Budget progress bars
-   [ ] Income and expense tables
-   [ ] Loan and EMI calculators
-   [ ] Investment performance charts
-   [ ] Portfolio allocation charts
-   [ ] Financial KPI cards
-   [ ] Currency conversion displays
-   [ ] Savings goal trackers
-   [ ] Scenario comparison controls
-   [ ] **Assumptions and formula panel** on every calculator result
-   [ ] "Not financial advice" disclaimer where appropriate

## 14. Scientific and Technical Visuals

-   [ ] Mathematical equation rendering
-   [ ] Interactive calculators (see section 22)
-   [ ] Unit conversion panels
-   [ ] Editable function / equation graphs
-   [ ] Interactive concept diagrams (labels, step-through, toggles)
-   [ ] Statistical test result cards
-   [ ] Probability distributions
-   [ ] Model performance charts
-   [ ] Confusion matrices
-   [ ] ROC curves
-   [ ] Precision-recall curves
-   [ ] Neural network diagrams
-   [ ] Dataset previews
-   [ ] Training progress charts
-   [ ] Sensor readings and telemetry
-   [ ] 3D model previews, where supported

## 15. Status, Feedback, and System Visuals

-   [ ] **Loading skeletons**
-   [ ] **Spinners and progress bars**
-   [ ] **Empty states**
-   [ ] **Error panels with retry**
-   [ ] **Unsupported-component fallback card** (see section 26)
-   [ ] Warning banners
-   [ ] Success confirmations
-   [ ] Toast notifications
-   [ ] Permission request cards
-   [ ] Offline indicators and network status
-   [ ] Rate-limit messages
-   [ ] Authentication screens
-   [ ] Partial-render warning ("some content couldn't be displayed")

## 16. Layout and Navigation

-   [ ] **Tabs**
-   [ ] **Modal / bottom sheets**
-   [ ] **Accordions**
-   [ ] **Responsive grids**
-   [ ] Sidebars and navigation drawers
-   [ ] Bottom navigation bars
-   [ ] Floating action buttons
-   [ ] Toolbars
-   [ ] Dialogs and popovers
-   [ ] Carousels
-   [ ] Split-pane layouts (tablets)
-   [ ] Nested cards (keep to one level of nesting)
-   [ ] Full-screen immersive views

## 17. Personalization and Settings

-   [ ] **Light and dark themes**
-   [ ] **Visual-density setting** (Off / Minimal / Auto / Rich), so users
    who prefer text can turn visuals down
-   [ ] **Accessibility controls** (font size, reduced motion, high
    contrast)
-   [ ] Theme selector
-   [ ] Language selector
-   [ ] Model selector
-   [ ] Voice selector
-   [ ] Chat history management (delete, export)
-   [ ] Memory and personalization controls
-   [ ] Notification settings
-   [ ] Privacy and permission settings
-   [ ] Data and storage controls

------------------------------------------------------------------------

# Part B: How to Build It

## 18. Text vs. Visual: When to Use What

The model should choose the *simplest* format that answers the question.
Suggested mapping:

| User intent | Preferred output |
|---|---|
| Quick fact, opinion, chit-chat, emotional support | Plain text |
| Compare options | Comparison table or cards |
| Explain a concept | Short text + diagram or interactive explainer |
| Plan a trip or route | Itinerary + map |
| Calculate something | Calculator with live inputs |
| Track progress or goals | Progress / dashboard |
| Trends or distributions | Chart + table fallback |
| Multi-step task | Plan preview → status timeline → result |
| Anything consequential (send, pay, delete) | Confirmation card |

Rules of thumb:

-   Default to text. Add a visual only when it communicates something
    text cannot (structure, comparison, interaction).
-   Never put a decision-critical fact *only* inside a visual. Repeat it
    in text.
-   Respect the user's visual-density setting (section 17).

## 19. Suggested Architecture

1.  **User message:** The user asks a question or gives a task.
2.  **LLM response:** The model returns text and/or a structured UI
    specification (streamed).
3.  **Streaming parser:** Incrementally parse partial output; render
    complete blocks as they arrive.
4.  **Schema validation:** Validate each block against a strict,
    versioned schema.
5.  **Permission checks:** Check which actions are allowed and whether
    confirmation is required.
6.  **Component registry:** Map approved component types to known UI
    components.
7.  **Renderer:** Render the approved visuals with validated data, each
    inside an error boundary.
8.  **Action handler:** Execute only predefined application actions with
    validated parameters.
9.  **State and result update:** Store interaction state, display the
    outcome, errors and next steps, and log telemetry.

``` text
User Message
    |
    v
LLM / AI Model  (streamed text + UI spec blocks)
    |
    v
Streaming Parser (partial JSON -> complete blocks)
    |
    v
Schema Validation (versioned) + Permission Checks
    |        \
    |         `--> invalid? repair-retry -> text fallback (section 26)
    v
Allowlisted Component Registry
    |
    v
React Native Renderer (error boundary per block)
    |
    v
User Interaction
    |-- local interaction (slider, tab, sort) -> update UI state only
    `-- action (button) -> Permission/Confirm -> Predefined Action Handler
    |
    v
Result / Status Update -> Message store + Telemetry
```

## 20. The Spec Contract

Every component shares a base shape, so renderers, accessibility and
fallbacks work uniformly.

Base fields on every component:

-   `id`: stable key (needed for streaming and re-render)
-   `type`: discriminator from the allowlist
-   `a11yLabel`: text for screen readers
-   `fallbackText`: plain-text version for copy, share, notifications
    and unsupported clients

Top-level spec fields:

-   `version`: schema version, so old messages keep rendering after the
    schema changes
-   `blocks`: ordered list mixing text blocks and components

Example validation with Zod (TypeScript):

``` ts
import { z } from "zod";

const Base = z.object({
  id: z.string(),
  a11yLabel: z.string().optional(),
  fallbackText: z.string().optional(),
});

const Progress = Base.extend({
  type: z.literal("progress"),
  label: z.string().max(80),
  value: z.number().min(0).max(100),
});

const ButtonC = Base.extend({
  type: z.literal("button"),
  label: z.string().max(40),
  action: z.enum(["open_study_plan", "open_settings", "create_reminder"]),
  params: z.record(z.string(), z.union([z.string(), z.number()])).optional(),
});

const Calculator = Base.extend({
  type: z.literal("calculator"),
  title: z.string().max(80),
  inputs: z.array(z.object({
    key: z.string().regex(/^[a-z_][a-z0-9_]*$/i),
    label: z.string().max(40),
    kind: z.enum(["number", "slider"]),
    min: z.number().optional(),
    max: z.number().optional(),
    default: z.number(),
  })).max(8),
  outputs: z.array(z.object({
    label: z.string().max(40),
    formula: z.string().max(200),
    format: z.enum(["number", "currency", "percent"]),
  })).max(6),
});

export const UIComponent = z.discriminatedUnion("type", [
  Progress, ButtonC, Calculator, /* chart, table, card, ... */
]);

export const UISpec = z.object({
  version: z.literal(1),
  blocks: z.array(
    z.union([
      z.object({ type: z.literal("text"), markdown: z.string() }),
      UIComponent,
    ])
  ),
});
```

Contract rules:

-   Cap everything: string lengths, array sizes, nesting depth, number of
    components per message.
-   Reject unknown fields and unknown types (do not guess).
-   Never accept HTML, JavaScript, style objects or arbitrary URLs as
    component content.
-   Version the schema and keep old renderers or a migration path.

## 21. Streaming and Progressive Rendering

-   Stream text and UI blocks as separate, ordered blocks.
-   Use an incremental JSON parser (or emit newline-delimited JSON, one
    block per line) so each complete block can render immediately.
-   Show a skeleton for a block that has started but is incomplete;
    replace it in place when it validates (stable `id` keys avoid
    flicker).
-   If a block fails validation mid-stream, drop only that block and
    continue.
-   Keep stop/cancel working at every stage.
-   Do not re-run layout on every token; batch updates (for example,
    every 50-100 ms).

## 22. Interaction Model and On-Demand Mini-Tools

Separate three kinds of interaction:

1.  **Local UI state** (tabs, sort, slider position, expand/collapse):
    handled on the device, no model call, instant.
2.  **Local computation** (calculators, unit converters, what-if
    sliders): the spec supplies inputs and *declarative formulas*; the
    app computes results locally.
3.  **Actions** (create reminder, send, save): go through the action
    handler with permission checks and confirmation.

Only call the model again when the user asks a new question or wants the
UI changed ("add a column", "make it monthly").

Generated calculators and mini-tools:

-   Describe them as data (inputs, formulas, output format), never as
    code.
-   Evaluate formulas with a small whitelisted expression parser
    (numbers, `+ - * / ^`, parentheses, a handful of math functions).
    Never use `eval`, `new Function`, or a general scripting engine on
    model output.
-   Show the formula and assumptions next to the result so users can
    verify it. Generated calculators look authoritative; make them
    checkable.
-   Persist the inputs the user chose so the tool is restored exactly
    when the chat is reopened.

## 23. Security and Safety

The model's output is untrusted input, especially when it has read web
pages, files or tool results that may contain injected instructions.

-   **Allowlist only:** render known component types; ignore everything
    else.
-   **No arbitrary code or HTML:** no WebView rendering of model-written
    markup; no dynamic imports.
-   **Actions are IDs, not code:** `action` must match a predefined
    handler; validate and bound every parameter.
-   **Confirm consequential actions** (payments, deletion, sending
    messages, sharing data) with a native confirmation that shows
    exactly what will happen, generated by the app, not by the model.
-   **Link and image allowlisting:** only `https`, block `javascript:`
    and `data:` schemes, and consider showing the destination domain
    before opening links.
-   **Anti-spoofing:** model-rendered UI must not be able to imitate
    system dialogs, login screens or permission prompts. Never collect
    passwords or one-time codes inside a model-generated form.
-   **Least privilege:** each action declares which permissions it needs;
    check them at execution time, not only at render time.
-   **Rate limits and size limits** on specs, actions and uploads.
-   **Privacy:** do not log full message contents or personal data in
    telemetry; redact before sending.
-   **Audit log** for executed actions (what, when, who approved).

## 24. Accessibility

-   Every interactive element has an accessible label, role and state.
-   Minimum touch target of about 44x44 pt.
-   Support dynamic font sizes without clipping.
-   Color is never the only signal; check contrast in both themes.
-   Every chart and map has a text or table alternative.
-   Respect the system "reduce motion" setting for animations and
    streaming effects.
-   Announce important state changes (loading finished, error) to screen
    readers.
-   Keyboard and switch-control navigation order follows reading order.

## 25. Performance (Mobile)

-   Use a virtualized list for the message list (for example FlashList)
    and for long tables.
-   Memoize message and component renderers; avoid re-rendering history
    while streaming the latest message.
-   Lazy-load heavy components (charts, maps, 3D, video) when they scroll
    into view.
-   Downsample large chart datasets before rendering.
-   Prefer GPU-friendly animation (Reanimated) over JS-thread animation.
-   Cache images and fetched thumbnails; set size limits on attachments.
-   Set budgets and measure them: time to first token on screen, frame
    rate while streaming, and memory with a long chat.

## 26. Failure Handling and Fallbacks

Use a fallback ladder instead of failing silently or crashing:

1.  **Block invalid** → drop that block, keep the rest of the message.
2.  **Spec invalid** → try one automatic repair (re-ask the model with the
    validation errors) if latency allows.
3.  **Still invalid** → show the model's `fallbackText` or plain text
    answer.
4.  **Unknown component type** (older app version) → show an
    "unsupported content" card with the fallback text and an update
    prompt.
5.  **Component crashes** → the error boundary replaces only that
    component with a retry card.
6.  **Network or model failure** → clear error with retry; keep the
    user's draft.

## 27. Persistence and History

-   Store the validated spec with the message, not rendered output, so it
    re-renders consistently.
-   Store user interaction state separately (slider values, selected
    tab, checked items).
-   Store the schema version with each message and migrate or fall back
    when it is old.
-   Keep a plain-text rendering of every message for search, export and
    notifications.
-   Decide retention up front: what is stored on device, what is synced,
    and how users delete it.

## 28. Small and On-Device Models

If the model is small or runs locally, UI generation becomes less
reliable. Adapt the design:

-   Use **constrained decoding** (JSON-schema or grammar-constrained
    output) so the model can only emit valid specs.
-   Start with a **small schema** (about 8-10 component types) and widen
    it only as quality allows.
-   Prefer **intent classification + templates**: the model picks a
    template and fills slots, instead of composing layouts.
-   Keep specs short; limit components per response.
-   Always keep the plain-text fallback path working.
-   Evaluate output validity rate on your own test set before shipping.

## 29. Testing, Evaluation and Metrics

**Testing**

-   Schema tests: valid and invalid specs, oversized payloads, unknown
    fields.
-   Fuzz and malformed-JSON tests for the streaming parser.
-   Security tests: injection attempts that try to produce fake login
    forms, hidden links, or unapproved actions.
-   Snapshot/visual regression tests for each component in light and dark
    themes.
-   Accessibility checks with screen readers on both platforms.

**Model evaluation**

-   Test set of prompts with expected format: "should be text",
    "should be a table", "should be a calculator".
-   Measure spec validity rate, correct component choice, and
    over-use of visuals.

**Metrics to track**

-   Spec validation failure rate and fallback rate
-   Component usage and interaction rate (which visuals actually help)
-   Time to first visible content and total render time
-   Crash rate per component
-   User feedback (thumbs) split by text vs. visual answers

## 30. Suggested Stack for React Native / Expo

Check current maintenance and Expo compatibility for each before
committing.

| Need | Options |
|---|---|
| Schema validation | Zod |
| Markdown | react-native-markdown-display or a custom renderer |
| Code highlighting | a lightweight highlighter component |
| Lists | FlashList |
| Charts | Victory Native, react-native-gifted-charts, or react-native-svg + d3 scales |
| Maps | react-native-maps or expo-maps |
| Animation | React Native Reanimated, Moti |
| Bottom sheets | @gorhom/bottom-sheet |
| Media | expo-video, expo-audio, expo-image |
| Local storage | expo-sqlite, MMKV |
| Math rendering | KaTeX via a WebView (static content only) or a native math component |
| Streaming | fetch with ReadableStream or Server-Sent Events |
| Telemetry | a privacy-respecting analytics/crash tool with redaction |

## 31. Recommended Implementation Priority

Each phase has an exit test. Do not start the next phase until it
passes.

### Phase 1: Core Chat

-   Text and Markdown renderer
-   Code block
-   Buttons and links
-   Loading, error, and empty states
-   Message actions and streaming

*Exit test:* a long streamed answer with Markdown and code renders
smoothly, can be stopped, retried and copied.

### Phase 2: Interactive UI (and the spec pipeline)

-   Versioned schema, validator, streaming parser, component registry
-   Cards
-   Forms and inputs
-   Sliders and dropdowns
-   Tabs and accordions
-   Checklists and confirmations

*Exit test:* malformed or hostile specs never crash the app and always
fall back to text.

### Phase 3: Data and Research

-   Tables
-   Charts (with table fallback)
-   Statistics cards
-   Search results
-   Citations and source panels

*Exit test:* every chart has an accessible alternative; large tables
scroll smoothly.

### Phase 4: Rich Media

-   Image gallery
-   Audio and video players
-   Document previews
-   Maps
-   Diagrams and flowcharts

*Exit test:* media loads lazily and the chat stays smooth with many
images.

### Phase 5: Agentic Workflows

-   Task timelines
-   Tool execution status
-   Approval prompts
-   Action results
-   Retry, cancellation, and progress controls
-   Audit log

*Exit test:* no consequential action can run without a confirmation
generated by the app.

### Phase 6: Specialized Components and Mini-Tools

-   Declarative calculators and on-demand tools
-   Education
-   Finance
-   Science and ML
-   Shopping
-   Productivity

*Exit test:* each specialized component has a real use case and a
measured usage rate.

## 32. Example Structured UI Specification

``` json
{
  "version": 1,
  "blocks": [
    {
      "type": "text",
      "markdown": "Here is your study progress and a quick planner."
    },
    {
      "type": "progress",
      "id": "unit1",
      "label": "Unit 1",
      "value": 80,
      "a11yLabel": "Unit 1 is 80 percent complete",
      "fallbackText": "Unit 1: 80% complete"
    },
    {
      "type": "calculator",
      "id": "hours",
      "title": "Hours needed per day",
      "inputs": [
        { "key": "pages", "label": "Pages left", "kind": "number", "default": 120 },
        { "key": "days", "label": "Days until exam", "kind": "slider", "min": 1, "max": 30, "default": 10 },
        { "key": "pph", "label": "Pages per hour", "kind": "number", "default": 6 }
      ],
      "outputs": [
        { "label": "Hours per day", "formula": "pages / pph / days", "format": "number" }
      ],
      "fallbackText": "Hours per day = pages / pages-per-hour / days"
    },
    {
      "type": "button",
      "id": "continue",
      "label": "Continue Studying",
      "action": "open_study_plan",
      "fallbackText": "Open your study plan"
    }
  ]
}
```

The application validates the JSON and renders only supported component
types. The `action` field must map to a predefined, permission-checked
application function; never execute arbitrary code supplied by the
model.

## 33. Core Engineering Rules

-   Use a reusable component registry instead of generating arbitrary UI
    code.
-   Validate every model-produced UI specification with a versioned
    schema; cap sizes and depth.
-   Keep UI rendering separate from tool execution and business logic.
-   Require app-generated confirmation for sensitive or consequential
    actions.
-   Treat all model output as untrusted, especially after the model has
    read external content.
-   Compute with declarative formulas and a whitelisted parser, never
    with model-written code.
-   Provide loading, success, failure, and cancellation states.
-   Give every component a text fallback and an accessible label.
-   Isolate failures with error boundaries; one bad block must not break
    the message.
-   Make layouts responsive and accessible.
-   Support dark and light themes and a visual-density setting.
-   Handle malformed model responses gracefully with a fallback ladder.
-   Do not assume every user request needs a visual component; plain
    text is often best.
-   Add specialized components only when they serve a real use case, and
    measure whether they are used.

## Final Recommendation

Start with roughly **20-30 foundational components** (the bold items
above), then expand based on actual use cases. A strong first release
should include chat rendering, Markdown, code, buttons, cards, forms,
tables, charts with table fallbacks, image previews, citations, and
agent task-status visuals, built on a validated, versioned spec
pipeline with text fallbacks from day one.

The central principle is: **the AI proposes what to show; the
application validates, renders, and safely controls what happens.**
