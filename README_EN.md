# Xiaojingyu Likes You

> **⚠️ Non-commercial notice: This software is for personal learning and research only. Any commercial use is strictly prohibited. See [LICENSE.md](LICENSE.md).**

An Android companion app with the genius catgirl AI **"Baiyin" (Albyra)**. She lives in your phone: she can chat, sense your state, act cute and tsundere, and will even reach out to you on her own when she gets bored.

## ⚠️ Important Warnings

- **No commercial use**: This software must not be used for any commercial purpose, including resale, repackaging, or distribution as part of a commercial product.
- **AI content disclaimer**: AI-generated content is for reference only; the author is not responsible for its accuracy, appropriateness, or any consequences.
- **API costs are on you**: Fees from third-party APIs such as DeepSeek / Gemini are paid by the user.
- **Data security**: All conversation data is stored locally and encrypted, but regular backups are recommended. Uninstalling the app will erase all data.
- **Experimental features**: Boredom values, proactive messages, web search and other features are experimental and may be unstable or delayed.

## ✨ Core Features

### Little Girlfriend "Baiyin"
- Genius catgirl AI: quirky, thinks she is super smart, slightly tsundere
- Only remembers the things between you two, growing as you get along
- Her own memory system, affection level and personality evolution

### Proactive Sensing (optional, enable manually)
- She senses your state in the background and sends you proactive messages
- **Boredom system**: the longer you stay silent, the more bored she gets, and she will text you first when bored
- Note: due to Android background restrictions, messages may be delayed on systems like Xiaomi

### Real Web Connectivity
- Weather, news, real-time information fetched directly via web search with real summaries
- Phone connects to the API directly - no PC relay required

### Safety Boundaries
- **Not supported on mobile**: screen reading, accessibility clicks, file read/write/hiding, adjusting volume/brightness
- Allowed only: opening the app, web search, proactive messages

### Other Abilities
- Jailbreak word library (built-in + custom import)
- Speech synthesis (TTS)
- Gemini Vision image understanding (requires your own API Key)
- Live2D character models (experimental)
- Tavern module (character cards, presets, regex, fully isolated from the girlfriend)

## 🚀 Quick Start

### Direct Install (Recommended)
Download the latest APK from the [Releases page](https://github.com/shgghjj/xiaojingyu-likes-you/releases):
- File: `xiaojingyu-likes-you-v0.5.1.apk`
- Requirement: Android 8.0+ (minSdk 26)
- Steps:
  1. Download the APK
  2. Enable "Install from unknown sources" on your phone
  3. Open the APK to install
  4. Follow the tutorial to configure the DeepSeek API on first launch

### Build from Source
Requires: JDK 17, Android SDK (Build Tools 35.0.0)

```powershell
$env:JAVA_HOME='path-to-your-JDK17'
.\gradlew.bat :app:assembleDebug
```

APK output: `app\build\outputs\apk\debug\app-debug.apk`

## 🔒 Privacy

- No user data collection, no private information uploads
- API Key is stored only in encrypted local storage
- Conversation data stays on your device only (AES-256-GCM encrypted)

## 🧩 Open-Source Credits

This software is built on the following open-source projects:

| Project | License |
|---------|---------|
| PocketTavern | Apache 2.0 |
| SillyTavern | AGPL-3.0 |
| Live2D Cubism SDK | Live2D Proprietary License |
| Coil | Apache 2.0 |
| OkHttp | Apache 2.0 |
| Jetpack Compose | Apache 2.0 |
| Hilt / Dagger | Apache 2.0 |

## ❌ Disclaimer

Provided "as is" without any express or implied warranty. Using this software means you have read and agreed to all terms of [LICENSE.md](LICENSE.md).
