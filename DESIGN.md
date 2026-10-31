# Mira Design DNA

Visual source of truth for all UI work. Where this conflicts with an implementation, the
implementation is wrong.

Distilled from Moleskine/Bonobo **Timepage**, with one deliberate divergence: Timepage never
renders a paragraph, and Mira is mostly paragraphs. Everything about chrome, density, motion and
colour comes from Timepage. Everything about reading long-form text does not.

---

## 1. Brand world

A near-black canvas. One accent colour that the user chooses, which then owns every active state in
the app. Cards float on the canvas with no dividers and almost no elevation. Labels are tiny,
uppercase and widely tracked. Titles are heavy and quiet. The mark is a single-stroke butterfly with
no colour of its own, so it sits on any theme.

The emotional target: **opening Mira should feel like opening a good notebook, not launching an
app.** If a screen looks busy, it is wrong. If a screen needs a divider to be legible, the spacing
is wrong.

Three rules that carry most of the weight:

1. **No dividers.** Separation comes from space and from surface, never from a line. The only
   hairlines in the app are the hour rail and the heatmap's today-ring.
2. **No elevation.** No drop shadows. Surfaces are distinguished by lightness alone.
3. **Empty is a state worth designing.** A month with nothing in it should look composed and
   intentional, not broken. Most of this app's life is spent mostly empty.

---

## 2. Colour tokens

Dark is the tuned mode; light is derived and must be checked independently, never assumed.

### Neutrals

| Token | Dark | Light | Role |
|---|---|---|---|
| `canvas` | `#0A0A0B` | `#FBFAF8` | App background. Dark is cool near-black; light is warm paper, never pure white |
| `surface` | `#141416` | `#FFFFFF` | Entry cards, sheets, list rows |
| `surfaceRaised` | `#1C1C1F` | `#F4F2EE` | Sheets over sheets, menus, selected rows |
| `surfaceSunken` | `#060607` | `#F0EEE9` | Hour-rail gutter, recessed wells |
| `ink` | `#F2F2F5` | `#121214` | Titles, entry body |
| `inkMuted` | `#8A8A92` | `#6E6E76` | Metadata, section labels, inactive months |
| `inkFaint` | `#4A4A52` | `#B4B4BC` | Empty-day numerals, disabled, placeholder |
| `outline` | `#232327` | `#ECEAE6` | Hour rail and today-ring only. Not for separating content |
| `scrim` | `#000000` @ 60% | `#121214` @ 32% | Behind modal sheets |

No pure black text in light mode and no pure white text in dark mode — both vibrate against the
canvas. `#F2F2F5` on `#0A0A0B` is ~18.5:1, far past AA, and reads calmer than `#FFFFFF`.

### Accents

Ten curated themes. The accent owns: selected day fill, today ring, the record button, active tab,
attachment dots, links, the heatmap ramp, and focus rings. Nothing else is coloured, ever.

| Name | Hex | OKLCH (L C H) |
|---|---|---|
| **Ember** (default) | `#F0533D` | `0.645 0.182 32` |
| Peach | `#F2A878` | `0.782 0.102 54` |
| Saffron | `#E8B23A` | `0.794 0.143 83` |
| Moss | `#5FA87A` | `0.643 0.085 152` |
| Teal | `#3FA8A0` | `0.643 0.082 185` |
| Cobalt | `#4A7DF0` | `0.591 0.177 266` |
| Iris | `#7C6CF0` | `0.588 0.185 288` |
| Magenta | `#D95AA8` | `0.631 0.177 349` |
| Slate | `#8A93A8` | `0.627 0.033 262` |
| Bone | `#E8E2D6` | `0.907 0.019 85` |

`Slate` and `Bone` exist so the app can be run almost monochrome. They must be tested — a near-neutral
accent is the case where "accent vs. `inkMuted`" collapses, and the selected-day state has to stay
legible.

`onAccent` is computed, not stored: `#0A0A0B` when the accent's OKLCH L > 0.70, otherwise `#FFFFFF`.
Under that rule Peach, Saffron and Bone take dark glyphs and the rest take light.

### The heatmap ramp

The heatmap is the signature of the app and it must not be built by applying alpha to the accent.
Alpha-over-black desaturates toward grey and the five steps end up muddy and unevenly spaced.

Derive five steps in **OKLCH**, holding hue constant and interpolating lightness and chroma from a
near-canvas anchor to the full accent:

```
ramp[i], i in 0..4, t = i / 4
  L = lerp(L_anchor, L_accent, t)
  C = lerp(C_anchor, C_accent, t)
  H = H_accent

dark:   L_anchor = 0.28,  C_anchor = 0.022
light:  L_anchor = 0.93,  C_anchor = 0.016
```

