package com.pockettavern.app.ui.screens.help

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class TutorialPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tip: String
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onFinish: () -> Unit,
    showBackButton: Boolean = false
) {
    val pages = remember { chinesePages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == pages.lastIndex

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小鲸鱼喜欢你使用教程") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onFinish) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!lastPage) {
                        TextButton(onClick = onFinish) {
                            Text("跳过")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(RoundedCornerShape(36.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                page.icon,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            page.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            page.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                            )
                        ) {
                            Text(
                                page.tip,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(pages.size) { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == pagerState.currentPage) 10.dp else 7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("上一步")
                        }
                    }
                    Button(
                        onClick = {
                            if (lastPage) onFinish()
                            else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (lastPage) "开始使用" else "下一步"
                        )
                    }
                }
            }
        }
    }
}

private fun chinesePages() = listOf(
    TutorialPage(
        Icons.Default.AutoAwesome,
        "欢迎使用小鲸鱼喜欢你",
        "这是一个以小女友「白音」为核心的本地陪伴应用。聊天、记忆、人设和设置保存在你的手机中；模型回答由你配置的 API 提供。",
        "这套新版教程会在安装或升级后出现一次。可以跳过，以后随时从“设置 → 帮助”重新打开。"
    ),
    TutorialPage(
        Icons.Default.Cloud,
        "第一步：配置模型 API",
        "进入“设置 → API 配置”，选择 DeepSeek 或 OpenAI 兼容接口，填写地址、API Key 和模型名称，再点击测试连接。手机会直接连接服务商，不需要电脑中转。",
        "API Key 只保存在本机配置中，但请求内容会发送给你选择的 API 服务商。费用、内容政策和可用性以服务商规则为准。"
    ),
    TutorialPage(
        Icons.Default.Face,
        "小女友「白音」",
        "首页点“小女友”进入。她有独立的人设、亲密度、短期聊天和长期记忆；小女友不会读取酒馆角色的预设、世界书或扩展上下文。",
        "可在“小女友设置”修改名字、称呼、开场白和破甲词库。模型也可能答错，重要信息请自行核实。"
    ),
    TutorialPage(
        Icons.Default.NotificationsActive,
        "无聊值与主动联系",
        "停止互动后无聊值会缓慢上升；达到阈值并开启“主动联系”后，她会在自己的聊天里主动发消息并显示通知。你也可以直接要求她主动发 1～5 条。",
        "主动联系不读取屏幕、不扫描相册和文件，也不需要无障碍权限。小米手机若延迟通知，请允许通知并把后台活动设为“不限制”。"
    ),
    TutorialPage(
        Icons.Default.Public,
        "手机端的三项工具能力",
        "小女友只会：真实联网搜索、打开已经安装的应用、安排小女友主动消息。天气、新闻、日期、汇率和股价等实时问题会先联网，再根据真实摘要回答。",
        "手机端不会读屏、自动点击、读取或修改文件、隐藏照片、调节音量亮度，也不会通过电脑代办。打开应用后她看不到应用内部页面。"
    ),
    TutorialPage(
        Icons.Default.RecordVoiceOver,
        "图片与语音",
        "可以发送图片、使用语音输入和文字朗读。若主模型没有看图能力，可在“小女友设置”配置 Gemini Vision：图片先转成中文描述，再交给文字模型回答。",
        "相机、麦克风和通知权限都可以按需开启。图片理解与语音服务可能把相应内容发送给所选第三方服务商。"
    ),
    TutorialPage(
        Icons.Default.Waves,
        "Live2D 互动形象",
        "在“小女友设置”中选择或导入 Live2D 模型。支持待机、表情、动作、视线和说话口型；模型显示只负责表演，不会改变小女友的记忆和模型能力。",
        "只使用你有权使用的模型。导入包通常需要包含 model3.json、moc3、纹理和动作文件；兼容性取决于模型版本与文件结构。"
    ),
    TutorialPage(
        Icons.Default.Person,
        "酒馆角色卡与聊天",
        "首页“角色”进入酒馆。可导入 PNG 角色卡和 JSON 预设，使用正则、世界书、多人聊天、图片生成等原有功能。酒馆与小女友是两个独立模块。",
        "导入第三方角色卡、预设、扩展、Live2D 或图片前，请确认来源可信并遵守原作者许可；不要导入包含恶意脚本的未知文件。"
    ),
    TutorialPage(
        Icons.Default.Security,
        "完成：先备份，再慢慢探索",
        "建议顺序：配置 API → 与白音测试对话 → 开启主动联系 → 测试图片、语音和 Live2D → 按需使用酒馆。设置中可以导出备份和查看调试日志。",
        "本版本是个人非商业测试软件，不保证模型答案、后台服务或第三方接口始终可用。升级前建议备份；不要把 API Key、隐私图片或重要文件发给不可信服务。"
    )
)
