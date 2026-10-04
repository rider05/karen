---
name: Obsidian Cybernetic Workspace
colors:
  surface: '#111317'
  surface-dim: '#111317'
  surface-bright: '#37393e'
  surface-container-lowest: '#0c0e12'
  surface-container-low: '#1a1c20'
  surface-container: '#1e2024'
  surface-container-high: '#282a2e'
  surface-container-highest: '#333539'
  on-surface: '#e2e2e8'
  on-surface-variant: '#bdc8d1'
  inverse-surface: '#e2e2e8'
  inverse-on-surface: '#2f3035'
  outline: '#87929a'
  outline-variant: '#3e484f'
  surface-tint: '#7bd0ff'
  primary: '#8ed5ff'
  on-primary: '#00354a'
  primary-container: '#38bdf8'
  on-primary-container: '#004965'
  inverse-primary: '#00668a'
  secondary: '#93ccff'
  on-secondary: '#003351'
  secondary-container: '#3198dc'
  on-secondary-container: '#002c47'
  tertiary: '#56e5a9'
  on-tertiary: '#003824'
  tertiary-container: '#30c88f'
  on-tertiary-container: '#004e34'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#c4e7ff'
  primary-fixed-dim: '#7bd0ff'
  on-primary-fixed: '#001e2c'
  on-primary-fixed-variant: '#004c69'
  secondary-fixed: '#cce5ff'
  secondary-fixed-dim: '#93ccff'
  on-secondary-fixed: '#001d31'
  on-secondary-fixed-variant: '#004b73'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#111317'
  on-background: '#e2e2e8'
  surface-variant: '#333539'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 26px
    fontWeight: '600'
    lineHeight: 34px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '500'
    lineHeight: 28px
    letterSpacing: -0.01em
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
    letterSpacing: '0'
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: '0'
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: '0'
  label-mono-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: '0'
  label-mono-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.02em
  code-inline:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: '0'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-tablet: 1.5rem
  gutter-desktop: 1.5rem
  margin: 1rem
  margin-tablet: 1.5rem
  margin-desktop: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system embodies a modern, minimal, and utilitarian operating environment tailored for edge intelligence and private local-first computation. Built around the ethos of absolute local privacy, sovereignty, and quiet machine intelligence, the interface avoids theatrical science-fiction tropes in favor of an exacting, high-density tool built for focus, control, and transparency.

The aesthetic fuses deep technical utility with the refined tactility of native Android and Jetpack Compose surface logic. By pairing dense information telemetry with disciplined spatial breathing room, the design communicates absolute security, low latency, and zero cloud dependency. Visual accents remain clinically restrained: glowing cues are replaced with surgical state indicators, matte translucent surfaces, and crisp hairline dividers that reinforce structural architecture.

## Colors

The palette is engineered exclusively for an OLED-optimized, dark-first operational environment. Color serves strictly communicative and structural roles:

- **Canvas & Surface Base (`#0c0e12`):** Pure obsidian void providing deep contrast while minimizing power consumption during continuous background processing.
- **Surface Elevation 1 (`#14171f`):** Low-tier surface for side navigation rails, background containers, and grouped list backings.
- **Surface Elevation 2 (`#1b202c`):** Interactive card planes, active panels, input shells, and sheet surfaces.
- **Structural Dividers (`#283042`):** Precision 1px boundaries, defining strict zone delineation without high visual friction.
- **Primary AI Identity (`#38bdf8` / `#0284c7`):** Electric Cyan-Azure spectrum reserved for active synthesis indicators, user focus rings, key action buttons, and active thread navigation.
- **Telemetry & State Accents:**
  - *Emerald Green (`#10b981`):* Confirmed on-device inference, local enclave active, network air-gap verified.
  - *Amber (`#f59e0b`):* Context limits approaching, parameter mutation, confirmation required.
  - *Rose (`#f43f5e`):* Hardware throttling, model execution failure, thread interrupted.
- **Text & Glyph Hierarchy:**
  - *High Emphasis:* `#f1f5f9` (Slate 100)
  - *Medium Emphasis:* `#94a3b8` (Slate 400)
  - *Muted / Telemetry:* `#64748b` (Slate 500)

## Typography

The typographic strategy balances human-scale conversational interaction with cold, terminal-grade precision:

- **Inter** acts as the primary cognitive layer for dialogue transcripts, contextual system explanations, high-level headers, and navigational affordances. Tight negative tracking on larger display headlines creates a grounded architectural tone.
- **JetBrains Mono** governs hardware diagnostics, active context token metrics, memory/tensor utilization readings, execution durations, and raw script/code outputs. It grounds the operating workspace in objective mechanics, treating the machine not as a conversational toy, but as a responsive local instrument.
- Optical sizing and distinct weight stepping (600 for headers, 500 for anchors/labels, 400 for continuous text) maintain high legibility under varying ambient light conditions and high pixel densities.

## Layout & Spacing

The layout is architected around native Android window size classes (Compact, Medium, Expanded), adapting seamlessly across handsets, foldables, and desktop/tablet docks:

