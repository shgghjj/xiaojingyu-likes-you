[**English**](README_EN.md) | 中文

---

# 小鲸鱼喜欢你 (Xiaojingyu Likes You)

> **⚠️ 非商用声明：本软件仅供个人学习与研究使用，严禁任何形式的商业用途。详见 [LICENSE](LICENSE.md)。**

一款搭载了天才猫娘AI「白音」的 Android 陪伴应用。她住在你的手机里，能聊天、能感知你的状态、会撒娇、会傲娇，还会因为无聊而主动找你。

---

## ⚠️ 重要警告

- **严禁商用**：本软件不得用于任何商业目的，包括转售、打包分发、作为商业产品的一部分。
- **AI 内容免责**：AI 生成内容仅供参考，作者不对其准确性、适当性或任何后果负责。
- **API 费用自理**：使用 DeepSeek / Gemini 等第三方 API 产生的费用由用户自行承担。
- **数据安全**：所有对话数据仅存本地并加密，但建议定期备份。卸载应用会清除所有数据。
- **实验性功能**：无聊值、主动消息、联网搜索等为实验性功能，可能存在不稳定或延迟。

---

## ✨ 核心功能

### 小女友「白音」
- 天才猫娘 AI：古灵精怪、自认为超级聪明、稍微傲娇
- 只记得你们之间的事，随相处慢慢长大
- 有自己的记忆系统、亲密度、性格演化

### 主动感知（可选，需手动开启）
- 她会在后台感知你的状态，主动给你发消息
- **无聊值系统**：越久不聊她越无聊，无聊了会主动找你撒娇
- 注意：受 Android 后台限制影响，小米等系统可能延迟消息

### 真实联网
- 天气、新闻、实时信息等直接联网搜索并返回真实摘要
- 不依赖电脑中转，手机直连 API

### 安全边界
- 手机端**不支持**：读屏、无障碍点击、文件读写/隐藏、调音量亮度
- 只允许：打开应用、联网搜索、主动消息

### 其他能力
- 破甲词库（内置 + 自定义导入）
- 语音合成（TTS）
- Gemini Vision 图片理解（需自行配置 API Key）
- Live2D 角色模型（实验性）
- 酒馆模块（角色卡、预设、正则等，与小女友完全隔离）

---

## 🚀 快速开始

### 直接安装（推荐）
从 [Releases 页面](https://github.com/shgghjj/xiaojingyu-likes-you/releases) 下载最新 APK：
- 文件名：`xiaojingyu-likes-you-v0.5.1.apk`
- 要求：Android 8.0+（minSdk 26）
- 安装步骤：
  1. 下载 APK
  2. 手机开启「允许安装未知来源应用」
  3. 打开 APK 安装
  4. 首次启动按教程配置 DeepSeek API

### 从源码构建
需要：JDK 17、Android SDK（Build Tools 35.0.0）

```powershell
$env:JAVA_HOME='你的JDK17路径'
.\gradlew.bat :app:assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

---

## 🔒 隐私

- 不收集用户数据，不上传隐私信息
- API Key 仅存设备本地加密存储
- 对话数据仅存设备本地（AES-256-GCM 加密）

---

## 🧩 开源致谢

本软件基于以下开源项目构建：

| 项目 | 许可 |
|------|------|
| PocketTavern | Apache 2.0 |
| SillyTavern | AGPL-3.0 |
| Live2D Cubism SDK | Live2D Proprietary License |
| Coil | Apache 2.0 |
| OkHttp | Apache 2.0 |
| Jetpack Compose | Apache 2.0 |
| Hilt / Dagger | Apache 2.0 |

---

## ❌ 免责

按"原样"提供，无任何明示或默示保证。使用本软件即表示你已阅读并同意 [LICENSE](LICENSE.md) 全部条款。
