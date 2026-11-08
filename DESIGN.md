# DESIGN.md — Soft Health UI (Neumorphic · Glowing Data)

> The visual spec for this project, for developers and AI agents who create or change UI.
> It comes from two reference screens, "Sleep Analysis" and "Dashboard", each in a light and a dark version.
> All color values were sampled from the reference images and then turned into tokens.
> **Use only these tokens. Do not add new color values.**

---

## 0. Quick rules for agents (read first)

1. **Look and feel**: Soft UI / neumorphism with glowing gradient data. The background is a low-saturation blue-grey. Cards look raised above the background. Data shapes use a glowing **blue → cyan → aqua** gradient.
2. **Raised on the same color**: Cards are almost the same color as the background. Depth comes from **a pair of shadows**: light at the top-left, dark at the bottom-right. Do **not** use borders. Do **not** put flat white cards on a grey background.
3. **Color is for data only**: Text always uses neutral ink colors. Brand gradients are only for charts, progress, selected states and icons. **Never use cyan, aqua or mint for text.** They do not have enough contrast on the light background.
4. **Big numbers lead**: Each card has at most one large value (`9h`, `78%`, `5400`). Units and labels sit next to it in a smaller, lower-contrast style.
5. **Generous radii**: Cards 20, icon buttons 14, tracks fully rounded. No sharp corners.
6. **CJK**: Latin text and numbers use Inter. Chinese uses HarmonyOS Sans SC, with system fonts as fallback (see §3). Chinese headings use weight 600 at most, never 700 or 800.
7. **Light and dark**: All colors come from CSS variables or theme tokens. Do not hard-code colors inside components.
8. **Never use**: pure black `#000` text, saturated red backgrounds, hard drop shadows (like `0 2px 4px rgba(0,0,0,.3)`), stacks of 1px grey dividers, or emoji as icons (the star and moon in the references are drawn as vector icons).

---

## 1. Principles

| Principle | Meaning |
|---|---|
| Soft | A low-contrast background and large, blurry shadows make the UI look pressed out of one material. |
| Glowing data | Data shapes have a gradient and a soft outer glow. They are the only saturated color on screen. |
| One focus per screen | Each screen has one hero visual (such as the sleep clock ring). The rest of the information goes into a grid of matching cards. |
| Room to breathe | 16 between cards and 20 inside cards, so content never feels crowded. |
| Numbers first | Large number with a small unit. The label sits above the number in a muted style. |

---

## 2. Color

### 2.1 Light theme (default)

| Token | Value | Use |
|---|---|---|
| `--bg-canvas` | `#E3E8F3` | Outer canvas / page background (outside the screen) |
| `--bg-screen` | `#F1F3FA` | App screen background |
| `--surface` | `#F4F7FD` | Cards, buttons, bottom tab bar |
| `--surface-sunken` | `#E8EDF7` | Sunken areas: progress tracks, empty bar slots |
| `--shadow-dark` | `rgba(163, 177, 205, 0.45)` | Neumorphic dark shadow (bottom-right) |
| `--shadow-light` | `rgba(255, 255, 255, 0.95)` | Neumorphic light shadow (top-left) |
| `--ink-900` | `#141A2B` | Page titles, greeting |
| `--ink-700` | `#313949` | Big numbers, card titles, body text |
| `--ink-500` | `#5D6280` | Secondary text, subtitles, inactive tabs |
| `--ink-400` | `#878EA3` | Axes, units, helper labels |
| `--ink-300` | `#AAB1BE` | Placeholder, disabled, denominators like `/8000` |
| `--divider` | `rgba(93, 98, 128, 0.12)` | Dashed lines, grid (use sparingly) |

### 2.2 Dark theme