Step 0 is a day with one entry. Step 4 is a day at or above the busy threshold. A day with **zero**
entries draws no circle at all — just the numeral in `inkFaint`. That absence is what makes a
consistent month read as a constellation instead of a grid.

The busy threshold is **adaptive**: the 90th percentile of entries-per-day over the visible month,
clamped to a minimum of 3. A month where you wrote once a day should still show variation, and a
fixed threshold would render a typical month entirely at step 0.

---

## 3. Typography

Two families, both bundled as Compose resources (required for desktop and wasm regardless).

- **Inter Tight** — all chrome. Tight grotesque, holds up at small sizes, excellent uppercase.
- **Literata** — entry body only. Designed for screen reading, low stroke contrast so it doesn't
  thin out on a black canvas, and it makes the app feel written-in rather than used.

The rule: **if the user typed it, it is Literata. If the app says it, it is Inter Tight.**

| Style | Family | Size / LH | Weight | Tracking | Use |
|---|---|---|---|---|---|
| `monthTitle` | Inter Tight | 34 / 38 | Bold | +0.5, UPPER | `JANUARY` on the calendar |
| `dayTitle` | Inter Tight | 24 / 28 | Bold | +0.4, UPPER | `TUESDAY` on the day screen |
| `dayDate` | Inter Tight | 11 / 14 | Medium | +1.6, UPPER | `30 OCT 2026` beneath it |
| `sectionLabel` | Inter Tight | 10 / 14 | Bold | +1.8, UPPER | `ENTRIES`, `ATTACHMENTS`, `ALL DAY` |
| `yearNav` | Inter Tight | 11 / 16 | Medium | +1.2, UPPER | `JAN FEB MAR` month strip |
| `dayNumeral` | Inter Tight | 13 / 13 | Medium | 0 | Numbers inside heatmap circles |
| `weekdayHead` | Inter Tight | 10 / 12 | Medium | +1.0, UPPER | `SU M TU W TH F SA` |
| `entryBody` | **Literata** | 16 / 26 | Regular | 0 | The journal text itself |
| `entryBodyLarge` | **Literata** | 18 / 30 | Regular | 0 | Single-entry reading view |
| `entryExcerpt` | **Literata** | 14 / 21 | Regular | 0 | 2-line clamp in timeline rows |
| `timestamp` | Inter Tight | 11 / 14 | Medium | +0.6 | `11:47 PM` |
| `button` | Inter Tight | 13 / 16 | SemiBold | +0.3 | All buttons |
| `meta` | Inter Tight | 12 / 16 | Regular | 0 | Durations, file sizes, counts |

Entry body gets a **measure cap of 68 characters** on desktop and web. Compose Multiplatform will
happily run a line of journal text across a 2560px monitor; `MiraContentWidth` (inherited from Basil's
`ui` module) is the place to enforce this.

---

## 4. Shape, spacing, density

| Token | Value |
|---|---|
| `radius.card` | 14dp — entry cards, attachment tiles |
| `radius.sheet` | 28dp — bottom sheets, compose sheet |
| `radius.control` | 10dp — inputs, segmented controls |
| `radius.pill` | 999dp — chips, record button, day circles |
| `radius.media` | 12dp — video posters, waveform tiles |
| Spacing scale | 2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 48 / 64 |
| Phone gutter | 20dp |
| Desktop gutter | 32dp, content capped at 720dp |
| Day circle | 34dp diameter, 6dp grid gap |
| Card padding | 16dp, 20dp for the reading view |
| Min touch target | 44dp, even where the visual is 34dp |

Gaps between stacked entry cards: **8dp**. Between sections: **32dp**. The jump from 8 to 32 is what
replaces dividers — if sections ever feel merged, the section gap has drifted, not the need for a
line.

---

## 5. Motion

Timepage's reputation is motion. Three durations, no more:

| Token | Duration | Easing | Use |
|---|---|---|---|
| `quick` | 140ms | `EaseOutQuad` | Press states, checkbox, dot fills |
| `standard` | 240ms | `EaseInOutCubic` | Sheets, tab changes, list item enter |
| `expressive` | 420ms | `EaseOutBack` (overshoot 1.04) | Day circle → day screen, record start |

Specific transitions that are not optional:

- **Day circle → day screen.** Shared-element: the circle is the shared bounds, expanding into the
  day header while the heatmap fades back. Pattern already exists in Basil as
  `RecipeSharedTransition.kt`.
- **Month paging.** Horizontal swipe with the month title cross-fading and sliding 8dp against the
  drag direction — the parallax is what sells it.
- **Record button.** Tap scales 1.0 → 0.92 → 1.06 over `expressive`, then a continuous pulse at
  1.0 ↔ 1.03, 1400ms, for the duration of the recording. The pulse is the recording indicator; no
  separate blinking dot.
