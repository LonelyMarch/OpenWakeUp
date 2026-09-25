package com.openwakeup.schedule.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 课表背景原图的持久化与有界解码入口。
 *
 * 用户选择的原始文件会原样保存，以保证备份质量不受损；显示时再根据目标 View 尺寸采样、
 * 居中裁剪并限制像素总量。主课表和外观预览共用本对象，避免两个页面分别维护容易漂移的
 * Bitmap 处理逻辑。
 */
internal object TableBackgroundImageStore {

    /**
     * 单张显示 Bitmap 允许的最大像素数。
     *
     * ARGB_8888 每像素占 4 字节，因此最坏像素内存约为 32 MiB；普通手机屏幕通常只会解码
     * 200～500 万像素，远低于该上限。
     */
    private const val MAX_DECODED_PIXELS = 8_388_608L

    /** 临时文件后缀；成功写完后再替换正式文件，避免进程中断留下半张图片。 */
    private const val TEMPORARY_SUFFIX = ".tmp"

    /**
     * 一次显示请求的稳定身份。
     *
     * 本地文件将长度和最后修改时间纳入版本；用户重新选择图片时即使保存路径没有改变，也会
     * 触发重新解码。非文件 URI 无可靠通用版本字段，使用源字符串与目标尺寸作为身份。
     *
     * @property source 数据库中保存的背景路径或 URI
     * @property targetWidth 经过像素预算限制后的目标宽度
     * @property targetHeight 经过像素预算限制后的目标高度
     * @property sourceLength 本地文件长度；非文件 URI 使用 `-1`
     * @property sourceLastModified 本地文件最后修改时间；非文件 URI 使用 `-1`
     */
    data class RequestKey(
        val source: String,
        val targetWidth: Int,
        val targetHeight: Int,
        val sourceLength: Long,
        val sourceLastModified: Long,
    )

    /**
     * 将照片选择器返回的原始内容保存到课表专属文件。
     *
     * 为兼容既有备份路径继续沿用 `table_bg_<id>.jpg` 文件名，但不会重新编码内容；PNG、WebP、
     * HEIF 等格式仍保持原始字节，BitmapFactory 会依据文件头识别真实格式。
     *
     * @param context 用于访问 ContentResolver 与应用私有目录
     * @param sourceUri 系统照片选择器返回的只读 URI
     * @param tableId 目标课表 id
     * @return 保存成功后的绝对路径；读取或替换失败时返回 `null`
     */
    suspend fun importOriginal(context: Context, sourceUri: Uri, tableId: Long): String? =
        withContext(Dispatchers.IO) {
            val target = File(context.filesDir, "table_bg_$tableId.jpg")
            val temporary = File(context.filesDir, target.name + TEMPORARY_SUFFIX)
            try {
                temporary.delete()
                val input = context.contentResolver.openInputStream(sourceUri) ?: return@withContext null
                input.use { source ->
                    temporary.outputStream().buffered().use { destination ->
                        source.copyTo(destination)
                    }
                }
                if (temporary.length() <= 0L) return@withContext null
                replaceAtomically(temporary, target)
                target.absolutePath
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } finally {
                // 原子替换成功后临时路径已经不存在；失败时清理残留文件。
                if (temporary.exists()) temporary.delete()
            }
        }