| Token | Value | Use |
|---|---|---|
| `--bg-canvas` | `#373B40` | Outer canvas |
| `--bg-screen` | `#1E2023` | App screen background |
| `--surface` | `#26282B` | Cards, buttons |
| `--surface-sunken` | `#1B1D20` | Sunken tracks |
| `--shadow-dark` | `rgba(0, 0, 0, 0.55)` | Dark shadow |
| `--shadow-light` | `rgba(255, 255, 255, 0.045)` | Light shadow (very faint) |
| `--ink-900` | `#F2F3F5` | Page titles |
| `--ink-700` | `#E3E5E8` | Big numbers, card titles |
| `--ink-500` | `#9A9DA3` | Secondary text |
| `--ink-400` | `#73767C` | Axes, units |
| `--ink-300` | `#56595E` | Disabled, denominators |
| `--divider` | `rgba(255, 255, 255, 0.07)` | Dashed lines, grid |

In dark mode, cards also get a hairline `1px solid rgba(255,255,255,0.035)` border to define their edges. Shadows alone do not separate layers on a dark background.

### 2.3 Brand and data colors (both themes)

| Token | Value | Use |
|---|---|---|
| `--brand-blue` | `#2F7BFF` | Primary: active tab, icons in icon buttons, `>` chevrons |
| `--brand-blue-strong` | `#0A6CFF` | Pressed state, or when more contrast is needed on light |
| `--accent-cyan` | `#18C2FF` | Middle of gradients |
| `--accent-aqua` | `#00EFE0` | End of gradients, glow color |
| `--accent-mint` | `#2CF2C8` | REM, heart-rate reference line |
| `--accent-lime` | `#F2F27A` | End of the activity gradient |
| `--accent-yellow` | `#FFE45C` | Awake, star icon |
| `--success` | `#12B886` | Up arrow `↑`, positive change (safe for text) |
| `--warning` | `#FFB020` | Warnings (not in the references; use when needed) |
| `--danger` | `#FF5A6E` | Abnormal values (not in the references; use when needed) |

### 2.4 Gradients

```css
/* Primary: bars, heart-rate line (vertical: blue on top, aqua at the bottom) */
--grad-primary: linear-gradient(180deg, #2F7BFF 0%, #18C2FF 50%, #00EFE0 100%);

/* Horizontal progress (steps): aqua on the left → blue on the right */
--grad-progress: linear-gradient(90deg, #00E5F0 0%, #2F7BFF 100%);

/* Activity progress: aqua → yellow-green */
--grad-activity: linear-gradient(90deg, #00E6B8 0%, #8EF0A0 60%, #F2F27A 100%);

/* Clock ring: starts blue, goes clockwise to aqua */
--grad-ring: conic-gradient(from 0deg, #2F7BFF 0deg, #18C2FF 140deg, #00EFE0 260deg);

/* Area fill under line charts */
--grad-area: linear-gradient(180deg, rgba(0, 239, 224, 0.18) 0%, rgba(0, 239, 224, 0) 100%);
```

### 2.5 Sleep stage colors (hypnogram only)

| Stage | Chinese label | Color |
|---|---|---|
| Deep | 深睡 | `#3D88FE` |
| Light | 浅睡 | `#06E6FC` |
| REM | 快速眼动 | `#2CF2C8` |
| Awake | 清醒 | `#FFE45C` (vertical gradient down to `#06E6FC`) |

---

## 3. Typography (including Chinese)

### 3.1 Font stacks

```css
--font-sans:
  "Inter",                      /* Latin + numerals, matched first */
  "HarmonyOS Sans SC",          /* Chinese first choice: geometric, full weight range, pairs with Inter */
  "MiSans",                     /* Chinese alternative */
  "PingFang SC",                /* iOS / macOS system Chinese */
  "Microsoft YaHei UI",         /* Windows */
  "Noto Sans SC",               /* Android / Linux / open-source fallback */
  "Source Han Sans SC",
  system-ui, -apple-system, sans-serif;

--font-num: "Inter", "SF Pro Display", system-ui, sans-serif;  /* numbers only */
```

**Why the Latin font comes first**: Chinese fonts include Latin letters and numerals, but these are usually less refined than Inter's. With Inter first, numbers and English text render in Inter. Chinese characters fall through to the Chinese font because Inter has no glyphs for them. The big numbers in the references (`9h`, `22:55`) are a geometric sans, and Inter is the closest match.

### 3.2 Choosing a Chinese font

