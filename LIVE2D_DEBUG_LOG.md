# 月语伴侣 Live2D 不显示问题 — 调试日志（第 1 篇）

> 写于 2026-08-02，问题已解决、APK 已装机验证通过。
> 本文件用于跨上下文节点续接：下一个节点请先读本文件 + 相关源码再继续。

---

## 一、问题概述（已解决）

- **现象**：红米 K80 Pro（HyperOS）上，APK 内 Live2D 角色页打开后屏幕一片深色，模型不显示；页面顶边有一条约 22px 高的细条（像"搜索框"，实际是压扁的诊断面板）。
- **最终结论**：模型一直在正常渲染，**根因是 WebView 布局视口高度卡死**，页面 CSS 百分比高度链全部失效。

## 二、根因分析（重要，勿再走弯路）

1. **现象层级**：页面内部一切正常 —— 日志确认 8 步诊断全 `[OK]`：
   - `BOOT` / `CUBISM_CORE_LOADED`(csmGetVersion=0x5010000) / `WEBGL_CREATED`(WebKit WebGL 1.0 / OpenGL ES 2.0) / `MODEL_JSON_LOADED` / `MOC3_LOADED` / `TEXTURES_LOADED` / `MODEL_INITIALIZED` / `FIRST_FRAME_RENDERED` 全部 OK
   - 模型渲染进了 384×501 的离屏缓冲，纹理 566,299B 加载成功
2. **真正的坏点**：WebView 的 **CSS 布局视口高度 ≈ 38px（≈0）**，而实际表面（surface）是 384×501 CSS px（物理 1440×1880）。两者脱节：
   - `window.innerHeight` = 501（从表面取，是准的）
   - 但 `getComputedStyle(html).height` = **0px**，`100vh` 注入后仍是 0px（vh 也基于布局视口）
   - 绝对定位元素按坏视口布局：`#status` 跑到 y:-40（屏幕外），`#diag` 被压成 22px
3. **成因推测**：Compose 进入页面时 WebView 先以 0/小高度完成首次测量，页面在此视口下加载；surface 后来变大了，但 Chromium 的布局视口高度没跟随更新（已知的 WebView 布局/合成不同步 bug）。`location.reload()` 无效——重载时视口仍是旧值。
4. **为什么手机 Chrome 正常**：浏览器里 WebView 尺寸从一开始就是最终值，没有这个时序问题。桌面 Edge 同理。
5. **wasm2js 理论已废弃**：官方 core 就是 wasm2js 构建，与本次问题无关，不要再动 core。

## 三、修复方案（已装机验证通过）

**核心：不用百分比/视口链，用 JS 显式像素高度。** 在 index.html 中新增：

```html
<script>
  function fixViewport() {
    const w = window.innerWidth, h = window.innerHeight;
    if (!w || !h) return;
    const doc = document.documentElement, body = document.body;
    const stage = document.getElementById('stage');
    doc.style.width = w + 'px'; doc.style.height = h + 'px';
    body.style.width = w + 'px'; body.style.height = h + 'px';
    stage.style.width = w + 'px'; stage.style.height = h + 'px';
    const diag = document.getElementById('diag');
    diag.style.top = '8px'; diag.style.bottom = 'auto'; diag.style.height = (h - 16) + 'px';
    const status = document.getElementById('status');
    status.style.bottom = 'auto'; status.style.top = (h - 48) + 'px';
  }
  window.addEventListener('resize', fixViewport);
  window.addEventListener('orientationchange', () => setTimeout(fixViewport, 300));
  fixViewport();
  window.addEventListener('load', () => {
    setTimeout(fixViewport, 150);
    setTimeout(fixViewport, 700);
  });
  setTimeout(fixViewport, 250);
</script>
```

- 面板默认收起（`<div id="diag" class="collapsed">` + 左上角 `#diagToggle` 按钮切换，"诊断面板 ✓/✕"）
- 桌面/Chrome 下百分比 CSS 仍保留作兜底，JS 像素值覆盖它，两种环境都正常

## 四、当前状态

- ✅ 手机 APK 验证通过：打开 Live2D 页直接显示小丸模型（面板默认收起）
- ✅ 桌面已放最新 APK：`C:\Users\xiagu\Desktop\月语伴侣-v0.4.1-修复版.apk`
- ✅ 临时测试页已从 assets 删除（imgtest.html / imgtest2.html / pixitest.html）
- ✅ APK 内确认：含 `fixViewport`、面板默认 `collapsed`、无 imgtest 残留

