package com.openwakeup.schedule.feature.schedule

import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.util.DateUtils

/**
 * 主课表课程片段解析器。
 *
 * 渲染之前先在这里按“天、节次”计算唯一显示结果，UI 层只负责把互不重叠的 [WeekCourseDisplaySegment]
 * 放进课程列。这样当前周课程可以只覆盖实际重合的节次，而不会把另一门长课程的未重合部分整张隐藏。
 */
internal object WeekCourseDisplayResolver {

    /**
     * 按节次解析当天应展示的内容，并把相邻且内容完全相同的节次合并为连续片段。
     *
     * 每个节次严格遵循以下优先级：当前查看周课程 > 最近未来周的非本周课程 > 空白。
     * 已经早于当前查看周的课程不会进入候选集合，因而不会以暗淡样式重新出现在过去的周次中。
     * 如果同一优先级、同一目标周内存在多门不同课程，则保留全部课程作为冲突片段；同一门课程
     * 因脏数据产生重复时间段时只取一条，避免把课程自身错误地标记为“课程重合”。
     *
     * @param currentWeekCourses 当天在当前查看周实际生效的课程，包含调入课程
     * @param otherWeekCourses 当天星期相同但当前查看周不生效的循环课程
     * @param viewedWeek 当前页面对应的周次
     * @param nodeCount 当前课表配置要求显示的总节数
     * @param showOtherWeekCourse 是否允许显示非本周课程
     * @return 已按起始节次排序且互不重叠的显示片段
     */
    fun resolve(
        currentWeekCourses: List<Pair<CourseEntity, CourseDetailEntity>>,
        otherWeekCourses: List<Pair<CourseEntity, CourseDetailEntity>>,
        viewedWeek: Int,
        nodeCount: Int,
        showOtherWeekCourse: Boolean,
    ): List<WeekCourseDisplaySegment> {
        if (nodeCount <= 0) return emptyList()

        val currentOccurrences = currentWeekCourses.map { (course, detail) ->
            WeekCourseOccurrence(course, detail)
        }
        val otherOccurrences = if (showOtherWeekCourse) {
            otherWeekCourses.mapNotNull { (course, detail) ->
                nextActiveWeek(detail, viewedWeek)?.let { futureWeek ->
                    WeekCourseOccurrence(course, detail, futureWeek)
                }
            }
        } else {
            emptyList()
        }

        val nodeSelections = (1..nodeCount).map { node ->
            val activeAtNode = distinctCoursesAtNode(currentOccurrences, node)
            if (activeAtNode.isNotEmpty()) {
                return@map WeekCourseNodeSelection(
                    courses = activeAtNode,
                    inWeek = true,
                    sourceWeek = viewedWeek,
                )
            }

            // 所有候选周都严格晚于查看周，因此取最小周次就是距离当前最近的未来课程。
            val inactiveAtNode = otherOccurrences.filter { occurrence ->
                occurrence.detail.coversNode(node)
            }
            val targetWeek = inactiveAtNode
                .mapNotNull { it.sourceWeek }
                .minOrNull()
                ?: return@map null
            val nearestCourses = distinctCoursesAtNode(
                occurrences = inactiveAtNode.filter { it.sourceWeek == targetWeek },
                node = node,
            )
            WeekCourseNodeSelection(
                courses = nearestCourses,
                inWeek = false,
                sourceWeek = targetWeek,
            )
        }

        val segments = mutableListOf<WeekCourseDisplaySegment>()
        nodeSelections.forEachIndexed { index, selection ->
            if (selection == null) return@forEachIndexed
            val node = index + 1
            val previous = segments.lastOrNull()
            if (previous != null && previous.selection == selection && previous.endNode + 1 == node) {
                segments[segments.lastIndex] = previous.copy(step = previous.step + 1)
            } else {
                segments += WeekCourseDisplaySegment(node, step = 1, selection)
            }
        }
        return segments
    }

    /**
     * 选出覆盖指定节次的不同课程，并按课程 id、时间段 id 固定排序。
     *
     * @param occurrences 待筛选的课程时间段
     * @param node 目标节次，使用从 1 开始的课表节次编号
     * @return 每门课程最多一条且顺序稳定的课程时间段
     */
    private fun distinctCoursesAtNode(
        occurrences: List<WeekCourseOccurrence>,
        node: Int,
    ): List<WeekCourseOccurrence> = occurrences
        .asSequence()
        .filter { occurrence -> occurrence.detail.coversNode(node) }
        .sortedWith(compareBy({ it.course.id }, { it.detail.id }))
        .distinctBy { it.course.id }
        .toList()

    /**
     * 计算一条循环课程在查看周之后最近的实际上课周。
     *
     * 单双周与起止周在这里同时参与判断，并使用严格大于 [viewedWeek] 的条件排除当前周和过去周。
     * 因此已经结课的时间段会返回 null，不再作为非本周课程显示。
     *
     * @param detail 课程时间段
     * @param viewedWeek 当前查看周
     * @return 最近的未来有效周次；时间段之后不再上课时返回 null
     */
    private fun nextActiveWeek(detail: CourseDetailEntity, viewedWeek: Int): Int? =
        (detail.startWeek..detail.endWeek)
            .asSequence()
            .filter { candidate ->
                candidate > viewedWeek &&
                        DateUtils.detailCoversWeek(
                            detail.startWeek,
                            detail.endWeek,
                            detail.type,
                            candidate
                        )
            }
            .firstOrNull()

    /** 判断课程时间段是否覆盖指定节次。 */
    private fun CourseDetailEntity.coversNode(node: Int): Boolean {
        val normalizedStart = startNode.coerceAtLeast(1)
        val normalizedEnd = normalizedStart + step.coerceAtLeast(1) - 1
        return node in normalizedStart..normalizedEnd
    }
}

/** 主课表解析时使用的课程及时间段快照；[sourceWeek] 仅在未来非本周候选中有值。 */
internal data class WeekCourseOccurrence(
    val course: CourseEntity,
    val detail: CourseDetailEntity,
    val sourceWeek: Int? = null,
)

/** 单个节次最终选中的课程集合；课程数大于 1 时表示同一周内发生冲突。 */
internal data class WeekCourseNodeSelection(
    val courses: List<WeekCourseOccurrence>,
    val inWeek: Boolean,
    val sourceWeek: Int,
)

/** 合并后的连续显示片段。 */
internal data class WeekCourseDisplaySegment(
    val startNode: Int,
    val step: Int,
    val selection: WeekCourseNodeSelection,
) {
    /** 片段结束节次，包含起始节次。 */
    val endNode: Int get() = startNode + step - 1
}
