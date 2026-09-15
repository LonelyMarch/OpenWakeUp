package com.openwakeup.schedule.data.backup

import com.openwakeup.schedule.data.backup.BackupArchive.readEntry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 JSON 的唯一配置。
 *
 * 导出写全默认值以保持快照可审查；导入不忽略未知字段，格式变化必须提升协议版本，
 * 不在当前版本中加入静默兼容分支。
 */
internal val BACKUP_JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

/**
 * `.openwakebak` ZIP 的读写与完整性校验。
 *
 * 本对象只处理归档层，不理解课程字段；业务引用校验和数据库写入由 [BackupRepository] 负责。
 */
internal object BackupArchive {

    private const val MANIFEST_PATH = "manifest.json"
    private const val MAX_ENTRY_COUNT = 4_096
    private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 96L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L

    private val payloadPathPattern = Regex(
        "^(data/(schedules|timetables)/[1-9][0-9]*\\.json|" +
                "settings/(global|widget)\\.json|" +
                "assets/schedules/[1-9][0-9]*/background\\.[a-z0-9]{1,8}|" +
                "assets/widget/(background|empty)\\.[a-z0-9]{1,8})$",
    )

    /**
     * 写出一个完整备份。
     *
     * `entries` 始终由实际有效负载重新计算，调用方不能传入自定义哈希，从根源上避免清单
     * 与内容不同步。ZIP 中先写清单，再按路径排序写入数据，便于人工检查和稳定复现。
     *
     * @param output SAF 提供的目标输出流
     * @param appInfo 导出应用版本信息
     * @param catalog 本次选择的业务目录
     * @param payloads 除 `manifest.json` 外的全部文件
     */
    fun write(
        output: OutputStream,
        appInfo: BackupAppInfo,
        catalog: BackupCatalog,
        payloads: Map<String, ByteArray>,
    ) {
        validatePayloadsForWrite(payloads)
        validateCatalog(catalog, payloads.keys)
        val entries = payloads.toSortedMap().map { (path, bytes) ->
            BackupManifestEntry(path = path, size = bytes.size.toLong(), sha256 = sha256(bytes))
        }
        val manifest = BackupManifest(
            format = BACKUP_FORMAT,
            formatVersion = BACKUP_FORMAT_VERSION,
            databaseSchemaVersion = BACKUP_DATABASE_SCHEMA_VERSION,
            createdAt = Instant.now().toString(),
            app = appInfo,
            catalog = catalog,
            entries = entries,
        )
        val manifestBytes = BACKUP_JSON
            .encodeToString(BackupManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        if (manifestBytes.size > MAX_MANIFEST_BYTES ||
            manifestBytes.size.toLong() + payloads.values.sumOf { bytes -> bytes.size.toLong() } >
            MAX_ARCHIVE_BYTES
        ) {
            throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, "archive-too-large")
        }
        try {
            ZipOutputStream(output.buffered()).use { zip ->
                writeEntry(zip, MANIFEST_PATH, manifestBytes)
                payloads.toSortedMap().forEach { (path, bytes) -> writeEntry(zip, path, bytes) }
            }
        } catch (error: Exception) {
            throw BackupException(BackupException.Reason.WRITE_FAILED, cause = error)
        }
    }

