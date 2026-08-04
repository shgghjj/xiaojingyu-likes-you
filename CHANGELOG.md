# Changelog

All notable changes to PocketTavern (月语伴侣) are documented here.

---

## [0.5.1] — 2026-08-04（小鲸鱼喜欢你·完整检查版）

### Fixed
- 首次安装或从旧版升级后会自动弹出新版九页中文教程；“设置 → 帮助”打开同一套教程。
- 恢复 APK 内实际存在的六套 Live2D 官方示例模型（Haru、Hiyori、Mao、Mark、Rice、Wanko），修复皮套列表为空与默认未选中的问题。
- 小女友后台主动联系不再使用 Android 15 限时 6 小时的 `dataSync` 前台服务类型，改为用户主动开启的 `specialUse` 陪伴提醒类型。
- 小女友提示词与新版蓝发小鲸鱼形象对齐，强化“先准确回答、不得装傻、工具结果不可编造”的约束。

### Changed
- 应用名称统一为“小鲸鱼喜欢你”，启动图、桌面图标和应用内头像换成用户提供表情图衍生的新图标。
- 手机端小女友工具白名单固定为：真实联网搜索、打开已安装应用、在小女友聊天内安排主动消息。
- 手机端不提供读屏、自动点击、文件读取/修改、音量亮度控制；酒馆模块与小女友的人设、记忆、上下文和 Live2D 选择保持隔离。
- 补充 Live2D 官方示例数据版权声明、第三方 API 隐私提示和用户提供图标的权利说明。

### Verification
- 885 个字符串资源均有简体中文对应。
- 六个 Live2D `model3.json` 引用的模型、纹理、动作和物理文件均存在。
- 项目内未发现硬编码 API Key、GitHub Token 或 Gemini Key。
- 完整测试、Lint、APK 检查结果见同版发布目录的《完整检查报告.md》。

---

## [dev] — 2026-08-03（当前开发版，versionName 0.4.0 / versionCode 5）

### Changed
- 主界面背景由 `ParticleBackground` 替换为自绘的 `ArknightsBackground`：明日方舟风格战术终端观感（45° 斜网格 + 水平/垂直细网格、自上而下扫描线、底部聚光脉冲、少量上浮光点），全部在单次 `Canvas` 内绘制，无 `AsyncImage` 参与，从源头规避背景图片解码黑屏类瞬时故障。
- 重构 `ArknightsBackground.kt`：补齐 `import …remember`、以 `ArkDot` 数据类替代嵌套 `Triple/Pair` 解构（消除编译期类型歧义）、删除无意义的死代码行与未用变量（`accent`/`i`/`speed`/`ticks` 信号刻度段）。
- `MainScreen.kt` 导入修正：移除旧 `ParticleBackground` import，新增 `ArknightsBackground`、`sp`、`BorderStroke` import。

### Fixed
- 主界面编译失败 `Unresolved reference 'BorderStroke'`（`MainScreen.kt:181/540`）→ 补 `androidx.compose.foundation.BorderStroke` import 并去除重复导入。
- `ArknightsBackground.kt` 编译失败：缺 `remember` Import、嵌套解构 `component1()/component2()` 歧义 → 重写数据模型后消除。

### Known Issue（未解决，待下一个上下文节点续接）
- **小女友版块输出内容问题**：表现与之前记录一致，尚未定位/修复。涉及文件：
  - `ui/screens/girlfriend/GirlfriendViewModel.kt`（提示词/记忆/卡片刷新管线）
  - `data/girlfriend/GirlfriendPromptBuilder.kt`（提示词构建与设备权限注入）
  - `ui/screens/girlfriend/GirlfriendScreen.kt`（输出呈现）
  - 复用酒馆管线 `ChatViewModel`（`pureChatMode = true` 固定）

