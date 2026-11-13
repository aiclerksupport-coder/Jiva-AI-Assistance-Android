
## Android phone-control boundary

Jiva is not unrestricted phone-control software. Android isolates apps and requires user-visible permissions/roles for sensitive features. The app can open supported apps/system screens, prepare calls/messages for review, and schedule reminders subject to device settings; it cannot silently control every app, send messages or place calls without user confirmation, read other apps' private data, or bypass Android protections. See [docs/ANDROID_PHONE_CAPABILITIES.md](docs/ANDROID_PHONE_CAPABILITIES.md). A unit-tested capability policy is included, but the project must still be built and tested locally.