    /**
     * 读取 ZIP 并完成归档级严格校验。
     *
     * 所有文件均设置未压缩大小上限；不能依赖攻击者可伪造的 [ZipEntry.getSize]，因此读取时
     * 逐块累计真实字节数。只有全部文件、清单和 SHA-256 都通过后才返回内容。
     *
     * @param input SAF 提供的源输入流
     * @return 已验证的清单与有效负载
     */
    fun read(input: InputStream): BackupArchiveContent {
        try {
            val files = linkedMapOf<String, ByteArray>()
            var totalBytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || files.size >= MAX_ENTRY_COUNT) {
                        throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, entry.name)
                    }
                    val path = entry.name
                    validateArchivePath(path)
                    if (files.containsKey(path)) {
                        throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, path)
                    }
                    val limit = if (path == MANIFEST_PATH) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                    val bytes = readEntry(zip, limit)
                    validateCompressionRatio(entry, bytes.size)
                    totalBytes += bytes.size
                    if (totalBytes > MAX_ARCHIVE_BYTES) {
                        throw BackupException(
                            BackupException.Reason.UNSAFE_ARCHIVE,
                            "archive-too-large"
                        )
                    }
                    files[path] = bytes
                    zip.closeEntry()
                }
            }
            val manifestBytes = files.remove(MANIFEST_PATH)
                ?: throw BackupException(BackupException.Reason.INVALID_FORMAT, "manifest-missing")
            val manifest = try {
                BACKUP_JSON.decodeFromString(
                    BackupManifest.serializer(),
                    manifestBytes.toString(Charsets.UTF_8),
                )
            } catch (error: SerializationException) {
                throw BackupException(BackupException.Reason.INVALID_FORMAT, "manifest-json", error)
            }
            validateManifest(manifest, files)
            return BackupArchiveContent(manifest = manifest, payloads = files)
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, cause = error)
        }
    }

    /** 写入一个不带平台时间戳差异的 ZIP 文件项。 */
    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 从当前 ZIP 项读取受大小限制的真实内容。 */
    private fun readEntry(zip: ZipInputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, "entry-too-large")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * 校验导出调用方生成的全部有效负载。
     *
     * 导出端使用与读取端相同的条目和大小上限，避免应用自己生成一个随后会被自己拒绝的
     * 备份文件。清单会额外占用一个 ZIP 条目，因此有效负载上限必须减一。
     */
    private fun validatePayloadsForWrite(payloads: Map<String, ByteArray>) {
        if (payloads.size + 1 > MAX_ENTRY_COUNT ||
            payloads.keys.any { path -> !payloadPathPattern.matches(path) } ||
            payloads.values.any { bytes -> bytes.size > MAX_ENTRY_BYTES }
        ) {
            throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, "payload-path")
        }
    }

    /**
     * 检查 ZIP 项的异常压缩比。
     *
     * 带 data descriptor 的 ZIP 项只有在内容读完后才能获得可靠的压缩后大小，因此本检查
     * 必须放在 [readEntry] 之后。未知大小保留给未提供该元数据的标准实现，但只要大小可用，
     * 超过上限的高压缩比内容会在业务解析前被拒绝。
     *
     * @param entry 已经完整读取的 ZIP 项
     * @param uncompressedBytes 实际累计的未压缩字节数
     */
    private fun validateCompressionRatio(entry: ZipEntry, uncompressedBytes: Int) {
        val compressedBytes = entry.compressedSize
        if (uncompressedBytes == 0) return
        if (compressedBytes == 0L ||
            (compressedBytes > 0L && uncompressedBytes.toLong() > compressedBytes * MAX_COMPRESSION_RATIO)
        ) {
            throw BackupException(
                BackupException.Reason.UNSAFE_ARCHIVE,
                "compression-ratio:${entry.name}"
            )
        }
    }

    /** 校验 ZIP 项名称，禁止目录穿越、反斜杠和当前版本未知路径。 */
    private fun validateArchivePath(path: String) {
        val valid = path == MANIFEST_PATH || payloadPathPattern.matches(path)
        if (!valid || path.startsWith('/') || '\\' in path || path.split('/').any { it == ".." }) {
            throw BackupException(BackupException.Reason.UNSAFE_ARCHIVE, path)
        }
    }

    /** 校验格式版本、目录关系以及每个实际文件的大小和摘要。 */
    private fun validateManifest(manifest: BackupManifest, payloads: Map<String, ByteArray>) {
        if (manifest.format != BACKUP_FORMAT) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, manifest.format)
        }
        if (manifest.formatVersion != BACKUP_FORMAT_VERSION) {
            throw BackupException(
                BackupException.Reason.UNSUPPORTED_VERSION,
                manifest.formatVersion.toString(),
            )
        }
        if (manifest.databaseSchemaVersion != BACKUP_DATABASE_SCHEMA_VERSION) {
            throw BackupException(
                BackupException.Reason.DATABASE_VERSION_MISMATCH,
                manifest.databaseSchemaVersion.toString(),
            )
        }
        if (runCatching { Instant.parse(manifest.createdAt) }.isFailure ||
            manifest.app.packageName.isBlank() || manifest.app.versionCode <= 0L
        ) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "manifest-metadata")
        }
        val entryPaths = manifest.entries.map { entry -> entry.path }
        if (entryPaths.size != entryPaths.toSet().size || entryPaths.toSet() != payloads.keys) {
            throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, "entry-set")
        }
        manifest.entries.forEach { entry ->
            if (!payloadPathPattern.matches(entry.path) || !entry.sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, entry.path)
            }
            val bytes = payloads.getValue(entry.path)
            if (entry.size != bytes.size.toLong() || entry.sha256 != sha256(bytes)) {
                throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, entry.path)
            }
        }
        validateCatalog(manifest.catalog, payloads.keys)
    }

    /** 校验业务目录中的 ID、依赖以及必需 JSON 文件。 */
    private fun validateCatalog(catalog: BackupCatalog, paths: Set<String>) {
        val scheduleIds = catalog.schedules.map { item -> item.sourceId }
        val timeTableIds = catalog.timeTables.map { item -> item.sourceId }
        if (scheduleIds.isEmpty() && timeTableIds.isEmpty() &&
            !catalog.hasGlobalSettings && !catalog.hasWidgetSettings
        ) {
            throw BackupException(BackupException.Reason.INVALID_FORMAT, "empty-catalog")
        }
        if (scheduleIds.any { it <= 0L } || timeTableIds.any { it <= 0L } ||
            scheduleIds.size != scheduleIds.toSet().size || timeTableIds.size != timeTableIds.toSet().size
        ) {
            throw BackupException(BackupException.Reason.INVALID_REFERENCE, "catalog-id")
        }
        if (catalog.schedules.any { item -> item.timeTableSourceId !in timeTableIds }) {
            throw BackupException(BackupException.Reason.INVALID_REFERENCE, "catalog-timetable")
        }
        val requiredPaths = buildSet {
            scheduleIds.forEach { id -> add(schedulePath(id)) }
            timeTableIds.forEach { id -> add(timeTablePath(id)) }
            if (catalog.hasGlobalSettings) add(GLOBAL_SETTINGS_PATH)
            if (catalog.hasWidgetSettings) add(WIDGET_SETTINGS_PATH)
        }
        if (!paths.containsAll(requiredPaths)) {
            throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, "catalog-payload")
        }
        val actualBusinessPaths = paths.filterTo(linkedSetOf()) { path ->
            path.startsWith("data/schedules/") || path.startsWith("data/timetables/") ||
                    path.startsWith("settings/")
        }
        if (actualBusinessPaths != requiredPaths) {
            throw BackupException(
                BackupException.Reason.INTEGRITY_CHECK_FAILED,
                "uncatalogued-payload"
            )
        }
        if ((GLOBAL_SETTINGS_PATH in paths) != catalog.hasGlobalSettings ||
            (WIDGET_SETTINGS_PATH in paths) != catalog.hasWidgetSettings
        ) {
            throw BackupException(BackupException.Reason.INTEGRITY_CHECK_FAILED, "settings-catalog")
        }
    }

    /** 对未压缩内容计算 SHA-256 小写十六进制。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { value ->
            (value.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    /** 返回一张课表在 ZIP 中的 JSON 路径。 */
    fun schedulePath(sourceId: Long): String = "data/schedules/$sourceId.json"

    /** 返回一张时间表在 ZIP 中的 JSON 路径。 */
    fun timeTablePath(sourceId: Long): String = "data/timetables/$sourceId.json"

    /** 全局设置 JSON 的固定路径。 */
    const val GLOBAL_SETTINGS_PATH = "settings/global.json"

    /** 小部件设置 JSON 的固定路径。 */
    const val WIDGET_SETTINGS_PATH = "settings/widget.json"
}
