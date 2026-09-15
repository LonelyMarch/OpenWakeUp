/*
 * 本文件基于 Jared Rummler/ColorPicker 及 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权包括 Copyright (C) 2017 Jared Rummler 与 Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年完成 Kotlin 与项目主题适配；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component.colorpicker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 透明滑条底纹：白/灰棋盘格，用于可视化 alpha 通道。
 * 基于 jaredrummler/ColorPicker 的 AlphaPatternDrawable 语义实现。
 *
 * @param rectangleSize 单个棋盘格边长（px）
 */
class AlphaPatternDrawable(private val rectangleSize: Int) : Drawable() {

    private val paint = Paint()
    private val paintWhite = Paint().apply { color = -0x1 }
    private val paintGray = Paint().apply { color = -0x343434 }

    private var numRectanglesHorizontal = 0
    private var numRectanglesVertical = 0

    private var bitmap: Bitmap? = null

    override fun draw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) }
    }

    override fun setAlpha(alpha: Int) {
        // 无需实现：图案不透明度跟随视图
    }

    override fun setColorFilter(cf: ColorFilter?) {
        // 无需实现
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) {
            bitmap = null
            return
        }
        numRectanglesHorizontal = Math.ceil(width.toDouble() / rectangleSize).toInt()
        numRectanglesVertical = Math.ceil(height.toDouble() / rectangleSize).toInt()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawPattern()
    }

    private fun drawPattern() {
        val currentBitmap = bitmap ?: return
        val canvas = Canvas(currentBitmap)
        val rect = Rect()
        var thisRowWhite = false
        for (y in 0..numRectanglesVertical) {
            var isWhite = thisRowWhite
            for (x in 0..numRectanglesHorizontal) {
                rect.top = y * rectangleSize
                rect.left = x * rectangleSize
                rect.bottom = rect.top + rectangleSize
                rect.right = rect.left + rectangleSize
                canvas.drawRect(rect, if (isWhite) paintWhite else paintGray)
                isWhite = !isWhite
            }
            thisRowWhite = !thisRowWhite
        }
    }
}