    /**
     * 删除背景字段被替换后不再使用的课表受管图片。
     *
     * 删除操作严格限制在应用 [Context.getFilesDir] 根目录，并要求文件名符合当前课表的
     * `table_bg_<id>.*` 规则。外部 `content://` URI、其他目录文件、其他课表图片以及仍由新
     * 背景字段引用的同一文件均不会删除。调用方应当只在新的数据库值成功落盘后调用本函数，
     * 避免数据库仍指向旧图片时提前移除文件。
     *
     * @param context 用于定位应用私有文件目录
     * @param oldSource 更新前的背景字段
     * @param newSource 已成功写入数据库的新背景字段
     * @param tableId 当前课表 id，用于约束允许删除的文件名
     * @return 确实删除文件时返回 `true`；无需删除、路径不受管或删除失败时返回 `false`
     */
    suspend fun deleteReplacedManagedImage(
        context: Context,
        oldSource: String,
        newSource: String,
        tableId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (oldSource.isBlank() || oldSource.startsWith("#")) return@withContext false

        val oldFile = sourceFile(oldSource)?.canonicalFileOrNull() ?: return@withContext false
        val newFile = newSource
            .takeIf { source -> source.isNotBlank() && !source.startsWith("#") }
            ?.let { source -> sourceFile(source) }
            ?.canonicalFileOrNull()
        if (newFile == oldFile) return@withContext false

        val filesRoot = context.filesDir.canonicalFileOrNull() ?: return@withContext false
        val expectedPrefix = "table_bg_${tableId}."
        val isManagedTableImage =
            oldFile.parentFile == filesRoot && oldFile.name.startsWith(expectedPrefix)
        if (!isManagedTableImage) return@withContext false

        runCatching { oldFile.delete() }.getOrDefault(false)
    }

    /**
     * 为当前目标 View 生成请求键。
     *
     * @param source 数据库中的图片路径或 URI
     * @param targetWidth 目标 View 实际宽度
     * @param targetHeight 目标 View 实际高度
     * @return 尺寸无效时返回 `null`
     */
    fun requestKey(source: String, targetWidth: Int, targetHeight: Int): RequestKey? {
        if (source.isBlank() || targetWidth <= 0 || targetHeight <= 0) return null
        val target = boundedTargetSize(targetWidth, targetHeight)
        val file = sourceFile(source)
        val validFile = file?.takeIf(File::isFile)
        return RequestKey(
            source = source,
            targetWidth = target.width,
            targetHeight = target.height,
            sourceLength = validFile?.length() ?: -1L,
            sourceLastModified = validFile?.lastModified() ?: -1L,
        )
    }

    /**
     * 按请求键采样并裁剪背景图。
     *
     * 解码过程在 IO 调度器执行；先读取尺寸，再选择 2 的幂采样率，最后按目标宽高比居中裁剪。
     * 返回 Bitmap 的像素数量不会超过 [MAX_DECODED_PIXELS]。
     *
     * @param context 用于读取 content URI
     * @param request 已包含目标尺寸与文件版本的请求
     * @return 可直接交给 ImageView/BitmapDrawable 的 Bitmap；无效图片返回 `null`
     */
    suspend fun decodeForView(context: Context, request: RequestKey): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = readBounds(context, request.source) ?: return@withContext null
                val sampleSize = calculateSampleSize(
                    sourceWidth = bounds.width,
                    sourceHeight = bounds.height,
                    targetWidth = request.targetWidth,
                    targetHeight = request.targetHeight,
                )
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val decoded = decodeSource(context, request.source, options) ?: return@withContext null
                cropAndScaleToTarget(decoded, request.targetWidth, request.targetHeight)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    /** 使用原子移动替换正式文件；文件系统不支持时退化为普通覆盖移动。 */
    private fun replaceAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /**
     * 只读取图片宽高，不分配完整像素缓冲区。
     *
     * [BitmapFactory.Options.inJustDecodeBounds] 开启后，[BitmapFactory.decodeStream] 按设计会返回
     * `null`，有效结果写入 [BitmapFactory.Options.outWidth] 与
     * [BitmapFactory.Options.outHeight]。因此这里只能根据输入流是否成功打开判断读取失败，不能
     * 把 `decodeStream` 的 Bitmap 返回值用于空值判断。
     *
     * @param context 用于解析 content URI 的上下文
     * @param source 本地绝对路径、file URI 或 content URI
     * @return 图片原始宽高；输入流无法打开或文件不是有效图片时返回 `null`
     */
    private fun readBounds(context: Context, source: String): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = openSource(context, source) ?: return null
        input.use { stream ->
            // bounds 模式不会创建 Bitmap，只通过 options 输出图片尺寸和类型。
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageSize(options.outWidth, options.outHeight)
    }

