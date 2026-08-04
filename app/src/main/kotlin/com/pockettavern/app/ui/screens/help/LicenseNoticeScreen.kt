package com.pockettavern.app.ui.screens.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LicenseNoticeScreen(onAccept: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠ 重要声明",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = buildString {
                    append("本软件（小鲸鱼喜欢你）基于以下开源项目构建：\n\n")
                    append("· PocketTavern (Apache 2.0)\n")
                    append("· SillyTavern (AGPL-3.0)\n")
                    append("· Live2D Cubism SDK (Live2D 专有许可)\n")
                    append("· Coil (Apache 2.0)\n")
                    append("· OkHttp (Apache 2.0)\n")
                    append("· Kotlin 生态库\n\n")
                    append("本软件附带 Haru、Hiyori、Mao、Mark、Rice、Wanko 等 Live2D 官方示例数据。\n")
                    append("This content uses sample data owned and copyrighted by Live2D Inc. ")
                    append("The sample data are utilized in accordance with terms and conditions set by Live2D Inc. ")
                    append("This content itself is created at the author's sole discretion.\n\n")
                    append("应用图标基于用户提供的蓝发鲸鱼女仆表情图编辑。\n")
                    append("原始角色与插画权利归原作者或相应权利人，\n")
                    append("当前仅用于个人、非商业测试。\n\n")
                    append("本项目为个人学习与研究用途开发，\n")
                    append("严格禁止任何形式的商业使用、\n")
                    append("转售、打包、或以任何形式盈利。\n\n")
                    append("若你从任何渠道付费获取了本软件，\n")
                    append("请立即退款并举报。\n\n")
                    append("本软件没有自建的数据收集服务器；\n")
                    append("聊天记录和本地配置保存在你的设备中。\n")
                    append("但调用模型、图片理解、语音或联网服务时，\n")
                    append("相关请求会发送给你选择的第三方服务商，\n")
                    append("请阅读其隐私政策并避免提交敏感信息。\n\n")
                    append("⚠ 免责声明：\n")
                    append("本软件按\"原样\"提供，作者不对以下情况负责：\n")
                    append("· AI 生成内容的质量、准确性或适当性\n")
                    append("· 因使用本软件产生的任何数据丢失、设备损坏\n")
                    append("· 第三方 API 服务的可用性或费用\n")
                    append("· 用户自行配置的后端服务产生的问题\n")
                    append("· 任何因本软件引发的法律或隐私纠纷\n\n")
                    append("继续使用即表示你已阅读并同意以上全部条款。")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                lineHeight = 22.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已阅读并同意，进入应用", modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
