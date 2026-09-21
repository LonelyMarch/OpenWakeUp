package com.openwakeup.schedule.feature.importexport

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.textview.MaterialTextView
import com.openwakeup.parser.CoursePreview
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.CourseImportWriteRequest
import com.openwakeup.schedule.data.schedule.CourseWriteModel
import com.openwakeup.schedule.feature.courseedit.AddCourseActivity
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import java.io.Serializable
import kotlin.math.roundToInt

/** 导入完成后交给界面展示的结果。 */
internal data class CourseImportResult(
    val importedSessionCount: Int,
    val rangeReport: CourseImportRangeReport,
)

/**
 * 导入策略准备出的数据库请求与界面反馈。
 *
 * @property writeRequest 可直接交给 Repository 原子写入的请求
 * @property rangeReport 导入完成后向用户展示的越界课程报告
 */
internal data class PreparedCourseImport(
    val writeRequest: CourseImportWriteRequest,
    val rangeReport: CourseImportRangeReport,
)

/** 目标课表范围调整结果及非法课程清单。 */
internal data class CourseImportRangeReport(
    val invalidCourses: List<InvalidImportedCourse>,
) : Serializable

/** 一门非法导入课程及其具体越界范围。 */
internal data class InvalidImportedCourse(
    val name: String,
    val day: Int,
    val startWeek: Int,
    val endWeek: Int,
    val startNode: Int,
    val endNode: Long,
    val invalidDay: Boolean,
    val invalidWeek: Boolean,
    val invalidNode: Boolean,
) : Serializable

/**
 * CSV、HTML 文件、ICS 文件和教务网页导入共用的课程写入与范围策略。
 *
 * 导入数据可以把目标课表周数扩展到不超过 48 周；节数始终保持目标课表现有配置，不会因为
 * 导入而改变。越界课程仍完整写入数据库，由普通课程视图统一隐藏，并在课程管理页等待修改。
 */
internal object CourseImportPolicy {

    /**
     * 将解析器输出整理为无副作用的数据库写入请求。
     *
     * 本函数只负责范围计算、非法数据报告、逻辑课程分组和时间段去重，不访问数据库。调用方
     * 完成全部准备后再把请求交给 Repository，覆盖模式不会提前删除用户的原有课程。
     *
     * @param table 导入目标课表的当前数据库快照
     * @param previews 解析器输出的全部课程时间段
     * @param overwriteExisting 是否覆盖目标课表现有课程
     * @param semesterStartDate ICS 导入解析出的学期开始日期；其他来源为 `null`
     * @param resetCurrentWeekOverride 是否清除手动当前周并恢复自动计算
     * @return 原子写入请求与非法课程报告
     */
    fun prepareImport(
        table: TableEntity,
        previews: List<CoursePreview>,
        overwriteExisting: Boolean,
        semesterStartDate: String? = null,
        resetCurrentWeekOverride: Boolean = false,
    ): PreparedCourseImport {
        // 作息不足只影响时间文字，课程是否合法始终以“一天课程节数”为准。
        val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(table.nodes)
        val importedMaxWeek = previews.maxOfOrNull { preview ->
            maxOf(preview.startWeek, preview.endWeek)
        } ?: 1
        val targetMaxWeek = maxOf(
            table.maxWeek.coerceAtMost(AppDefaults.Table.MAX_SUPPORTED_WEEKS),
            importedMaxWeek.coerceAtMost(AppDefaults.Table.MAX_SUPPORTED_WEEKS),
        )

        val invalidCourses = previews.mapNotNull { preview ->
            val endNode = preview.startNode.toLong() + preview.step.toLong() - 1L
            val invalidDay = preview.day !in 1..7
            val invalidWeek = preview.startWeek !in 1..AppDefaults.Table.MAX_SUPPORTED_WEEKS ||
                    preview.endWeek !in preview.startWeek..AppDefaults.Table.MAX_SUPPORTED_WEEKS
            val invalidNode = preview.startNode < 1 || preview.step < 1 ||
                    endNode > visibleNodeLimit.toLong()
            if (!invalidDay && !invalidWeek && !invalidNode) {
                null
            } else {
                InvalidImportedCourse(
                    name = preview.name,
                    day = preview.day,
                    startWeek = preview.startWeek,
                    endWeek = preview.endWeek,
                    startNode = preview.startNode,
                    endNode = endNode,
                    invalidDay = invalidDay,
                    invalidWeek = invalidWeek,
                    invalidNode = invalidNode,
                )
            }
        }.distinctBy { issue ->
            // 同一课程的相同越界范围只提示一次，避免离散周解析产生重复警告。
            listOf(
                issue.name,
                issue.day,
                issue.startWeek,
                issue.endWeek,
                issue.startNode,
                issue.endNode,
            )
        }
        val palette = AddCourseActivity.PALETTE
        val courses = previews.groupBy { preview ->
            CourseIdentity(preview.name, preview.teacher, preview.room, preview.color)
        }.entries.mapIndexed { index, (identity, coursePreviews) ->
            CourseWriteModel(
                name = identity.name,
                color = identity.color ?: palette[index % palette.size],
                teacher = identity.teacher,
                room = identity.room,
                details = coursePreviews.map { preview -> preview.toDetail() }.distinct(),
            )
        }
        return PreparedCourseImport(
            writeRequest = CourseImportWriteRequest(
                tableId = table.id,
                overwriteExisting = overwriteExisting,
                targetMaxWeek = targetMaxWeek,
                courses = courses,
                semesterStartDate = semesterStartDate,
                resetCurrentWeekOverride = resetCurrentWeekOverride,
            ),
            rangeReport = CourseImportRangeReport(invalidCourses),
        )
    }