| Font | Fit | License | Recommendation |
|---|---|---|---|
| **HarmonyOS Sans SC** | ★★★★★ Square, geometric, open counters, closest to Inter's feel; 8 weights | Free for commercial use under Huawei's license (check the official terms) | **First choice**, self-host on web |
| MiSans | ★★★★☆ Also modern and geometric, slightly tighter | Free for commercial use under Xiaomi's license (check the official terms) | Alternative |
| PingFang SC | ★★★★☆ Built into Apple systems, renders well | System font, cannot be redistributed | Use directly on native iOS / macOS |
| Noto Sans SC / Source Han Sans SC | ★★★☆☆ Safe choice, a little more formal | SIL OFL, open source | Open-source fallback, Android |
| Microsoft YaHei UI | ★★☆☆☆ Windows fallback only | System font | Fallback |

- **Native apps**: On iOS, use the system fonts (SF Pro + PingFang SC); there's no need to bundle a Chinese font. On Android, use the system font or bundle HarmonyOS Sans SC.
- **Web**: Chinese font files are several MB each. **Always subset them or slice them by `unicode-range`** (for example with a tool like `cn-font-split`), and use `font-display: swap`. If only a few heading glyphs are needed, subset just those.

### 3.3 Type scale

Based on a 375-wide design (px / pt).

| Token | Size / line height | Weight (Latin / Chinese) | Use | Example |
|---|---|---|---|---|
| `display-xl` | 48 / 52 | 700 / — | Hero number (numbers only) | `9h` |
| `display-sub` | 22 / 28 | 400 / 400 | Second line under the hero number | `15 min`, `15 分钟` |
| `title-lg` | 22 / 30 | 600 / 600 | Page title, greeting | Sleep Analysis, 早上好，Natali |
| `stat` | 26 / 32 | 600 / — | Card value (numbers only) | `78%`, `5400`, `22:55` |
| `title-md` | 17 / 24 | 600 / 500 | Card title | Sleep duration, 心率 |
| `body` | 15 / 22 | 400 / 400 | Body, list rows | Trending Workouts |
| `label` | 13 / 18 | 500 / 400 | Card labels, subtitles, units | Steps, Last 7 days, `/8000` |
| `caption` | 12 / 16 | 500 / 400 | Axes, legends | `12 AM`, Deep 13% |
| `tab` | 11 / 14 | 500 / 500 | Bottom tab labels | Dashboard |

Big numbers use `letter-spacing: -0.02em`. Numbers that change (timers, step counts) use `font-variant-numeric: tabular-nums` so the width does not shift.

### 3.4 Chinese typesetting rules

- **Line height**: Chinese body text needs at least 1.5 (in the scale above, use `size × 1.5`, rounded, for Chinese). Numbers and one-line titles can be tighter.
- **Weight**: Chinese glyphs have more strokes, so the same weight looks heavier than Latin. Use 500–600 for Chinese headings, **never 700 or more**. Heavier weights look muddy on the soft background.
- **Letter spacing**: Keep Chinese at `0`. **Never use negative tracking on CJK text**, even when the Latin numbers next to it use it.
- **Minimum size**: At least 11px for Chinese (tabs), and at least 13px for anything read as text.
- **Mixed text**: Put a half-width space between Chinese and numbers or Latin words (`近 7 天`, `9 小时 15 分`). Where the browser supports `text-autospace`, it can do this for you.
- **Units**: Numbers in large Inter; the Chinese unit one size smaller in `--ink-400`, for example `84` + `分钟`.
- **Punctuation**: Full-width Chinese punctuation; times use a half-width colon `22:55`.
- **No italics**. Chinese has no true italic.
- **Length**: Chinese labels are usually shorter than English ones, but keep tab labels to 2–4 characters.
- **Mixed baselines**: Chinese and Latin fonts have different vertical metrics. Set line heights explicitly and check that numbers and Chinese text line up in pills and stat rows.

### 3.5 Chinese copy for the reference screens