    /** 使用已计算的 BitmapFactory 参数执行真实解码。 */
    private fun decodeSource(
        context: Context,
        source: String,
        options: BitmapFactory.Options,
    ): Bitmap? = openSource(context, source)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }

    /** 根据绝对路径、file URI 或 content URI 打开全新的输入流。 */
    private fun openSource(context: Context, source: String): InputStream? {
        val file = sourceFile(source)
        if (file != null) return file.takeIf(File::isFile)?.inputStream()?.buffered()
        return context.contentResolver.openInputStream(Uri.parse(source))?.buffered()
    }

    /** 把无协议绝对路径和 file URI 解析为 File；其他 URI 留给 ContentResolver。 */
    private fun sourceFile(source: String): File? {
        val uri = Uri.parse(source)
        return when (uri.scheme?.lowercase()) {
            null -> File(source)
            "file" -> uri.path?.let(::File)
            else -> null
        }
    }

    /** 获取规范路径；路径解析失败时返回 `null`，避免清理流程影响外观设置保存。 */
    private fun File.canonicalFileOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    /**
     * 计算 2 的幂采样率。
     *
     * 优先保证采样结果仍覆盖目标尺寸；若原图比例极端或像素过多，则继续采样直到落入硬预算，
     * 允许 ImageView 对较小结果进行有限放大，避免为了不可见的裁剪区域保留巨型像素缓冲区。
     */
    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        while (true) {
            val currentWidth = (sourceWidth / sampleSize).coerceAtLeast(1)
            val currentHeight = (sourceHeight / sampleSize).coerceAtLeast(1)
            val currentPixels = currentWidth.toLong() * currentHeight.toLong()
            val nextSample = sampleSize * 2
            val nextWidth = (sourceWidth / nextSample).coerceAtLeast(1)
            val nextHeight = (sourceHeight / nextSample).coerceAtLeast(1)
            val nextStillCoversTarget = nextWidth >= targetWidth && nextHeight >= targetHeight
            if (!nextStillCoversTarget && currentPixels <= MAX_DECODED_PIXELS) break
            sampleSize = nextSample
            if (currentWidth == 1 && currentHeight == 1) break
        }
        return sampleSize
    }

    /**
     * 按目标宽高比居中裁剪，并且只做缩小、不做放大。
     *
     * 极端全景图片经过采样后仍可能在不可见方向保留数千像素；先裁剪再缩小可以把最终常驻内存
     * 收敛到真正显示区域。中间 Bitmap 不再使用时立即回收，降低一次解码过程的峰值持续时间。
     */
    private fun cropAndScaleToTarget(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > targetRatio) {
            cropHeight = bitmap.height
            cropWidth = (cropHeight * targetRatio).roundToInt().coerceIn(1, bitmap.width)
        } else {
            cropWidth = bitmap.width
            cropHeight = (cropWidth / targetRatio).roundToInt().coerceIn(1, bitmap.height)
        }
        val cropLeft = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
        val cropTop = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
        val cropped = if (
            cropLeft == 0 && cropTop == 0 && cropWidth == bitmap.width && cropHeight == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight).also {
                bitmap.recycle()
            }
        }

        val scale = minOf(
            1f,
            targetWidth.toFloat() / cropped.width.toFloat(),
            targetHeight.toFloat() / cropped.height.toFloat(),
        )
        if (scale >= 1f) return cropped
        val scaledWidth = (cropped.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (cropped.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true).also { scaled ->
            if (scaled !== cropped) cropped.recycle()
        }
    }

    /** 将目标尺寸等比压入像素预算，防止超高分辨率屏幕突破固定内存上限。 */
    private fun boundedTargetSize(width: Int, height: Int): ImageSize {
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount <= MAX_DECODED_PIXELS) return ImageSize(width, height)
        val scale = sqrt(MAX_DECODED_PIXELS.toDouble() / pixelCount.toDouble())
        return ImageSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** 内部宽高值对象，避免在尺寸计算中传递无语义的 Pair。 */
    private data class ImageSize(val width: Int, val height: Int)
}