    /**
     * 使用 Material 3 Expressive 分段列表展示不会正常显示的课程。
     *
     * 弹窗只保留“课程名称 + 错误类型”两级信息，不再重复原始周数和节次范围。同名课程包含
     * 多条非法时间段时合并为一项，并汇总其错误类型，避免长课程名和数值说明交错成大段文本。
     *
     * @param context 当前导入页面上下文
     * @param report 目标范围和非法课程报告
     */
    fun showInvalidCourseDialog(context: Context, report: CourseImportRangeReport) {
        if (report.invalidCourses.isEmpty()) return
        val courseItems = report.invalidCourses.toDialogItems()
        val inflater = LayoutInflater.from(context)
        val content = inflater.inflate(R.layout.dialog_invalid_imported_courses, null)
        val list = content.findViewById<LinearLayout>(R.id.invalid_course_list)
        val scroll = content.findViewById<NestedScrollView>(R.id.invalid_course_scroll)
        courseItems.forEachIndexed { index, item ->
            val row = inflater.inflate(
                R.layout.item_invalid_imported_course,
                list,
                false
            ) as ListItemLayout
            row.findViewById<MaterialTextView>(R.id.invalid_course_name).text = item.name
            row.findViewById<MaterialTextView>(R.id.invalid_course_reason).text =
                item.reasonText(context)
            SettingsAppearance.applySegmentedCard(
                layout = row,
                card = row.findViewById<ListItemCardView>(R.id.invalid_course_card),
                index = index,
                count = courseItems.size,
            )
            list.addView(row)
        }

        // 列表最多占屏幕约 42%，课程较少时按实际行数收缩，较多时在弹窗内部滚动。
        val density = context.resources.displayMetrics.density
        val estimatedContentHeight =
            (courseItems.size * INVALID_COURSE_ROW_HEIGHT_DP * density).roundToInt()
        val maximumListHeight = minOf(
            (INVALID_COURSE_LIST_MAX_HEIGHT_DP * density).roundToInt(),
            (context.resources.displayMetrics.heightPixels * INVALID_COURSE_SCREEN_HEIGHT_RATIO).roundToInt(),
        )
        scroll.layoutParams = scroll.layoutParams.apply {
            height = estimatedContentHeight.coerceAtMost(maximumListHeight)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.import_invalid_courses_title)
            .setView(content)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 按课程名称合并非法时间段，仅保留弹窗需要的错误类别。
     *
     * @return 保持首次出现顺序的精简课程提示项
     */
    private fun List<InvalidImportedCourse>.toDialogItems(): List<InvalidCourseDialogItem> =
        groupBy { issue -> issue.name }.map { (name, issues) ->
            InvalidCourseDialogItem(
                name = name,
                invalidDay = issues.any { issue -> issue.invalidDay },
                invalidWeek = issues.any { issue -> issue.invalidWeek },
                invalidNode = issues.any { issue -> issue.invalidNode },
            )
        }

    /** 弹窗中一门课程的精简错误摘要。 */
    private data class InvalidCourseDialogItem(
        val name: String,
        val invalidDay: Boolean,
        val invalidWeek: Boolean,
        val invalidNode: Boolean,
    ) {
        /**
         * 生成不包含原始数值的错误类型文本。
         *
         * @param context 用于读取本地化资源的上下文
         * @return 由中点分隔的一个或多个错误类型
         */
        fun reasonText(context: Context): String = buildList {
            if (invalidDay) add(context.getString(R.string.import_invalid_day_label))
            if (invalidWeek) add(context.getString(R.string.import_invalid_week_label))
            if (invalidNode) add(context.getString(R.string.import_invalid_node_label))
        }.joinToString(REASON_SEPARATOR)
    }

    /** 将解析器预览转换为数据库时间段，保留越界原值供用户后续修改。 */
    private fun CoursePreview.toDetail(): CourseDetailEntity = CourseDetailEntity(
        courseId = 0,
        day = day,
        startNode = startNode,
        step = step,
        startWeek = startWeek,
        endWeek = endWeek,
        type = type,
        teacher = teacher,
        room = room,
    )

    /** 课程身份不包含周次和节次，因此一门课程的多条时间段可以合并写入。 */
    private data class CourseIdentity(
        val name: String,
        val teacher: String,
        val room: String,
        val color: String?,
    )

    /** 非法课程列表单行的估算高度，与 XML 中的 64dp 最小高度一致。 */
    private const val INVALID_COURSE_ROW_HEIGHT_DP = 64f

    /** 弹窗课程列表在大屏设备上的最大高度。 */
    private const val INVALID_COURSE_LIST_MAX_HEIGHT_DP = 320f

    /** 弹窗课程列表在小屏设备上最多占用的屏幕高度比例。 */
    private const val INVALID_COURSE_SCREEN_HEIGHT_RATIO = 0.42f

    /** 多个精简错误类型之间使用的视觉分隔符。 */
    private const val REASON_SEPARATOR = " · "
}