| English | Chinese |
|---|---|
| Sleep Analysis | 睡眠分析 |
| Good morning, Natali | 早上好，Natali |
| Quality / Early bedtime | 睡眠质量 / 早睡时间 |
| Steps / Activity Time | 步数 / 活动时长 |
| Sleep duration · Last 7 days | 睡眠时长 · 近 7 天 |
| Heart Rate · Last 24 hours | 心率 · 近 24 小时 |
| Deep / Light / REM / Awake | 深睡 / 浅睡 / 快速眼动 / 清醒 |
| Trending Workouts | 热门训练 |
| Dashboard / Activity / Timetable / Profile | 首页 / 活动 / 日程 / 我的 |
| Su Mo Tu We Th Fr Sa | 日 一 二 三 四 五 六 |

---

## 4. Shape, spacing, elevation

### 4.1 Radius

| Token | Value | Use |
|---|---|---|
| `--radius-screen` | 40 | Phone screen / outermost container |
| `--radius-card` | 20 | Cards |
| `--radius-card-sm` | 18 | Two-column small cards |
| `--radius-button` | 14 | Square icon buttons (44×44) |
| `--radius-pill` | 999 | Progress bars, bar tracks, tags |

### 4.2 Spacing (4 / 8 grid)

| Token | Value | Use |
|---|---|---|
| `--space-1` | 4 | Icon to text |
| `--space-2` | 8 | Label to value |
| `--space-3` | 12 | Between legend items |
| `--space-4` | 16 | Between cards, gap in two-column rows |
| `--space-5` | 20 | Screen side margins, card padding |
| `--space-6` | 24 | Between sections |
| `--space-8` | 32 | Space around the hero visual |

### 4.3 Elevation (the core of neumorphism)

```css
/* Light */
--elev-raised:                    /* cards, buttons */
  -6px -6px 16px var(--shadow-light),
   8px  8px 22px var(--shadow-dark);
--elev-raised-sm:                 /* icon buttons, small cards */
  -3px -3px 8px var(--shadow-light),
   4px  4px 12px var(--shadow-dark);
--elev-pressed:                   /* pressed state, sunken tracks */
  inset 2px 2px 5px var(--shadow-dark),
  inset -2px -2px 5px var(--shadow-light);
--elev-highlight: inset 0 1px 0 rgba(255,255,255,0.9); /* top highlight on cards (light only) */

/* Data glow */
--glow-aqua: drop-shadow(0 0 6px rgba(0, 239, 224, 0.55));
--glow-blue: drop-shadow(0 0 6px rgba(47, 123, 255, 0.45));
```

Dark mode uses the same structure; the variables swap to the dark values. Raise glow opacity to about 0.7 in dark mode so it reads on the dark background.

---

## 5. Components

### 5.1 Card
- Background `--surface`, radius `--radius-card`, padding 20, shadow `--elev-raised` + `--elev-highlight`.
- Layout: header row (`title-md` + optional subtitle in `label` / `--ink-400`) with a `>` chevron on the right (`--brand-blue`, 20px), then content.
- Clickable cards switch to `--elev-pressed` when pressed, 150ms transition.

### 5.2 IconButton
- 44×44, radius 14, `--surface` + `--elev-raised-sm`.
- Icon 22px, outline style, 1.75 stroke, `--brand-blue`.
- Used for back, share, filters (the `<`, share and slider icons in the references).

### 5.3 Header
- Left: optional back button + title in `title-lg` / `--ink-900`, with the date below in `label` / `--ink-500` (e.g. "Friday, 12 Feb, 2022").
- Right: one icon button. 20 above and below.

### 5.4 RingGauge (sleep clock hero)
- Outer: a large raised disc (`--elev-raised`, about 75% of the screen width).
- Progress ring: 14–16 stroke, round caps, `--grad-ring` + `--glow-aqua`, starts at 12 o'clock and runs clockwise.
- Inner dial: a sunken circle (a softer `--elev-pressed`) with 12 tick marks and numbers at 3/6/9/12 (`caption` / `--ink-500`).
- Center: `display-xl` number over a `display-sub` unit, both centered.
- Motion: on enter, the ring draws from 0 to its value in 900ms, `cubic-bezier(.22,1,.36,1)`.

