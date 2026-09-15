package com.openwakeup.schedule.platform.appwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 小部件自定义图片的有界导入与解码入口。
 *
 * 桌面小部件通过 RemoteViews 跨进程传递 Bitmap，直接使用相册原图容易触发内存不足或
 * Binder/RemoteViews 图像大小限制。本对象在写入私有目录前把图片限制到安全尺寸，并对
 * 大图或损坏文件执行采样解码；ARGB_8888 与无损 WebP 会保留源图片透明通道。
 */
object WidgetImageStore {

    /** 背景图最长边上限；兼顾高密度桌面清晰度与 RemoteViews 内存开销。 */
    const val MAX_BACKGROUND_DIMENSION = 1_024

    /** 空视图图片需要等比例铺满可用区域，使用与背景图相同的上限保证全宽显示清晰。 */
    const val MAX_EMPTY_IMAGE_DIMENSION = 1_024

    /**
     * 从系统图片 URI 导入、缩放并以 WebP 无损格式原子替换私有文件。
     *
     * @param context 用于访问 ContentResolver 与 filesDir
     * @param uri 系统照片选择器返回的图片 URI
     * @param fileName 私有目录中的稳定文件名
     * @param maxDimension 输出 Bitmap 最长边像素上限
     * @return 保存成功后的绝对路径；读取、解码或写入失败时返回空
     */
    fun importImage(
        context: Context,
        uri: Uri,
        fileName: String,
        maxDimension: Int,
    ): String? = runCatching {
        val bitmap = decodeUri(context, uri, maxDimension)
            ?: error("无法解码所选图片")
        try {
            val directory = File(context.filesDir, DIRECTORY_NAME)
            check(directory.exists() || directory.mkdirs()) { "无法创建小部件图片目录" }
            val target = File(directory, fileName)
            val temporary = File(directory, "$fileName.tmp")
            temporary.delete()
            temporary.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)) {
                    "无法压缩小部件图片"
                }
            }
            if (target.exists()) check(target.delete()) { "无法替换旧小部件图片" }
            check(temporary.renameTo(target)) { "无法保存小部件图片" }
            target.absolutePath
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    /**
     * 安全读取应用私有图片，历史全尺寸文件也只按目标尺寸采样。
     *
     * @param path 图片绝对路径
     * @param maxDimension 返回 Bitmap 的最长边上限
     * @return 可显示 Bitmap；文件缺失、损坏或内存不足时返回空
     */
    fun decodeFile(path: String, maxDimension: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile || maxDimension <= 0) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                // 保留透明通道，使空视图图片能够自然覆盖在小部件背景之上。
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.let { bitmap ->
                scaleDownPreservingAspectRatio(bitmap, maxDimension)
            }
        }.getOrNull()
    }

    /** 使用 ContentResolver 两次打开流，先读尺寸再按采样率解码真实像素。 */
    private fun decodeUri(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            // WebP 无损输出会保留该 Alpha 通道，透明区域可继续透出小部件背景。
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        return scaleDownPreservingAspectRatio(decoded, maxDimension)
    }

    /** 计算 2 的幂采样率，先显著降低原图解码峰值，再执行精确缩放。 */
    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > maxDimension * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 保持原始宽高比限制 Bitmap 最长边，并及时回收中间 Bitmap。
     *
     * 此处不按某个固定小部件比例预裁剪；实际显示层统一使用 CENTER_CROP，才能在桌面尺寸变化后
     * 继续等比例填满整个背景区域，而不会把图片横向或纵向拉伸变形。
     */
    private fun scaleDownPreservingAspectRatio(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height)
        if (longestEdge <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longestEdge
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private const val DIRECTORY_NAME = "widget_images"
}
