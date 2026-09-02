---
version: beta
name: Engineering-evidence-workbench
description: An Apple-inspired engineering workspace built for reading code evidence, reviewing change impact, and judging repository health. It keeps Apple's clarity and restraint while replacing the marketing-gallery model with a semantic evidence system.

colors:
  identity: "#17324d"
  action: "#2f7fd3"
  evidence: "#168fa3"
  success: "#218a60"
  warning: "#c27a19"
  danger: "#c14f45"
  model: "#6d5db5"
  canvas: "#f4f7f9"
  surface: "#ffffff"
  surface-subtle: "#f8fafb"
  border: "#d8e2e8"
  border-strong: "#bdcbd5"
  text-primary: "#1e2b34"
  text-regular: "#3f515e"
  text-muted: "#60717d"
  text-subtle: "#758590"

typography:
  display:
    fontFamily: 'Inter, "Segoe UI Variable Display", "Microsoft YaHei UI", "PingFang SC", system-ui, sans-serif'
  body:
    fontFamily: 'Inter, "Segoe UI Variable Text", "Microsoft YaHei UI", "PingFang SC", system-ui, sans-serif'
    fontSize: 15px
    lineHeight: 1.55
  utility:
    fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, "Liberation Mono", monospace'
  page-title:
    fontSize: 30px
    fontWeight: 700
    lineHeight: 1.2
  section-title:
    fontSize: 21px
    fontWeight: 650
    lineHeight: 1.3
  card-title:
    fontSize: 17px
    fontWeight: 650
    lineHeight: 1.4
  control:
    fontSize: 14px
    fontWeight: 600
    lineHeight: 1.4
  caption:
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.5
  micro:
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1.4
  code:
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.75

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 20px
  xl: 24px
  xxl: 32px

rounded:
  compact: 6px
  control: 8px
  surface: 10px
  pill: 9999px
---

# Engineering Evidence Design System

## 1. Product and audience

This product is an engineering context and change-governance workspace for developers, maintainers, reviewers, and platform administrators. Its main job is to help a user establish what is true about a repository, understand why it is true, and decide what to do next.

The design is inspired by Apple's clarity and restraint, not by Apple's product-marketing page composition. Large photography, near-empty hero tiles, one-color branding, and 10px legal text are not appropriate foundations for a dense engineering application.

## 2. Design thesis

> Quiet surfaces, readable information, explicit evidence.

The interface should feel like a well-edited technical dossier:

- Typography carries hierarchy before decoration does.
- Color communicates source, confidence, status, or action.
- Whitespace separates decisions, not merely content blocks.
- Cards are structural containers, not the default answer to every layout problem.
- Evidence provenance remains visible wherever a conclusion is presented.

The signature element is the **Evidence Spine**: a consistent visual chain that distinguishes Git facts, code facts, verified knowledge, graph inference, unknowns, and model suggestions. Other surfaces stay comparatively quiet so this evidence language remains memorable.

## 3. Color system

### Core palette

| Role | Token | Hex | Use |
|---|---|---:|---|
| Product identity | `{colors.identity}` | `#17324d` | Navigation, major headings, stable product identity |
| Action | `{colors.action}` | `#2f7fd3` | Primary actions, links, selected navigation, keyboard focus |
| Evidence | `{colors.evidence}` | `#168fa3` | Code relationships, graph evidence, provenance connections |
| Verified | `{colors.success}` | `#218a60` | Current, healthy, verified, complete, ready |
| Attention | `{colors.warning}` | `#c27a19` | Suspect, degraded, incomplete, needs review |
| Critical | `{colors.danger}` | `#c14f45` | Failed, blocking, stale, destructive consequences |

`{colors.model}` (`#6d5db5`) is a provenance color reserved for model-generated suggestions and graph inference that is not yet verified. It must never make generated content look equivalent to fact.

### Neutral surfaces

- Canvas: `{colors.canvas}` (`#f4f7f9`)
- Primary surface: `{colors.surface}` (`#ffffff`)
- Subtle surface: `{colors.surface-subtle}` (`#f8fafb`)
- Border: `{colors.border}` (`#d8e2e8`)
- Strong border: `{colors.border-strong}` (`#bdcbd5`)

### Semantic rules

- Blue means the user can act or navigate.
- Cyan means a relationship or evidence source is being shown.
- Green means the system has verified a healthy/current state.
- Amber means the state is usable but requires attention or review.
- Red means a blocking, failed, stale, or destructive state.
- Violet means a model or inference boundary.
- Gray means neutral or unknown; unknown must never be shown as success.
- Never rely on color alone. Pair color with a label, icon, status word, or border treatment.
- Do not assign different colors to KPI cards solely to make a dashboard look richer.

## 4. Typography

### Font roles

- Display and headings use the display stack. It provides a clear structural voice without imitating a marketing headline.
- Chinese body and UI copy use the body stack, prioritizing Microsoft YaHei UI and PingFang SC fallbacks for platform readability.
- Paths, hashes, versions, symbols, line numbers, and evidence identifiers use the utility stack.

### Type scale

| Role | Size | Weight | Use |
|---|---:|---:|---|
| Page title | 28–32px | 700 | One per route or major workspace |
| Section title | 20–22px | 650 | Major page regions |
| Card/panel title | 16–18px | 600–650 | Surface headings and drawer titles |
| Body | 15–16px | 400 | Explanations, findings, user-facing narrative |
| Controls/table | 14–15px | 500–600 | Buttons, inputs, navigation, table cells |
| Secondary | 13px | 400–600 | Metadata, compact descriptions, timestamps |
| Micro | 12px | 600 | Eyebrows, status labels, compact provenance |

