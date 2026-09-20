package com.openwakeup.parser.web

import com.openwakeup.parser.ParserInput
import kotlin.test.Test
import kotlin.test.assertEquals

/** 微人大解析器正则初始化与基础字段解析的回归测试。 */
class RucParserTest {

    /** 确保课程对象正则可以初始化，并能读取一个完整课程对象。 */
    @Test
    fun parseSingleCourseObject() {
        val input = ParserInput(
            text = """
                {
                  "course": [
                    {
                      "title": "数据结构",
                      "start": "1",
                      "quittingTime": "2",
                      "week": "星期一",
                      "weekly": "第1-4周全周",
                      "teacher": "王老师",
                      "place": "明德楼101"
                    }
                  ]
                }
            """.trimIndent(),
            type = "ruc",
        )

        val courses = RucParser.parse(input)

        assertEquals(1, courses.size)
        assertEquals("数据结构", courses.single().name)
        assertEquals(1, courses.single().day)
        assertEquals(1, courses.single().startNode)
        assertEquals(2, courses.single().step)
        assertEquals(1, courses.single().startWeek)
        assertEquals(4, courses.single().endWeek)
    }
}
