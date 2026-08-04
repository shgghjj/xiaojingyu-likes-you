package com.pockettavern.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private data class ArkDot(val xRatio: Float, val yRatio: Float, val alpha: Float)

/**
 * 明日方舟风格主界面背景。
 * - 底部聚光 + 顶部暗晕：营造"终端舱内"景深
 * - 45° 斜网格 + 水平/垂直细网格：方舟标志性的战术网格
 * - 周期性白色扫描线自顶向下缓扫
 * - 极少的白点上浮：动态感不喧宾夺主
 * 全部在单次 Canvas 内绘制，无 AsyncImage 参与，从源头避开黑屏类瞬时解码故障。
 */
@Composable
fun ArknightsBackground(modifier: Modifier = Modifier) {
    val gridColor = Color(0xFF3A3A3E)
    val scanColor = Color(0xFF6E6E74)

    val transition = rememberInfiniteTransition(label = "arknightsBg")
    val scanY by transition.animateFloat(
        initialValue = -0.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanY"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // 固定点元素（重组时保持不变）
    val dots = remember {
        List(26) {
            ArkDot(
                xRatio = Random.nextFloat(),
                yRatio = Random.nextFloat(),
                alpha = 0.06f + Random.nextFloat() * 0.16f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1) 底：顶部暗部 → 底部微亮
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF08080A), Color(0xFF0B0B0D), Color(0xFF141416)),
                startY = 0f, endY = h
            )
        )

        // 2) 斜向网格（45° 下降线，从左/上边缘出发）
        val spacing = 56f
        var offset = -h
        while (offset < w + h) {
            drawLine(
                color = gridColor.copy(alpha = 0.28f),
                start = Offset(offset, 0f),
                end = Offset(offset + h, h),
                strokeWidth = 0.8f
            )
            offset += spacing
        }
        // 水平线
        var yy = 0f
        while (yy <= h) {
            drawLine(
                color = gridColor.copy(alpha = 0.18f),
                start = Offset(0f, yy),
                end = Offset(w, yy),
                strokeWidth = 0.6f
            )
            yy += spacing
        }
        // 垂直细线（低可见度）
        var vx = 0f
        while (vx <= w) {
            drawLine(
                color = gridColor.copy(alpha = 0.12f),
                start = Offset(vx, 0f),
                end = Offset(vx, h),
                strokeWidth = 0.5f
            )
            vx += spacing
        }

        // 3) 顶部光带 + 底部弱光
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                startY = 0f, endY = h * 0.28f
            )
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.045f)),
                startY = h * 0.82f, endY = h
            )
        )

        // 4) 上浮淡色点
        for (dot in dots) {
            val y = (dot.yRatio + pulse * 0.04f) % 1f
            val alpha = dot.alpha * (0.4f + 0.6f * abs(sin(pulse * 6f + dot.xRatio * 15f)))
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = 1.4f,
                center = Offset(dot.xRatio * w, y * h)
            )
        }

        // 5) 扫描线（自上而下）
        val sy = scanY * h
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    scanColor.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.06f),
                    scanColor.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                startY = sy - h * 0.35f,
                endY = sy + h * 0.35f
            )
        )
        drawLine(
            color = Color.White.copy(alpha = 0.045f),
            start = Offset(0f, sy),
            end = Offset(w, sy),
            strokeWidth = 1f
        )

        // 6) 底部聚光脉冲
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.05f * pulse),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, h * 0.96f),
                radius = w * 0.7f
            ),
            radius = w * 0.7f,
            center = Offset(w * 0.5f, h * 0.96f)
        )
    }
}