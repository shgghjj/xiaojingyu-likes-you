# AI Companion personal build

This is a personal, non-commercial derivative of PocketTavern.

- Upstream: https://github.com/Starkka15/PocketTavern
- Upstream copyright: Copyright (c) 2026 starkka15
- Upstream license: `LICENSE` (MIT plus the upstream no-commercial-use restriction)
- Personal-build changes: AI-confirmed Android app launching, DeepSeek-first defaults,
  application branding, and starter assets/documentation.

The phone companion tool is intentionally narrow. In the girlfriend surface it can:

- search the public web directly from the phone;
- launch an installed application;
- schedule one to five proactive messages inside the app's own girlfriend chat.

It does not include an AccessibilityService, screen reading, automatic tapping, external
message sending, purchases, photo-library scanning, or arbitrary file access. Proactive
messages run only after the user enables the visible “主动联系” switch and keep an ongoing
notification while the service is active.

The app includes Live2D Inc. sample data under Live2D's applicable terms. See the bundled
`app/src/main/assets/live2d/NOTICE.txt`. The custom icon is derived from user-provided art;
its underlying character and illustration rights remain with their respective owner(s).

Do not sell this application or a derivative containing PocketTavern source without the
upstream licensor's explicit permission.