## 五、待办 / 下一步

1. （可选）确认其他模型（小春/日和/真央/马克/小米）也能正常显示
2. （可选）确认"重新加载"按钮、切换皮套、"测试说话口型"均正常
3. （待用户决定）正式版处理：诊断面板是保留（默认收起）还是彻底移除
4. （可选）换用 `position:fixed` + 像素布局可避免个别设备同类问题，当前 JS 方案已够用

## 六、环境与操作备忘（下一个上下文需要）

### 构建命令
```
$env:JAVA_HOME="C:\Users\xiagu\Documents\Codex\2026-08-01\q\work\toolchain\jdk17\jdk-17.0.20+8"
cd C:\Users\xiagu\Documents\Codex\2026-08-01\q\work\AICompanion
.\gradlew.bat assembleDebug --console=plain
```
- 产物：`app\build\outputs\apk\debug\app-debug.apk`（约 167MB）
- 包名：`com.xiagu.aicompanion`
- 注意：`local.properties` 的 sdk.dir 指向 `C:\Users\xiagu\Documents\Codex\2026-08-01\q\work\toolchain\android-sdk`

### adb 无线调试（手机 REDMI K80 Pro，IP 可能变化）
```
adb pair 192.168.71.44:<配对端口> <6位配对码>   # 每次打开配对弹窗端口会变，用弹窗里显示的
adb connect 192.168.71.44:<连接端口>
adb -s 192.168.71.44:37785 install -r app-debug.apk
adb -s 192.168.71.44:37785 logcat -d | grep chromium
```
- adb 路径：`C:\Users\xiagu\Documents\Codex\2026-08-01\q\work\toolchain\android-sdk\platform-tools\adb.exe`
- 若报 `more than one device`：先 `adb disconnect` 掉 mDNS 伪条目，或统一用 `-s` 指定序列号

### WebView 调试协议（CDP，无需装包即可读页面状态）
- `adb forward tcp:9222 localabstract:webview_devtools_remote_<app pid>`（pid 用 `adb shell pidof com.xiagu.aicompanion` 获取）
- `GET http://127.0.0.1:9222/json` 拿到 `webSocketDebuggerUrl`
- 用 `C:\Users\xiagu\AppData\Local\Temp\opencode\cdp.ps1 -WsUrl <ws> -Expr <js>` 执行 Runtime.evaluate（本机无可用 python/node，PowerShell 5.1 原生 WebSocket；执行策略受限，需 `powershell -ExecutionPolicy Bypass -File ...`）

### 本地 HTTP 诊断服务器（已停用/未停，如继续测试手机 Chrome 可用）
- exe：`C:\Users\xiagu\AppData\Local\Temp\opencode\server.exe`，监听 `http://192.168.71.45:8123/`
- root：`app\src\main\assets`；`GET /report?r=...` 写 `report.txt`；`access.log` 记录请求
- 手机 Chrome 测试页：`http://192.168.71.45:8123/assets/live2d/index.html?v=2&model=/assets/live2d/models/Wanko/Wanko.model3.json`

## 七、关键文件

- `app\src\main\assets\live2d\index.html` — 修复主体（fixViewport、可收起诊断面板、IMG hook 只收真实事件、geom/centerPx 诊断）
- `app\src\main\kotlin\com\pockettavern\app\ui\screens\live2d\Live2DStage.kt` — WebViewAssetLoader 配置（**确认无误，勿改**）：`https://appassets.androidplatform.net/assets/live2d/index.html`、`cacheMode=LOAD_NO_CACHE`、不透明背景 rgb(20,20,30)
- 桌面 APK：`月语伴侣-v0.4.1-修复版.apk`

## 八、注意事项

- 用户约束：不重构项目、不动无关页面、一次只诊断不大范围改
- 手机 Chrome 与 APK 行为可不同：Chrome 正常不代表 APK 正常（本次正是如此）
- 诊断面板内容可靠依据：WebView console（chromium tag in logcat）+ CDP Runtime.evaluate，不要靠用户描述/截图（模型不支持图像输入）
