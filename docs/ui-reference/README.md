# Jiva AI approved design reference — 17 November 2026

The six PNG images in this folder are the user's chosen visual design direction and must remain attached to future UI-change reviews. They are **visual mockups, not screenshots of running Flutter widgets**.

## Implementation contract

- Preserve original Jiva orbital emblem: use the packaged branding assets; do not regenerate artwork for each screen.
- Deep navy app bar, cyan/teal accents, soft ice background, light cards, and adaptive dark-mode colors.
- Home dashboard: talk/type and quick actions before advanced configuration; quick actions fixed 3×2 at ordinary phone widths, adaptive 2-column on narrow screens.
- Button labels stay one line and shrink to fit available space rather than wrapping or clipping. Maintain ~48dp touch targets; buttons are sized consistently.
- Technical/long instructions go inside expandable sections (Voice & Listening, Visible-screen Assistance, Offline AI & Maintenance).
- Existing callable workflows, review/approval gates, API-key security and audio states must be preserved. No mock button should be added without an implementation.
- About Jiva AI: visible version/build, independent legal developer and proposed/unregistered Sanwariya brand. Do not claim unsupported privacy guarantees.
- Native Android splash is OS-controlled. Post-launch Flutter branded transition is animated; the master splash artwork is 1080×1920 portrait.

## Differences from concept art

The mockups contain illustrative UI and illustrative reminder records. We never seed fake reminders, bypass permissions or introduce nonfunctional actions to match them. Source buttons retain their original behavior. The code follows the approved sizing/placement and design system; **pixel-for-pixel equality requires device tests** at target densities and accessibility font scales.
