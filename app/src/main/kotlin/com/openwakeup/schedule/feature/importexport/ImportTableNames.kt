package com.openwakeup.schedule.feature.importexport

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 统一生成各类导入操作创建的新课表名称。
 *
 * 文件导入仅移除文件名的最后一个后缀，以便 `课程表.导出.csv`
 * 仍能保留有意义的 `课程表.备份` 名称；教务导入则使用实际创建课表时的本地时间。
 */
internal object ImportTableNames {

    private val schoolImportFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")

    /**
     * 从文件展示名生成课表名称。
     *
     * @param fileName 系统文档提供器返回的文件展示名，也允许传入完整路径
     * @param fallbackName 文件名缺失、仅含后缀或去除后缀后为空时使用的回退名称
     * @return 去掉路径和最后一个后缀的课表名称
     */
    fun fromFileName(fileName: String, fallbackName: String): String {
        // 某些文档提供器无法返回 DISPLAY_NAME，此时 URI 末段可能仍包含路径分隔符。
        val leafName = fileName.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
        return leafName.substringBeforeLast('.', leafName)
            .trim()
            .ifBlank { fallbackName }
    }

    /**
     * 生成教务系统导入的新课表名称。
     *
     * @param now 实际创建课表的本地时间；参数可注入以便稳定验证格式
     * @return `yyyy-MM-dd-HH-mm-ss` 格式的名称
     */
    fun fromSchoolImport(now: LocalDateTime = LocalDateTime.now()): String =
        now.format(schoolImportFormatter)
}
