package com.openwakeup.schedule.data.school

import android.content.Context
import com.openwakeup.schedule.data.school.SchoolRepository.Companion.ASSET_NAME
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 学校列表数据源。
 *
 * 清单来自离线 JSON 资产 [ASSET_NAME]，首次访问时通过 kotlinx.serialization 解析一次。
 */
class SchoolRepository(context: Context) {

    /**
     * 单条学校配置。
     *
     * [sortKey] 用于列表分组和右侧字母索引；原始值 `0` 表示通用教务。
     * [type] 是本地解析器使用的教务类型。
     */
    @Serializable
    data class School(
        val sortKey: String = "",
        val name: String,
        val url: String = "",
        val type: String = "",
    )

    /** 学校清单的最外层结构。 */
    @Serializable
    private data class SchoolEnvelope(
        val data: List<School> = emptyList(),
    )

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    /** 学校数据只解析一次，过滤缺失名称或类型的无效记录。 */
    private val all: List<School> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadSchoolList()
            .filter { school -> school.name.isNotBlank() && school.type.isNotBlank() }
    }

    /** 返回全部学校及通用教务记录。 */
    fun all(): List<School> = all

    /** 读取学校清单 JSON。 */
    private fun loadSchoolList(): List<School> {
        val jsonText = appContext.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readText() }
        return json.decodeFromString(SchoolEnvelope.serializer(), jsonText).data
    }

    private companion object {
        /** 离线学校清单 */
        const val ASSET_NAME = "schools.json"
    }
}
