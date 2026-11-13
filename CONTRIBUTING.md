# Contributing to Jiva

Thanks for your interest. Jiva is an experimental Android assistant and must preserve user control and platform safeguards.

## Before opening a pull request

1. Open an issue describing the proposed change; avoid posting API keys, contacts, message content, or private device data.
2. Keep permissions minimal and request runtime consent for sensitive capabilities.
3. Do not add silent calling/SMS, hidden recording, unrestricted device control, accessibility-based safeguard bypasses, or broad package/contact access as a shortcut.
4. Treat Gemini output as untrusted data. Only dispatch allowlisted actions after local validation and explicit user confirmation.
5. Update user-facing privacy/disclaimer text when data flows or permissions change.
6. Add/update tests for parser behavior, action-confirmation/cancellation, failure states, and permissions.
7. Run `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest` where a device/emulator is available. Report exactly what ran and any failures; do not say tests passed unless they did.

## Pull requests

Use a concise description, list behavior and permissions changed, explain data sent to third parties, attach screenshots for UI changes, and note test results. By submitting a contribution, you confirm you have the right to submit it under the repository's MIT License. This contribution statement is not a separate legal agreement.