- **Entry save.** The compose sheet dismisses downward while the new card fades and rises 12dp into
  the day list. Never a toast.
- **Heatmap first paint.** On month open, circles fade in with a 12ms per-day stagger in reading
  order. Runs once per month view, not on every recomposition.

Reduced-motion: all `expressive` transitions degrade to `standard` crossfades, the record pulse
becomes a static ring, and the heatmap stagger is dropped.

---

## 6. Iconography

Thin-line, 1.5dp stroke, 24dp box, round caps and joins — matching the single-stroke weight of the
butterfly mark. No filled icons anywhere except the record button's inner shape. Inherits `ink` or
`inkMuted`; coloured only when it represents an active state.

Set: calendar, timeline, search, settings, plus, microphone, video, text, play, pause, trash,
attachment, chevron, close, cloud-pending, cloud-done, github, download.

---

## 7. Screen anatomy

### Calendar (home)

```
┌──────────────────────────────────┐
│  2026  ·                      ⚙  │  year, inkMuted, 11sp
│  JAN FEB MAR APR MAY JUN         │  yearNav — current white, rest inkFaint
│  JUL AUG SEP [OCT] NOV DEC       │
│                                  │  32dp
│  OCTOBER                         │  monthTitle
│                                  │  20dp
│  SU  M  TU  W  TH  F  SA         │  weekdayHead, inkMuted
│   ·  ·  ·  1  ②  ③  4           │  circles = ramp step, bare = no entries
│   5  ⑥  ⑦  ⑧  9  ⑩ ⑪           │
│  ...                             │
│                                  │
│                           ( + )  │  accent record/compose FAB
└──────────────────────────────────┘
```

The year strip is the whole navigator — tap a month to jump, tap the year to open the year view.
No month arrows, no dropdown.

### Day

Header: `TUESDAY` / `30 OCT 2026` centred, back-chevron left, `+` in a circle right. Then
`ENTRIES` section label and the entry cards, chronological. Each card: timestamp in the top-left as
`timestamp`, attachment dots in accent on the top-right, Literata body below, clamped to 6 lines
with a soft fade rather than an ellipsis.

Empty day: the section label, and a single line of `inkFaint` Literata — *"Nothing yet."* — with
generous space above it. Nothing else. No illustration, no button; the `+` is already there.

### Compose sheet

Opens as a sheet at 92% height. A date field at the top (defaults to today, tappable to move the
entry). Then the Literata text field, immediately focused, no placeholder chrome beyond a faint
*"Write…"*. A bottom bar with three thin-line affordances — text / mic / video — and a Save. Audio
and video capture expand in place within the sheet; they do not push a new screen.

### Timeline

Reverse-chronological across all time, grouped by month with sticky `sectionLabel` headers. Rows
are date, excerpt (2-line Literata clamp), attachment dots. Scroll position is persisted per session
so returning doesn't dump you at today.

### Search

Field at the top in `radius.control`. Below it, filter chips: date range, has audio, has video, tags.
Results reuse timeline rows with the matched term in accent. Empty state shows recent searches.

### Year view

12 month blocks, each a mini heatmap of bare circles without numerals, 8dp per day. Tapping a month
drills into the calendar. This is the one screen that is purely ornamental, and it should be the
best-looking thing in the app.

### Settings

Grouped sections with `sectionLabel` headers, no cards: Account, Appearance (theme picker — a row of
accent swatches plus light/dark/system), Sync (Firebase status, GitHub archive, "Back up now"),
Storage (usage readout, cache cap, pinned years, orphan sweep), About (version, check for updates).

---

## 8. Platform divergence

The design is identical everywhere except where a platform forces a difference:

- **Desktop** — content capped at 720dp and centred; the calendar and the day view sit side by side
  above 1100dp, matching Timepage's iPad dual-panel. Keyboard: `⌘N` new entry, `⌘F` search,
  `⌘[`/`⌘]` month paging.
- **Web** — identical to desktop. No media kept locally, so attachment tiles always show the
  streaming state. Initial paint must not wait on the wasm database; the calendar chrome renders
  first and the heatmap fills in.
- **iOS / Android** — bottom tab bar: calendar, timeline, search, settings. Safe-area handled by
  `MiraSafeArea`. The record button respects the home indicator.

---

## 9. What this design explicitly rejects

- Material 3's default colour system. Mira uses its own tokens; `MaterialTheme` is a carrier, not a
  source of colour.
- Elevation, drop shadows, and surface tinting by elevation level.
- Dividers between list items.
- More than one accent colour on screen at a time.
- Toasts and snackbars. State changes are shown by the thing that changed.
- Illustrated empty states.
- Any colour that is not a neutral, the accent, or a step of the accent's ramp.