### 5.5 StatTile (two columns)
- Two equal columns, gap 16, `--radius-card-sm`.
- Row 1: label in `label` / `--ink-500` + an ⓘ icon on the right (16px, `--ink-400`).
- Row 2: colored icon (star in `--accent-yellow`, moon in a `--brand-blue` gradient) + trend arrow `↑` in `--success` + value in `stat`.

### 5.6 ProgressBar
- 6 tall, fully rounded; track `--surface-sunken` (optionally with `--elev-pressed`).
- Fill: `--grad-progress` for counts like steps, `--grad-activity` for activity; optional `--glow-aqua`.
- Above: label (`label`), then value in `stat` + denominator or unit in `label` / `--ink-300` (`5400 /8000`, `84 min`).

### 5.7 BarChart (last 7 days)
- Each bar is a fully rounded **sunken track** (22–24 wide, `--surface-sunken`) holding a **thin capsule fill** that rises from the bottom (about 10 wide, `--grad-primary`, blue on top and aqua at the bottom, with `--glow-aqua`).
- Days with no data show only the empty track.
- Y-axis labels in `caption` / `--ink-400`, no grid lines; X-axis is the weekday.

### 5.8 LineChart (heart rate)
- 2.5 stroke, round joins, smoothed curve; stroke is a horizontal blue → aqua gradient, or `--accent-cyan`.
- Optional area fill `--grad-area`.
- Reference line: `--accent-mint` dashed, `stroke-dasharray: 4 4`, 1.5 wide.
- Faint vertical dashed lines (`--divider`) at both ends mark the time range.
- Axis labels in `caption` / `--ink-400`.

### 5.9 Hypnogram (sleep stages)
- Step chart: a horizontal bar for each sleep stage in time order, 12 tall with radius 4, joined by vertical gradient connectors.
- Colors from §2.5; awake spikes use a yellow → cyan vertical gradient.
- Legend on top: colored dot (8px) + stage name + percentage (`caption`, percentage bold).
- Bottom: bedtime and wake time in `caption`, joined by a dashed line.

### 5.10 ListRow
- 56 tall, card style; title in `body` / `--ink-700` on the left, `>` in `--brand-blue` on the right.

### 5.11 TabBar
- A raised card (`--elev-raised`) with 16 margin on each side, radius 20, 64 tall.
- 4 tabs: icon 24 + label in `tab`. Active tab: filled icon in `--brand-blue`. Inactive: outline icon in `--ink-500`.
- No indicator bar; color and filled vs outline icons show the active tab.

---

## 6. Icons

- Outline icons on a 24 grid, 1.75 stroke, round caps and joins; Lucide or Phosphor (Regular) recommended.
- Active or emphasized icons switch to the filled version or a gradient fill.
- Colored decorative icons (star, moon) are filled with `--accent-yellow` / `--brand-blue`, optionally with a light gradient. No emoji.

---

## 7. Motion

| Case | Duration | Easing |
|---|---|---|
| Press (raised → pressed shadow) | 150ms | `ease-out` |
| Card enter | 300ms, staggered 40ms | `cubic-bezier(.22,1,.36,1)` |
| Chart draw (ring, rising bars, line stroke) | 700–900ms | `cubic-bezier(.22,1,.36,1)` |
| Theme switch | 200ms background transition | `ease` |

Respect `prefers-reduced-motion`: when it is on, skip chart animations and show the final state.

---

## 8. Accessibility

- Text colors are only `--ink-*`, `--success` and `--brand-blue-strong`. Cyan, aqua, mint and yellow are **for graphics only**.
- Neumorphic shadows have very low contrast. **Every interactive element needs an extra cue** (an icon, text or chevron); shadows alone are not enough.
- Focus ring: `outline: 2px solid var(--brand-blue); outline-offset: 3px;`
- Charts need a text equivalent (`aria-label` or a visually hidden data table).
- Tap targets at least 44×44.

---

## 9. Implementation reference

### 9.1 CSS variables

