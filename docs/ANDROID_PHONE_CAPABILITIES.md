# Android phone-control boundary

Jiva cannot responsibly or technically promise unrestricted control of an entire Android phone. Android intentionally isolates apps and gates sensitive operations. A normal app should use public intents, runtime permissions, system roles, and user-visible review. A user command or Gemini result is not itself authorization to bypass those controls.

## Supported hand-offs in this MVP

- Launch an installed app through a launcher intent.
- Open Camera or a public Settings screen.
- Start a call by opening the system dialer; the user verifies and taps Call.
- Prepare SMS or WhatsApp/share content; the user selects the destination and taps Send.
- Schedule a local reminder, subject to notification permission, device settings, and battery behavior.
- Show basic device/Android version details locally; ask before sending diagnostics to a network service.

## Not supported

Jiva must not silently place calls or send messages, read private data owned by other apps, arbitrarily tap/control every app, or directly alter protected settings without Android's permitted flow. Accessibility or special access must not be used to circumvent platform safeguards. Any future default-assistant, accessibility, notification-listener, or device-management integration requires a specific user-facing purpose, eligibility/policy review, explicit system consent, and feature-specific security/privacy design.

`domain/PhoneCapabilityPolicy.kt` records this boundary in code and unit tests. It is a product-level gate, not a substitute for Android permission checks in each feature. Integrate it into every future action dispatcher and keep the user confirmation immediately before dispatch.
