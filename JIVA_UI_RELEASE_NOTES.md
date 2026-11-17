# Jiva AI UI Branding Release · 1.0.1+2

## New work
- Approved Jiva emblem extracted from the master artwork; no re-generation for app assets.
- Branded Android app launcher and portrait 1080×1920 splash artwork.
- Android 12+ starts with system-managed splash icon (OS restriction); Flutter then displays a 1.2s subtle animated full-screen branded handoff.
- Centralized Material 3 navy/teal/cyan design system, including light/dark cards, input fields, buttons, dialog and bottom-sheet themes.
- Compact app-bar Jiva emblem, On switch, wake-word control, Gemini key control, About menu.
- About Jiva AI dialog: explicit version, build, developer/operator and proposed-brand status.
- Added consistent quick-action icons and one-line button label helper for targeted controls.
- Version changed from `1.0.0+1` to `1.0.1+2`.

## Preserved
- No intentional changes to domain logic, Gemini API key authorization, voice capture, offline LLM, permissions, accessibility actions, reminders, or background scheduling.
- Final uploaded source ZIP used as the bytewise comparison baseline. Verify latest local GitHub main has same baseline before applying changes.

## Validation status
- XML well-formed and assets verified locally; full Flutter analyze/test and release build MUST be run in your Windows Flutter development environment.
- Runtime UI layout, permissions, splash timing and device-install behavior NOT yet verified.
- Screenshots shown earlier were visual concepts; the shipped theme implements their design system, not pixel-for-pixel reproduction of every card.

## Build and push (VS Code PowerShell)
Run inside the extracted project root after independently verifying your remote history:

```powershell
cd flutter
flutter pub get
flutter analyze
flutter test
flutter build apk --release
cd ..
git status
git diff --stat
git add -A
git diff --cached --name-only
git commit -m "Jiva AI 1.0.1+2: branding, native splash, animated handoff and About UI"
git fetch origin main
git log --oneline HEAD..origin/main
git push origin main
```

If the remote ahead log has commits, reconcile them before push. Never use force push. Never commit any private API keys or signing material.