### Build & Install
- `.\gradlew.bat :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL**（2026-08-03）
- 产物：`app\build\outputs\apk\debug\app-debug.apk`（175,769,583 B，约 168 MB）→ 已复制到桌面 `C:\Users\xiagu\Desktop\app-debug.apk`，用于覆盖更新装机。

---

## [2.1.5.1] — 2026-05-17

### Fixed
- First message no longer shows raw `{{user}}` — persona name now substituted on greeting creation

---

## [2.1.5] — 2026-05-17

### Added
- **nano-gpt image generation** — new backend option in Image Generation settings; models loaded automatically from the nano-gpt API

---

## [2.0.0] — 2026-02-21 — Standalone Release

### Overview

Version 2.0 is a complete architectural overhaul. PocketTavern is now a **fully standalone app** — no SillyTavern server is required.

We made this change because the original design created unnecessary friction. Users had to set up a Node.js server, configure network access, keep a PC running just to use the app, and debug connectivity issues before they could even send a message. That was the opposite of what we wanted PocketTavern to be: *simple*.

The new architecture talks directly to LLM backends (KoboldCPP, Ollama, OpenAI-compatible APIs, Anthropic, and more), stores all data locally on-device in SillyTavern-compatible formats, and ships with all the templates you'd expect built right in.

---

### What's New

#### Architecture
- **Removed SillyTavern server dependency entirely** — the app no longer needs a running ST instance
- Replaced `SillyTavernRepository` (3300-line server proxy) with a local-first data layer
- New package identifier: `com.pockettavern.app` (previously `com.stark.sillytavern`)
- App renamed from internal working title to **PocketTavern** throughout

#### Direct LLM Backends
- **KoboldCPP** — `POST /api/v1/generate` with streaming
- **Ollama** — `POST /api/generate` / `/api/chat` with streaming
- **OpenAI-Compatible** — `/v1/chat/completions` (covers LM Studio, TabbyAPI, vLLM, Aphrodite, TextGen WebUI OAI mode, OpenAI, Mistral, Groq, DeepSeek, and others)
- **LlamaCpp Server** — `POST /completion` with streaming
- **Anthropic** — Claude models via `/v1/messages`
- **NovelAI** — `/ai/generate-stream`
- All backends support token streaming via SSE/chunked transfer

#### Local Storage
- Characters stored as PNG files with embedded metadata (SillyTavern-compatible `.png` card format)
- Chats stored as JSONL files (SillyTavern-compatible format, one message per line)
- Lorebooks stored as JSON files (SillyTavern world info format)
- Room database for fast indexing, search, and filtering — file system remains the source of truth
- Recent Chats now sorts by actual last-modified time (not filename creation timestamp)
- Recent Chats now shows a preview of the last message

#### Bundled Templates (96+ total)
Copied from SillyTavern's open-source preset library:
- 42 instruct templates (Llama 3, Mistral V1–V7, ChatML, DeepSeek, Gemma, Command-R, Alpaca, and more)
- 34 context templates
- 14 system prompt presets
- 6 TextGen presets
- 3 OpenAI presets (Balanced, Creative, Precise)

#### Extension System
New native extension framework:
- **Quick Reply** — configurable preset message buttons above the input field
- **Regex Text Replacement** — find/replace rules applied to AI output, with full regex support
- **Token Counter** — live estimated token count display

#### OpenAI Prompt Order Editor
- Full drag-and-reorder prompt block system for chat-completion APIs
- Per-block role selection: `system` / `user` / `assistant`
- Per-block injection mode: in-order or injected at a specific depth into chat history
- Depth-0 injection ordering fixed (blocks now appear in their configured sequence)

#### Connection Profiles
- Save named API + model configurations
- Switch between profiles from the main screen

#### SillyTavern Import Wizard
- One-time migration tool: connects to a SillyTavern server, pulls characters, chats, and lorebooks, saves locally
- After import, the ST server is no longer needed
- Also supports direct PNG folder import (no server required)

#### World Info Improvements
- Character book entries (embedded in PNG cards) now automatically loaded into context
- Probability-based entry activation
- Token budget enforcement (stops injecting once budget is reached)
- Recursive scanning (activated entry content is scanned for further keyword matches)
- Regex key matching (`/pattern/flags` syntax)

#### Chat Improvements
- Author's Note depth and frequency controls
- Per-message edit and delete fully wired to local storage
- Alternative response system: swipe left/right on any AI message or tap the arrow buttons below it; `↺` button at the end generates a new alternative; all alternatives are stored alongside the original
- Group chat activation strategy selector
- Continue generation (append to last response without starting fresh)

---

### Removed

- **SillyTavernApi.kt** / **SillyTavernDtos.kt** — ST REST client no longer needed
- **SillyTavernRepository.kt** — replaced by `LocalRepository` + `LlmRepository`
- **AuthInterceptor / CsrfInterceptor** — no longer needed without an ST server
- **Server URL / Username / Password** settings — replaced with direct backend URL + API key
- "Test Connection" to ST server — replaced with per-backend connection test
- Setup requirement for SillyTavern Multi-User Mode

---

### Bug Fixes

- Recent Chats sorted by filename creation date instead of actual last activity — fixed
- Recent Chats "last message" preview always showed "Tap to continue chatting" — fixed
- Depth-0 system prompt injections appearing in reversed order — fixed
- GOT patching Thumb bit causing SIGILL in native layer — fixed

---

## [1.0.2] — Previous Release

- Add group chat activation strategy selector
- Bump version to 1.0.2

## [1.0.1]

- Add CharaVault login and mode switching
- Add group chat feature
- Add update check notification
- Fix version comparison with flavor suffix

## [1.0.0]

- Initial release
- SillyTavern companion app
- Characters, chats, CardVault, Forge, basic settings
