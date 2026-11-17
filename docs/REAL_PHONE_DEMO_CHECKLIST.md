# Jiva AI — Real Phone Demo and Screenshot Checklist

Use this checklist when preparing media that might be shared publicly.

## Capture conditions

- Use a test device or a privacy-safe user profile; turn on Do Not Disturb.
- Mask phone number, saved contacts, chat messages, notifications, PIN entry, API keys, tokens, fingerprint prompts and account information.
- Check the captured status bar, recent apps, keyboard suggestions and notification shade before publishing.
- Record version and Android device/OS in private QA notes.
- Keep the approved orbital Jiva logo and current theme without editing source screenshots to imply nonexistent UI.

## Real screens to capture

- [ ] Home dashboard
- [ ] Voice and listening settings
- [ ] Reminders
- [ ] About Jiva AI and visible version
- [ ] Permission/confirmation workflow (redacted)
- [ ] Optional screen assistance, using an innocuous test app only

## Functional proof

- [ ] A typed local command works without configured Gemini (where supported)
- [ ] Gemini key Test & Save passes or shows truthful error
- [ ] Cancelled biometric challenge does not modify saved key
- [ ] Voice command respects user confirmation
- [ ] Call or message opens handoff; do not claim it sent a call/message
- [ ] Reminder triggers under documented device conditions
- [ ] App resumes after background/foreground transitions

## Publishing checks

Every image must be labelled **real captured UI** or **concept mockup**. Keep raw originals locally for troubleshooting, but never publish an unredacted phone capture. Do not promise compatibility on devices or Android versions you did not test.
