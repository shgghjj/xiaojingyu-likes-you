package com.pockettavern.app.ui.utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 明日方舟风"斜切角"卡片：右上角向内切掉 45°，形成战术面板轮廓。
 * @param cutSize 切角边长（像素，dp 转换前传）
 */
class ArkCutCornerShape(private val cutSize: Float = 0f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(buildPath(size.width, size.height))

    private fun buildPath(w: Float, h: Float): Path =
        Path().apply {
            val c = cutSize.coerceIn(0f, minOf(w, h))
            moveTo(0f, 0f)
            lineTo(w - c, 0f)
            lineTo(w, c)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
}