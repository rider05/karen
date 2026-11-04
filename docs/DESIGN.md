---
name: Professional Workspace
colors:
  # Light theme (default)
  background: '#f8fafc'
  surface: '#ffffff'
  surface-dim: '#f1f5f9'
  surface-bright: '#ffffff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f8fafc'
  surface-container: '#f1f5f9'
  surface-container-high: '#e8edf3'
  surface-container-highest: '#e2e8f0'
  on-background: '#0f172a'
  on-surface: '#0f172a'
  on-surface-variant: '#475569'
  inverse-surface: '#1e293b'
  inverse-on-surface: '#f1f5f9'
  outline: '#94a3b8'
  outline-variant: '#e2e8f0'
  surface-tint: '#2563eb'
  primary: '#2563eb'
  on-primary: '#ffffff'
  primary-container: '#dbeafe'
  on-primary-container: '#1e3a8a'
  inverse-primary: '#93c5fd'
  secondary: '#475569'
  on-secondary: '#ffffff'
  secondary-container: '#e2e8f0'
  on-secondary-container: '#1e293b'
  tertiary: '#059669'
  on-tertiary: '#ffffff'
  tertiary-container: '#d1fae5'
  on-tertiary-container: '#065f46'
  warning: '#d97706'
  on-warning: '#ffffff'
  warning-container: '#fef3c7'
  on-warning-container: '#92400e'
  error: '#dc2626'
  on-error: '#ffffff'
  error-container: '#fee2e2'
  on-error-container: '#991b1b'
  surface-variant: '#e2e8f0'
  # Dark theme overrides (applied under dark mode)
  dark:
    background: '#0f1115'
    surface: '#161a20'
    surface-container-low: '#1a1f27'
    surface-container: '#1f2530'
    surface-container-high: '#262d3a'
    on-surface: '#e5e9f0'
    on-surface-variant: '#a3aebf'
    outline: '#64708a'
    outline-variant: '#2b3342'
    primary: '#60a5fa'
    on-primary: '#0b1f44'
    primary-container: '#1e3a8a'
    on-primary-container: '#dbeafe'
    tertiary: '#34d399'
    warning: '#fbbf24'
    error: '#f87171'
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
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  title-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
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
  label-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: 0.01em
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
  xl: 1.25rem
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
elevation:
  level-0: none
  level-1: '0 1px 2px rgba(15, 23, 42, 0.06)'
  level-2: '0 2px 8px rgba(15, 23, 42, 0.08)'
  level-3: '0 8px 24px rgba(15, 23, 42, 0.12)'
---

## Brand & Style

This design system defines a clean, professional, and trustworthy workspace for a private, local-first AI assistant. The interface is calm, structured, and easy to scan: it prioritizes clarity, consistency, and predictable behavior over decoration. Users should feel they are working in a dependable productivity tool, not a novelty.

The look follows modern enterprise software conventions: generous whitespace, a restrained neutral palette, a single confident blue accent, and soft, low-contrast elevation. Privacy and on-device status are communicated through quiet, well-labelled indicators rather than dramatic effects. The system is **light-first** with a fully supported **dark theme** that follows the device setting.

## Colors

Color is used sparingly and always carries meaning. Neutrals form 90% of the interface; the accent is reserved for primary actions, focus, and selection.

- **Canvas (`#f8fafc`):** App background behind all content.
- **Surface (`#ffffff`):** Cards, sheets, input fields, and panels.
- **Surface Subtle (`#f1f5f9`):** Navigation rails, grouped list backing, table headers, hover states.
- **Border (`#e2e8f0`):** 1px dividers and component outlines. Stronger outline (`#94a3b8`) is used only for form controls at rest.
- **Primary (`#2563eb`):** Primary buttons, links, active navigation, focus rings, selected states. Hover/pressed: `#1d4ed8`. Tint for selected backgrounds: `#dbeafe`.
- **Status Colors:**
  - *Success (`#059669`, tint `#d1fae5`):* On-device processing confirmed, task completed, saved.
  - *Warning (`#d97706`, tint `#fef3c7`):* Context nearing limit, action needs confirmation.
  - *Error (`#dc2626`, tint `#fee2e2`):* Failures, destructive actions, interrupted tasks.
