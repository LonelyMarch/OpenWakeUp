package com.openwakeup.parser.web

import com.openwakeup.parser.ParserException
import com.openwakeup.parser.ParserFactory
import com.openwakeup.parser.ParserInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** 电子科技大学旧 EAMS 运行时课表模型的回归测试。 */
class UestcShuweiParserTest {

    /**
     * 验证 ParserFactory 只选择电子科大 Parser，并正确合并连续节次、拆分星期和压缩周次。
     */
    @Test
    fun parseTable0ActivitiesThroughFactory() {
        val nodeCount = 4
        val slots = MutableList(DAYS_PER_WEEK * nodeCount) { mutableListOf<JsonObject>() }
        val oddCourse = activity(
            name = "数字信号处理（A12345.01）",
            teacher = "张老师",
            room = "品学楼 A101",
            weeks = setOf(1, 3, 5),
        )
        // 星期一第 1～2 节是同一课程，Parser 应合并为 step=2。
        slots[0] += oddCourse
        slots[1] += oddCourse
        // 相同课程出现在星期二时必须保留为独立时间段。
        slots[nodeCount] += oddCourse
        slots[3] += activity(
            name = "大学物理",
            teacher = "李老师",
            room = "立人楼 B202",
            weeks = setOf(2, 3, 4),
        )

        val input = ParserInput(
            text = buildPayload(slots).toString(),
            type = "uestc_shuwei",
        )
        val courses = ParserFactory.parse(input)

        assertSame(UestcShuweiParser, ParserFactory.create("uestc_shuwei"))
        assertEquals(3, courses.size)
        assertEquals(
            listOf(1, 2, 1, 1, 5, 1),
            courses.first { course -> course.name == "数字信号处理" && course.day == 1 }
                .let { course ->
                    listOf(
                        course.day,
                        course.step,
                        course.startNode,
                        course.startWeek,
                        course.endWeek,
                        course.type,
                    )
                },
        )
        assertEquals(
            listOf(1, 4, 1, 2, 4, 0),
            courses.first { course -> course.name == "大学物理" }
                .let { course ->
                    listOf(
                        course.day,
                        course.startNode,
                        course.step,
                        course.startWeek,
                        course.endWeek,
                        course.type,
                    )
                },
        )
    }

    /** 不是 7 天整数倍的槽位必须失败，禁止静默错算星期和节次。 */
    @Test
    fun rejectInvalidSlotCount() {
        val invalidPayload = buildJsonObject {
            put("activities", buildJsonArray { repeat(8) { addJsonArray { } } })
        }

        val error = assertFailsWith<ParserException> {
            UestcShuweiParser.parse(
                ParserInput(invalidPayload.toString(), "uestc_shuwei"),
            )
        }

        assertEquals(
            "uestc_shuwei 课表解析失败：电子科大课表槽位数量必须是 7 的正整数倍",
            error.message,
        )
    }

    /** 把测试槽位转成与 WebImportSource 完全一致的 JSON 封套。 */
    private fun buildPayload(slots: List<List<JsonObject>>): JsonObject = buildJsonObject {
        putJsonArray("activities") {
            slots.forEach { slot ->
                add(JsonArray(slot))
            }
        }
    }

    /** 创建一个只含电子科大 Parser 所需原始字段的课程对象。 */
    private fun activity(
        name: String,
        teacher: String,
        room: String,
        weeks: Set<Int>,
    ): JsonObject = buildJsonObject {
        put("courseName", name)
        put("teacherName", teacher)
        put("roomName", room)
        putJsonArray("validWeeks") {
            // 旧 EAMS 的第 0 位不是第一周；第一周从数组下标 1 开始。
            repeat(MAX_TEST_WEEK + 1) { index -> add(if (index in weeks) 1 else 0) }
        }
    }

    private companion object {
        const val DAYS_PER_WEEK = 7
        const val MAX_TEST_WEEK = 6
    }
}