- **Compact (Mobile < 600dp):** Single-column dynamic viewport. Bottom sheet dock for prompt entry, edge-to-edge conversational streams with 16dp margins, and horizontal swipe-aware hardware telemetry shelves.
- **Medium (Foldables/Tablets 600–839dp):** Dual-pane split: 40% persistent session drawer and execution log, 60% active synthesis stream. 24dp screen-edge padding.
- **Expanded (Large Tablet/Desktop 840dp+):** Three-tier console: Left utility/thread navigation rail (72dp collapsed or 280dp expanded), center chat/workspace thread (max-width 840dp centered), and right telemetry pane for live model parameters, temperature sliders, memory cache, and quantization metrics.

Spacing adheres strictly to a 4dp base grid (multiples of 4/8/16/24/32dp). Dense telemetry widgets compact to `space-xs` and `space-sm` for high-density information display, while interactive tap targets consistently satisfy the 48x48dp minimum accessible threshold.

## Elevation & Depth

Visual depth eschews diffused drop shadows and faux lighting sources. Instead, depth is established via tonal layering, controlled opacity, and hairline boundary lines:

1. **Surface Tiers (Compose Tonal Hierarchy):**
   - **Level 0 (Canvas Base):** `#0c0e12` (Background workspace canvas).
   - **Level 1 (Subordinate Planes):** `#14171f` (Drawer rails, stationary app bars, persistent toolbars).
   - **Level 2 (Active Cards & Floating Containers):** `#1b202c` (Chat messages, diagnostic blocks, interactive cards).
   - **Level 3 (Transient Overlays):** `#222938` (Command palettes, context popovers, modal bottom sheets).

2. **Hairline Outlines:**
   Every floating surface and interactive module features a crisp 1px stroke of `#283042`. On selected or active elements, this border sharpens into `#38bdf8` at 60% opacity.

3. **Subtle Glass Translucency:**
   Floating input bars, dynamic system pills, and app bar scrollers utilize backdrop blur (`backdrop-filter: blur(16px)`) over an 85% alpha surface (`#0c0e12d9` or `#14171fe6`). This ensures background information rhythm remains subtly legible without hindering contrast.

## Shapes

The shape hierarchy is directly informed by Material You (M3) geometric standards, tempered with technical sharpness:

- **Base Radius (`rounded-md`, 8px / 0.5rem):** Standard for utility buttons, telemetry badges, code blocks, parameter sliders, and text fields.
- **Container Radius (`rounded-lg`, 16px / 1rem):** Standard for message bubbles, telemetry panels, modal cards, and floating inspector panes.
- **Structural Radius (`rounded-xl`, 24px / 1.5rem):** Reserved for bottom sheets, quick-action navigation docks, and primary input capsules.
- **Pill Geometry (Full circular radius):** Used strictly for operational status chips (e.g., "ON-DEVICE // ACTIVE", "AIR-GAPPED"), state badges, and small contextual tool selectors.

## Components

### Buttons
- **Primary Action (Execute/Send):** Solid background of `#0284c7`, shifting to `#38bdf8` on active state. Crisp white text (`#f8fafc`), 8px border radius, 44dp height. Zero drop shadow; uses an internal 1px semi-transparent highlight stroke on top edge.
- **Secondary / Utilitarian:** Transparent background, 1px border in `#283042`, text in `#94a3b8`. On touch/hover: background transitions to `#1b202c` and border shifts to `#38bdf8`.
- **Destructive / Abort:** Border and glyphs rendered in `#f43f5e`, background transparent or `#f43f5e14` (8% alpha).

### Input Fields & Prompt Capsules
- Floating container docked to bottom edge with 20px bottom margin on mobile. Built with `#14171f` background, 1px border of `#283042`, and a 16px corner radius.
- Includes trailing utility actions (voice transcription toggle, token usage counter, file/context injection pin). Focused state transitions border to `#38bdf8` with a 2px outer glow restricted to 4px spread.

### Diagnostic & Status Chips
- Height: 24dp. Border radius: 9999px (full pill). Monospaced typography (`label-mono-sm`).
- **Verified Offline:** `#10b9811f` surface, `#10b981` text, 6px static emerald LED indicator dot.
- **Context Usage:** `#14171f` surface, `#283042` border, slate text showing active token count (`2,840 / 8,192 t`).

### Cards & Synthesis Threads
- **User Message Card:** Aligned right, `#1b202c` surface, border `#283042`, 16px corner radius with lower-right notch reduction (4px radius) for directional flow.
- **AI Workspace Output:** Spans full content width without distinct bounding box or sits within a Level 1 `#14171f` surface container. Code snippets inside output use a nested `#0c0e12` block with a 1px `#283042` border, a persistent copy button, and syntax highlight themes tuned to slate/cyan/emerald tones.

### Lists & Key-Value Telemetry
- Divided by 1px horizontal strokes of `#283042`. Left column features high-contrast label in Inter (`body-md`), right column houses aligned mono-data in JetBrains Mono (`label-mono-md`).

### Checkboxes, Toggles & Radios
- Native switch controls styled with a compact 20dp track in `#1b202c` bordered by `#283042`. Active thumb shifts to `#38bdf8` with track fill at `#0284c740`. Checkboxes use sharp 4px corners with `#38bdf8` fill and `#0c0e12` checkmark.