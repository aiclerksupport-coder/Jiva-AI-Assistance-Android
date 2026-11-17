# Jiva AI — responsive UI redesign on v1.0.1+2

Baseline: `Jiva-AI-Assistance-Android-v1.0.1+2-JivaThemeSplashAbout-SOURCE.zip`.
Application version unchanged: `1.0.1+2`, by request to replace the same version.

Implemented:
- Home reordered around branded hero, full voice interaction card, fixed responsive 3x2 Quick Actions and live conversation.
- Replaced variable-width Wrap quick buttons with adaptive constrained grid, with no-wrap, scale-down labels and minimum tap target.
- Introduced reusable `JivaActionTile`, `JivaSectionHeading`, and gradient action button widgets.
- Compact expandable Offline AI/Maintenance, Voice/Listening and Android accessibility/workflow settings, keeping existing callbacks and security checks.
- Accessibility navigation fixed 3-across, with smaller stable buttons.
- New About Jiva AI detail screen using legally accurate proposed brand/developer wording, and links to app info via built-in About dialog.
- Saved all six approved design mockups as immutable visual references in `docs/ui-reference/` for follow-up diffs.
- Added small widget tests for button label sizing, tap behavior and About version.

No domain or service Dart files are intentionally edited. Offline AI, key authentication, speech, workflows, and permissions retain their existing source methods.

LIMITATIONS: Dart/Flutter SDK unavailable in the assembly environment. No `flutter analyze`, `flutter test`, Android release build, physical device UX test or GitHub push was executed. Run the project's `BUILD_FULL.ps1` on Windows before accepting the release as verified.