### Readability guardrails

- No meaningful text may render below 12px.
- 12px is reserved for compact metadata, status labels, and provenance markers.
- Body copy defaults to 15px with a line height of at least 1.55.
- Chinese paragraphs should not use aggressive negative letter-spacing.
- Code uses 13px minimum and a line height around 1.7–1.8.
- Buttons and form fields use at least 14px text and 36px height.
- Touch targets are at least 44px where the same control is expected on mobile.

## 5. Layout

The default page structure is an engineering dossier:

```text
┌─ Project / version identity ───────────────────── Primary action ┐
├─ Verified ── Needs review ── Blocking ── Unknown status ledger ┤
├───────────────────────────────────┬─────────────────────────────┤
│ Main evidence, history, or change │ Obligations, readiness, risk│
│ narrative                         │ and secondary controls       │
└───────────────────────────────────┴─────────────────────────────┘
```

### Page frame

- Desktop sidebar: approximately 216px.
- Page gutters: 20px on standard desktop, reduced gradually below tablet width.
- Main content uses the available workspace width; avoid arbitrary narrow maximum widths on data-heavy routes.
- Standard surface gap: 16px.
- Panel padding: 16–24px according to density.
- Separate page scrolling from nested panel scrolling deliberately; do not allow fixed shells to hide bottom content.

### Density

- Dense does not mean small. Preserve readable type and reduce redundant chrome instead.
- Prefer dividers, alignment, and grouped whitespace over nesting multiple bordered cards.
- Summary rows should show state and meaning, not a collection of decorative numbers.

## 6. Components

### Navigation

- Active navigation uses the action-blue soft surface and a stronger text weight.
- Section labels are 12px minimum and may use uppercase/letter spacing only for short utility labels.
- Global repository and version context must remain readable at 13px or above.

### Buttons and inputs

- Default control radius: 8px.
- Primary buttons use `{colors.action}` and white text.
- Success, warning, and danger colors are not alternative primary-button themes; use them when the action itself has that semantic consequence.
- Focus uses a visible 3px translucent ring and must not be removed.
- Avoid full-pill buttons for ordinary toolbar actions. Pills are reserved for status and compact filtering.

### Tables

- Table text: 14px minimum.
- Headers use muted text and 600–650 weight, not tiny uppercase labels.
- Default row height: 56–60px.
- Hover may use a subtle cool surface; selection requires an additional action/evidence marker.

### Cards and panels

- Surface radius: 8–10px.
- Use a 1px neutral border for structure.
- Soft shadows are allowed only where elevation clarifies interaction, such as a dialog, floating drawer, or selected workspace tab.
- Do not give every card a shadow.

### Status and provenance

- Status badges include text; color alone is insufficient.
- Git and code facts use action/evidence families.
- Verified knowledge uses green.
- Unknown or unbound content stays neutral gray.
- Model suggestions use violet and must carry a visible model boundary label.
- Stale or blocking evidence uses amber/red according to severity.

### Empty, loading, and failure states

- Empty states explain the next useful action.
- Loading states identify what is being prepared or verified.
- Failure states state what failed and how the user can recover.
- Do not use vague copy such as “Something went wrong.”

## 7. Motion and depth

- Motion is functional: panel opening, selection feedback, progress, and evidence-path emphasis.
- Default transitions stay in the 120–200ms range.
- Respect `prefers-reduced-motion`.
- Avoid ambient animation, decorative gradients, and unrelated floating effects.
- The Evidence Spine may use one restrained path-reveal or state-transition treatment; scattered animation is discouraged.

## 8. Responsive behavior

| Breakpoint | Behavior |
|---|---|
| ≥ 1200px | Full sidebar, two-column dossier layouts, complete context controls |
| 900–1199px | Narrower secondary rails; tables retain readable type and may scroll horizontally |
| 761–899px | Single-column content where needed; controls wrap instead of shrinking text |
| ≤ 760px | Compact top navigation, single-column panels, 44px touch targets |

Responsive rules must change layout before reducing typography. The 12px minimum remains in force at every breakpoint.

## 9. Do and don't

### Do

- Preserve Apple's clarity, restraint, alignment, and careful typography.
- Use the semantic palette consistently across every route.
- Keep body and control text readable under real Chinese content.
- Make evidence source and confidence visible near conclusions.
- Let one strong evidence structure carry the product identity.
- Test fixed-height workspaces for bottom obstruction and nested-scroll traps.

### Don't

- Don't imitate Apple product marketing tiles or photography-first composition.
- Don't return to a single-blue system for all states and sources.
- Don't use 7–11px text for meaningful information.
- Don't use color as decoration or as the only status cue.
- Don't solve density by shrinking type.
- Don't place arbitrary maximum widths on project overview or change-review workspaces.
- Don't add gradients, glass effects, or shadows without a functional reason.

## 10. Implementation contract

Global tokens live in `frontend/src/styles/main.css`. Shared alignment and workspace patterns live in `frontend/src/styles/design-alignment.css`. Page-local styles may consume global tokens but should not redefine competing brand or semantic palettes.

When adding or changing a page:

1. Use global text, color, border, and surface tokens.
2. Keep all explicit text at 12px or above.
3. Assign color only when the value has a defined semantic role.
4. Verify desktop and mobile layout before reducing font size.
5. Run the frontend build and tests.
6. Visually inspect the affected page for clipping, bottom obstruction, and contrast.
