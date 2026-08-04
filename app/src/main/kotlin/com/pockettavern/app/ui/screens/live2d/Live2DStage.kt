package com.pockettavern.app.ui.screens.live2d

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.pockettavern.app.ui.live2d.AvatarCommand
import kotlinx.coroutines.launch
import org.json.JSONObject

data class Live2DModel(
    val id: String,
    val displayName: String,
    val modelPath: String
)

/**
 * 随安装包提供的 Live2D 官方示例模型。
 * 路径必须经过 WebViewAssetLoader 的 /assets/ 前缀，否则 WebView 无法读取 APK 内资源。
 */
val bundledLive2DModels: List<Live2DModel> = listOf(
    Live2DModel("bundled:haru", "Haru", "/assets/live2d/models/Haru/Haru.model3.json"),
    Live2DModel("bundled:hiyori", "Hiyori", "/assets/live2d/models/Hiyori/Hiyori.model3.json"),
    Live2DModel("bundled:mao", "Mao", "/assets/live2d/models/Mao/Mao.model3.json"),
    Live2DModel("bundled:mark", "Mark", "/assets/live2d/models/Mark/Mark.model3.json"),
    Live2DModel("bundled:rice", "Rice", "/assets/live2d/models/Rice/Rice.model3.json"),
    Live2DModel("bundled:wanko", "Wanko", "/assets/live2d/models/Wanko/Wanko.model3.json")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Live2DStageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("live2d_preferences", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(Live2DModelManager.allModels(context)) }
    var selectedId by rememberSaveable {
        mutableStateOf(prefs.getString("selected_model", "") ?: "")
    }
    var previewSpeaking by rememberSaveable { mutableStateOf(false) }
    var reloadToken by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val selectedModel = models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
    LaunchedEffect(models, selectedId) {
        val fallback = models.firstOrNull() ?: return@LaunchedEffect
        if (models.none { it.id == selectedId }) {
            selectedId = fallback.id
            prefs.edit().putString("selected_model", fallback.id).apply()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importMessage = null
            scope.launch {
                Live2DModelManager.importZip(context, uri)
                    .onSuccess { imported ->
                        models = Live2DModelManager.allModels(context)
                        selectedId = imported.id
                        prefs.edit().putString("selected_model", imported.id).apply()
                        importMessage = "已导入 ${imported.displayName}"
                    }
                    .onFailure { error -> importMessage = "导入失败：${error.message ?: "文件格式不正确"}" }
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live2D 形象馆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val model = selectedModel
            if (model == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无皮套，请点下方「从 ZIP 导入皮套」",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                key(model.id, reloadToken) {
                    Live2DStage(
                        model = model,
                        isSpeaking = previewSpeaking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("选择皮套", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        models.forEach { model ->
                            FilterChip(
                                selected = model.id == selectedModel?.id,
                                onClick = {
                                    selectedId = model.id
                                    prefs.edit().putString("selected_model", model.id).apply()
                                },
                                label = { Text(model.displayName) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { previewSpeaking = !previewSpeaking },
                            label = { Text(if (previewSpeaking) "停止口型测试" else "测试说话口型") },
                            leadingIcon = {
                                Icon(
                                    if (previewSpeaking) Icons.Default.Stop else Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        AssistChip(
                            onClick = { reloadToken++ },
                            label = { Text("重新加载") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !importing
                    ) {
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(if (importing) " 正在导入…" else " 从 ZIP 导入皮套")
                    }
                    importMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "可拖动视线、轻点身体触发动作；聊天页播放语音时嘴型会自动同步。导入前请确认你有权使用该模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Live2DStage(
    model: Live2DModel,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    avatarCommand: AvatarCommand? = null,
    lipSyncLevel: Float = -1f,
    onClick: (() -> Unit)? = null
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // WebView 会吞掉 Compose 的点击，用 JS 桥回调转发
    var tapCallback by remember { mutableStateOf(onClick) }
    LaunchedEffect(onClick) { tapCallback = onClick }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler(
                    "/live2d-user/",
                    WebViewAssetLoader.InternalStoragePathHandler(context, Live2DModelManager.rootDir(context))
                )
                .build()
            WebView(context).apply {
                // 不透明背景：透明 WebView + WebGL 在部分设备（Adreno/HyperOS）上会整层不合成，
                // 表现为"模型加载不出来且无任何提示文字"。页面自带渐变背景，视觉不受影响。
                setBackgroundColor(android.graphics.Color.rgb(16, 16, 22))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
                addJavascriptInterface(
                    object : Any() {
                        @android.webkit.JavascriptInterface
                        fun onTap() {
                            tapCallback?.invoke()
                        }
                    },
                    "AndroidBridge"
                )
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        assetLoader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        val path = JSONObject.quote(model.modelPath)
                        view.evaluateJavascript(
                            "window.AICompanion && window.AICompanion.loadModel($path);",
                            null
                        )
                        view.evaluateJavascript(
                            "window.AICompanion && window.AICompanion.setSpeaking(${if (isSpeaking) "true" else "false"});",
                            null
                        )
                    }
                }
                webChromeClient = WebChromeClient()
                WebView.setWebContentsDebuggingEnabled(true)
                loadUrl("https://appassets.androidplatform.net/assets/live2d/index.html")
                webView = this
            }
        },
        update = { webView = it },
        onRelease = { view ->
            view.removeJavascriptInterface("AndroidBridge")
            view.loadUrl("about:blank")
            view.stopLoading()
            view.destroy()
            webView = null
        }
    )

    LaunchedEffect(webView, model.modelPath) {
        val path = JSONObject.quote(model.modelPath)
        webView?.evaluateJavascript("window.AICompanion && window.AICompanion.loadModel($path);", null)
    }

    LaunchedEffect(webView, isSpeaking) {
        webView?.evaluateJavascript(
            "window.AICompanion && window.AICompanion.setSpeaking(${if (isSpeaking) "true" else "false"});",
            null
        )
    }

    LaunchedEffect(webView, avatarCommand) {
        val cmd = avatarCommand ?: return@LaunchedEffect
        val expression = cmd.expression?.let { JSONObject.quote(it) } ?: "null"
        val group = cmd.motionGroup?.let { JSONObject.quote(it) } ?: "null"
        val index = cmd.motionIndex ?: -1
        val gaze = JSONObject.quote(cmd.gaze)
        val intensity = cmd.intensity
        val script = "window.AICompanion && window.AICompanion.setState({" +
            "\"expression\":$expression,\"motionGroup\":$group,\"motionIndex\":$index," +
            "\"gaze\":$gaze,\"intensity\":$intensity});"
        webView?.evaluateJavascript(script, null)
    }

    // 口型电平：仅在有值（>=0）且与上次差异超过阈值时才推送，避免 JS 桥刷屏
    var lastPushedMouth by remember { mutableFloatStateOf(-2f) }
    LaunchedEffect(webView, lipSyncLevel) {
        if (lipSyncLevel < 0f) return@LaunchedEffect
        if (kotlin.math.abs(lipSyncLevel - lastPushedMouth) < 0.02f) return@LaunchedEffect
        lastPushedMouth = lipSyncLevel
        webView?.evaluateJavascript(
            "window.AICompanion && window.AICompanion.setMouth($lipSyncLevel);",
            null
        )
    }
}