- **Text Hierarchy:**
  - *Primary:* `#0f172a` (Slate 900), headings and body.
  - *Secondary:* `#475569` (Slate 600), supporting text and labels.
  - *Tertiary:* `#64748b` (Slate 500), metadata, timestamps, helper text.
  - *Disabled:* `#94a3b8` (Slate 400).

All text and interactive element colors meet WCAG AA contrast (4.5:1 for body text, 3:1 for large text and UI components) in both themes.

### Dark Theme

Dark mode uses a soft charcoal rather than pure black: canvas `#0f1115`, surface `#161a20`, raised surface `#1f2530`, border `#2b3342`. The primary shifts to `#60a5fa` for contrast, and status colors lighten (`#34d399`, `#fbbf24`, `#f87171`). Elevation in dark mode is shown through lighter surface tiers and borders instead of shadows.

## Typography

A single sans-serif family keeps the interface cohesive; monospace is used only where data should be read as data.

- **Inter** is used for all headings, body text, buttons, and navigation. Larger headings use slight negative tracking for a polished, compact feel.
- **JetBrains Mono** is limited to code, token counts, model parameters, timings, and file paths.
- **Weights:** 600 for headings and titles, 500 for labels and buttons, 400 for body text.
- **Scale:** 32 / 24 / 20 / 16 / 14 / 13 / 11. Body copy defaults to 14px on dense screens and 16px for conversation content. Line length for reading is capped at roughly 70 characters.

## Layout & Spacing

The layout adapts to Android window size classes using a consistent 4dp grid (4 / 8 / 12 / 16 / 24 / 32).

- **Compact (< 600dp):** Single column. 16dp screen margins. Top app bar with title and overflow menu, bottom navigation (3 to 5 items), and a prompt bar docked above the keyboard.
- **Medium (600 to 839dp):** Two panes. A 320dp session list on the left and the active conversation on the right. 24dp margins.
- **Expanded (840dp+):** Three regions. A navigation rail (80dp collapsed, 280dp expanded), a centered conversation column (max-width 760dp), and an optional 320dp details panel for settings, sources, and model information.

Content is grouped using whitespace first and dividers second. Section spacing is 24dp, component spacing is 16dp, and related items are separated by 8dp. All touch targets are at least 48 x 48dp.

## Elevation & Depth

Depth is subtle and functional. Surfaces are separated by a combination of 1px borders and light, diffused shadows.

| Level | Use | Light theme | Dark theme |
|---|---|---|---|
| 0 | Canvas, flat content | None | None |
| 1 | Cards, inputs, list containers | 1px border + soft shadow `0 1px 2px` | 1px border, surface `#161a20` |
| 2 | Sticky bars, dropdown menus | Shadow `0 2px 8px` | Surface `#1f2530` |
| 3 | Dialogs, bottom sheets, popovers | Shadow `0 8px 24px` + scrim at 40% | Surface `#262d3a` + scrim at 60% |

Modal surfaces dim the background with a scrim. Translucent blur is reserved for the top app bar when content scrolls beneath it (surface at 90% opacity, 12px blur).

## Shapes

Shapes are softly rounded and consistent, giving a friendly but professional character.

- **Small (4dp):** Checkboxes, tags, inline code.
- **Default (8dp):** Buttons, text fields, dropdowns, tooltips.
- **Medium (12dp):** Cards, list groups, code blocks.
- **Large (16dp):** Dialogs, message bubbles, panels.
- **Extra Large (20dp):** Bottom sheets and the prompt bar.
- **Full:** Status chips, avatars, filter chips, switches.

## Components

### Buttons
- **Primary:** Fill `#2563eb`, white text, 8dp radius, 40dp height (48dp touch target), 16dp horizontal padding, label-md. Hover `#1d4ed8`, pressed `#1e40af`. Disabled uses `#e2e8f0` fill with `#94a3b8` text.
- **Secondary:** White fill, 1px `#cbd5e1` border, `#0f172a` text. Hover fills `#f1f5f9`.
- **Tonal:** Fill `#dbeafe`, text `#1e3a8a`. Used for secondary emphasis actions.
- **Text button:** No fill or border, text `#2563eb`. Used for low-emphasis actions such as Cancel and Learn more.
- **Destructive:** Fill `#dc2626` with white text for confirmed deletion; outlined variant for initial triggers.
- Only one primary button per view or dialog.

