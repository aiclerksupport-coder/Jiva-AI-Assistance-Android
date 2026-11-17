# Jiva AI — Community Launch Kit

**Prepared:** 17 November 2026  
**Primary product:** Flutter/Dart Android Jiva AI Assistance  
**Current package version:** `1.0.1+2`  
**Public distribution status:** Not yet cleared for a general binary release

## Positioning

**Short description (GitHub About field, copy manually):**

> Flutter-first multilingual Android AI assistant with voice, reminders, optional Gemini BYOK, and consent-based phone assistance.

**Suggested GitHub topics** (add in repository About settings):

`flutter`, `dart`, `android`, `ai-assistant`, `voice-assistant`, `gemini-api`, `accessibility`, `open-source`

Never claim broad phone control, local-only Gemini, guaranteed offline intelligence, zero data sharing, perfect speech recognition or Play Store readiness.

## Visual assets

- Brand: `flutter/assets/branding/jiva_logo_master.png` and `jiva_logo_header.png`.
- Approved conceptual screen design: `docs/ui-reference/` (six images).
- **Public screenshots:** Capture real app screens on a test phone, redact personal data, and label them as actual screenshots. Do not mislabel AI mockups as released UI.
- **GitHub social preview:** Manually upload a landscape 1280×640 banner using the approved Jiva emblem and the positioning statement above via repository Settings → General → Social preview. This document does not claim that a social preview has been set.
- **Demo recording:** 1080×1920 portrait, 30–60 sec for short-form and 2–3 min for detailed tour. Never show Gemini/Picovoice keys, fingerprints, contacts, PINs, private screens or notification contents.

## Demo storyboard (30–45 seconds)

1. 0–5s: Jiva branding and a real home-screen capture, caption: "Jiva AI — Flutter-first Android assistant".
2. 5–13s: Speak or type a supported command; show the confirmation and response.
3. 13–20s: Open an allowed system screen or app and show that the user remains in control.
4. 20–28s: Create a reminder and show the scheduled item (no personal details).
5. 28–36s: Show supported language/device settings and optional Gemini connection without revealing credentials.
6. 36–45s: End card: "Open source • Explore the code • Share feedback" with official repository URL.

Use actual verified capabilities, not simulated actions or fake results. If a feature is not stable on the demo phone, omit it.

## Proposed launch order

**Week 1:** README, screenshots, project About/topics, issue templates, verified device matrix and release checklist.  
**Week 2:** Record genuine demos and publish to your own YouTube/LinkedIn accounts.  
**Week 3:** Share a technical write-up in appropriate Flutter/Android communities, following each community's promotional rules.  
**Week 4:** Publish what testers actually found, fix high-priority issues and update release notes.

Useful destinations: [r/FlutterDev](https://www.reddit.com/r/FlutterDev/), [Hacker News Show](https://news.ycombinator.com/show), [Product Hunt](https://www.producthunt.com/) and [LinkedIn](https://www.linkedin.com/). Avoid mass-tagging, vote solicitation or spam.

## Genuine feedback targets — not guarantees

- 10–20 testers on varied Android versions/device manufacturers.
- 5–10 actionable bug reports or experience notes.
- Track legitimate stars, issues, clones and unique visitors from GitHub Insights.

Do not buy stars, install bots, fake downloads or ask for coordinated upvotes.

## Binary release gates (all required before describing as public-ready)

- [ ] Reproducible Flutter analysis, tests and release build with saved results.
- [ ] App starts and completes key voice/reminder/permission workflows on several Android devices, including a clean install.
- [ ] Confirm final signing key and install/update continuity; source docs currently state debug signing for the release pipeline.
- [ ] Check requested Android permissions and third-party Gemini/recognizer data disclosures.
- [ ] Audit secret exposure, hardcoded credentials, logs and public APK contents.
- [ ] Test owner's biometric/PIN protection for save/remove, denial, cancellation and lockout.
- [ ] Verify versionName/versionCode and the package's update compatibility.
- [ ] Finalize privacy policy and legal notices with applicable professional review.
- [ ] Publish a signed APK only after checking checksum, provenance and distribution policy.
- [ ] Attach signed release checksum and clear manual installation/update steps.

**A successful build and ADB install on one phone is valuable, but does not satisfy these gates alone.** Avoid uploading an APK to public Releases before passing them.

## Manual GitHub setup not performed by this document commit

1. Repository About: description and eight topics from above.
2. Settings → General → Social preview: approved 1280×640 banner.
3. Settings → Features: enable Discussions if you want an open discussion forum.
4. Settings → Security: enable private vulnerability reporting if available.
5. Releases: create a release only after the binary gates pass.
6. YouTube/LinkedIn/Reddit/HN/Product Hunt: publish authentic demos through your accounts when ready.

## Ownership and project identity

Jiva AI Assistance is independent. **Sanwariya** is a proposed, unregistered umbrella brand. Legal individual developer/operator: **Kalpesh Chhabhaiya**. Do not imply registered-company status or any official temple affiliation.