```css
:root {
  color-scheme: light;
  --bg-canvas: #E3E8F3;
  --bg-screen: #F1F3FA;
  --surface: #F4F7FD;
  --surface-sunken: #E8EDF7;
  --shadow-dark: rgba(163, 177, 205, 0.45);
  --shadow-light: rgba(255, 255, 255, 0.95);
  --ink-900: #141A2B; --ink-700: #313949; --ink-500: #5D6280;
  --ink-400: #878EA3; --ink-300: #AAB1BE;
  --divider: rgba(93, 98, 128, 0.12);
  --card-border: transparent;

  --brand-blue: #2F7BFF; --brand-blue-strong: #0A6CFF;
  --accent-cyan: #18C2FF; --accent-aqua: #00EFE0; --accent-mint: #2CF2C8;
  --accent-lime: #F2F27A; --accent-yellow: #FFE45C; --success: #12B886;

  --radius-card: 20px; --radius-button: 14px; --radius-pill: 999px;

  --font-sans: "Inter", "HarmonyOS Sans SC", "MiSans", "PingFang SC",
    "Microsoft YaHei UI", "Noto Sans SC", "Source Han Sans SC",
    system-ui, -apple-system, sans-serif;
}

[data-theme="dark"] {
  color-scheme: dark;
  --bg-canvas: #373B40;
  --bg-screen: #1E2023;
  --surface: #26282B;
  --surface-sunken: #1B1D20;
  --shadow-dark: rgba(0, 0, 0, 0.55);
  --shadow-light: rgba(255, 255, 255, 0.045);
  --ink-900: #F2F3F5; --ink-700: #E3E5E8; --ink-500: #9A9DA3;
  --ink-400: #73767C; --ink-300: #56595E;
  --divider: rgba(255, 255, 255, 0.07);
  --card-border: rgba(255, 255, 255, 0.035);
}

.card {
  background: var(--surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-card);
  padding: 20px;
  box-shadow:
    -6px -6px 16px var(--shadow-light),
     8px  8px 22px var(--shadow-dark);
}

body {
  font-family: var(--font-sans);
  color: var(--ink-700);
  background: var(--bg-screen);
  -webkit-font-smoothing: antialiased;
}

/* Chinese content */
:lang(zh) { line-height: 1.5; letter-spacing: 0; }
.num { font-variant-numeric: tabular-nums; letter-spacing: -0.02em; }
```

### 9.2 Tailwind (optional)

```js
// tailwind.config.js (excerpt)
theme: {
  extend: {
    colors: {
      canvas: 'var(--bg-canvas)', screen: 'var(--bg-screen)',
      surface: 'var(--surface)', sunken: 'var(--surface-sunken)',
      ink: { 900: 'var(--ink-900)', 700: 'var(--ink-700)', 500: 'var(--ink-500)',
             400: 'var(--ink-400)', 300: 'var(--ink-300)' },
      brand: 'var(--brand-blue)', aqua: 'var(--accent-aqua)', cyan: 'var(--accent-cyan)',
      mint: 'var(--accent-mint)', success: 'var(--success)',
    },
    borderRadius: { card: '20px', btn: '14px' },
    boxShadow: {
      raised: '-6px -6px 16px var(--shadow-light), 8px 8px 22px var(--shadow-dark)',
      'raised-sm': '-3px -3px 8px var(--shadow-light), 4px 4px 12px var(--shadow-dark)',
      pressed: 'inset 2px 2px 5px var(--shadow-dark), inset -2px -2px 5px var(--shadow-light)',
    },
    fontFamily: { sans: ['Inter', 'HarmonyOS Sans SC', 'MiSans', 'PingFang SC',
      'Microsoft YaHei UI', 'Noto Sans SC', 'system-ui', 'sans-serif'] },
  },
}
```

---

## 10. Pre-merge checklist

- [ ] Every color comes from a token; no hard-coded colors after switching between light and dark
- [ ] Cards use the paired shadows, with no grey borders (except the dark-mode hairline)
- [ ] Gradients and glow appear only on data, progress and selected states
- [ ] At most one big number per card; units and labels are muted
- [ ] Chinese weight ≤ 600, line height ≥ 1.5, no negative tracking
- [ ] Numbers and Latin text render in Inter (Latin font first in the stack)
- [ ] Spaces between Chinese and numbers / Latin words
- [ ] Interactive elements are ≥ 44×44 and have a focus style
- [ ] Chinese web fonts are subset, or system fonts are used