### Input Fields & Prompt Bar
- **Text field:** White fill, 1px `#cbd5e1` border, 8dp radius, 48dp height, 14px text. Label above the field, helper text below in tertiary color. Focus shows a 2px `#2563eb` border; error shows `#dc2626` border with message below.
- **Prompt bar:** Docked at the bottom with 16dp margins, white surface, 1px border, 20dp radius, level-1 shadow. Contains a multi-line input (up to 6 lines), attachment button, voice button, and a circular primary send button. Focus state adds a 2px primary border. A small token counter in label-mono-sm sits at the trailing edge when the context is over 70% full.

### Status Chips & Badges
- Height 24dp, full pill radius, label-md text, 8dp horizontal padding, optional 6dp leading dot.
- **On-device:** `#d1fae5` fill, `#065f46` text, green dot. Label: "On-device".
- **Warning:** `#fef3c7` fill, `#92400e` text.
- **Error:** `#fee2e2` fill, `#991b1b` text.
- **Neutral (e.g. context usage):** `#f1f5f9` fill, `#475569` text, mono value such as `2,840 / 8,192`.

### Cards & Conversation
- **Card:** White surface, 1px `#e2e8f0` border, 12dp radius, 16dp padding, level-1 shadow. Interactive cards raise to level-2 on hover and show a primary border when selected.
- **User message:** Right-aligned bubble, fill `#2563eb`, white text, 16dp radius with a 4dp bottom-right corner. Maximum width 80% of the column.
- **Assistant message:** Left-aligned, full column width, no bubble, `#0f172a` text, with a small avatar and name above the first message in a group. Actions (copy, regenerate, feedback) appear in a subtle row beneath the message.
- **Code block:** `#f1f5f9` fill, 1px border, 12dp radius, JetBrains Mono 13px, header row with language label and copy button.

### Lists & Key-Value Rows
- Rows are at least 56dp tall with 16dp horizontal padding and 1px `#e2e8f0` dividers. The leading label uses Inter body-md in primary text color; the trailing value uses secondary text, or JetBrains Mono for technical values. Selected rows use a `#eff6ff` background with a 3dp primary indicator on the leading edge.

### Navigation
- **Top app bar:** 56dp tall, white surface with a bottom border on scroll, title in title-md.
- **Bottom navigation:** 64dp tall, surface background, selected item shows a primary icon and label inside a `#dbeafe` pill indicator.
- **Navigation rail / sidebar:** Surface Subtle background, 1px right border, selected item highlighted with a primary-tint fill and primary text.

### Switches, Checkboxes & Radios
- **Switch:** 40 x 24dp track. Off: `#cbd5e1` track, white thumb. On: `#2563eb` track, white thumb.
- **Checkbox:** 20dp, 4dp radius, 1.5px `#94a3b8` border. Checked: `#2563eb` fill with white check.
- **Radio:** 20dp circle, checked state uses a primary ring with an 8dp inner dot.

### Dialogs, Sheets & Feedback
- **Dialog:** White surface, 16dp radius, level-3 shadow, 24dp padding, maximum width 480dp. Title, concise body, and right-aligned actions (text button, then primary button).
- **Bottom sheet:** 20dp top radius, drag handle, level-3 shadow.
- **Snackbar:** Inverse surface (`#1e293b`), white text, 8dp radius, optional text action, auto-dismisses after 4 seconds.
- **Empty and loading states:** Use a simple icon, one line of explanation, and a single suggested action. Loading content uses neutral skeleton placeholders with a gentle pulse; avoid spinners for content that takes longer than one second to load.

## Motion & Accessibility

- **Motion:** Short and purposeful. 150ms for state changes, 250ms for transitions between views, standard ease-out curve. Honor the system "reduce motion" setting by replacing movement with simple fades.
- **Focus:** All interactive elements show a visible 2px primary focus ring with a 2px offset.
- **Screen readers:** Every icon-only button has a content description. Status is never conveyed by color alone; chips always include a text label.
- **Scaling:** Layouts support system font scaling up to 200% without clipped content.
