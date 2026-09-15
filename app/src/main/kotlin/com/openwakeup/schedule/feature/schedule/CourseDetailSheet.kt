package com.openwakeup.schedule.feature.schedule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.textview.MaterialTextView
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.FragmentCourseDetailBinding
import com.openwakeup.schedule.databinding.ItemAddCourseDetailBinding
import com.openwakeup.schedule.feature.courseedit.AddCourseActivity
import kotlinx.coroutines.launch

/**
 * 课程详情底部弹层（fragment_course_detail：MaterialToolbar 标题/学分 + course_detail_menu
 * 删除/复制/编辑 + item_add_course_detail 时间段信息 + 时间冲突课程信息列表）。
 * 交互：menu_edit → 加课页编辑；menu_delete → 确认后删整门；menu_copy → 复制整门；
 * 时间段上的 ib_delete → 删除本周单节（拆记录，保留语义）。
 */
class CourseDetailSheet : BottomSheetDialogFragment() {

    private val repo by lazy { ScheduleRepository(requireContext()) }
    private val detailId: Long get() = requireArguments().getLong(ARG_DETAIL_ID)
    private val week: Int get() = requireArguments().getInt(ARG_WEEK)
    private var _binding: FragmentCourseDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCourseDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            val tableId = repo.currentTableId()
            val target = repo.detailOnce(detailId) ?: return@launch
            val cwd = repo.courseWithDetailsOnce(target.courseId) ?: return@launch
            val course = cwd.course
            val myDetails = cwd.details.sortedWith(
                compareBy<CourseDetailEntity>(
                    { detail -> detail.day },
                    { detail -> detail.startNode },
                    { detail -> detail.startWeek },
                    { detail -> detail.endWeek },
                    { detail -> detail.type },
                ),
            )

            binding.toolbar.title = course.courseName
            // 学分字段本工程数据模型未引入，留空副标题
            binding.toolbar.subtitle = ""

            val d = myDetails.firstOrNull { it.id == detailId } ?: target
            renderCourseDetails(myDetails)

            // 只有双方都在目标周实际上课时，节次重叠才构成时间冲突；范围内的单双周不能互相误报。
            val allDetails = repo.detailsOnce(tableId)
            val table = repo.tableOnce(tableId)
            val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(table?.nodes ?: 0)
            val validCourseIds = allDetails.groupBy { detail -> detail.courseId }
                .filterValues { details ->
                    CourseRangePolicy.isCourseValid(
                        details,
                        visibleNodeLimit
                    )
                }
                .keys
            val conflicts = allDetails.filter { other ->
                other.courseId != course.id && other.day == d.day &&
                        other.courseId in validCourseIds &&
                        other.startNode < d.startNode + d.step && d.startNode < other.startNode + other.step &&
                        DateUtils.detailCoversWeek(d.startWeek, d.endWeek, d.type, week) &&
                        DateUtils.detailCoversWeek(other.startWeek, other.endWeek, other.type, week)
            }.distinctBy { other -> other.courseId }
            if (conflicts.isEmpty()) {
                binding.tvTimeConflictCourses.visibility = View.GONE
                binding.llTimeConflictCourses.visibility = View.GONE
            } else {
                val names = repo.coursesOnce(tableId).associateBy { it.id }
                binding.llTimeConflictCourses.removeAllViews()
                conflicts.forEachIndexed { index, other ->
                    val row = layoutInflater.inflate(
                        R.layout.item_time_conflict_course,
                        binding.llTimeConflictCourses,
                        false,
                    ) as ListItemLayout
                    row.findViewById<MaterialTextView>(R.id.conflict_course_name).text =
                        names[other.courseId]?.courseName ?: "?"
                    row.findViewById<MaterialTextView>(R.id.conflict_course_schedule).text =
                        conflictScheduleText(other)
                    // Expressive 分段卡依据条目位置调整首尾圆角，列表中不再出现任何选择控件。
                    row.updateAppearance(
                        when {
                            conflicts.size == 1 -> ListItemLayout.POSITION_SINGLE
                            index == 0 -> ListItemLayout.POSITION_FIRST
                            index == conflicts.lastIndex -> ListItemLayout.POSITION_LAST
                            else -> ListItemLayout.POSITION_MIDDLE
                        },
                    )
                    binding.llTimeConflictCourses.addView(row)
                }
            }

            binding.toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_edit -> {
                        startActivity(
                            android.content.Intent(requireContext(), AddCourseActivity::class.java)
                                .putExtra(AddCourseActivity.EXTRA_TABLE_ID, tableId)
                                .putExtra(AddCourseActivity.EXTRA_COURSE_ID, course.id)
                        )
                        dismiss()
                    }

                    R.id.menu_delete -> confirmDelete(course.id, course.courseName)
                    R.id.menu_copy -> lifecycleScope.launch {
                        repo.copyCourse(tableId, course.id)
                        dismiss()
                    }
                }
                true
            }
        }
    }

    /**
     * 生成冲突课程条目的周次与节次说明。
     *
     * @param detail 待展示的冲突课程时间段
     * @return 第一行为有效周次，第二行为星期与节次范围的本地化文本
     */
    private fun conflictScheduleText(detail: CourseDetailEntity): String {
        val weekType = when (detail.type) {
            CourseDetailEntity.TYPE_ODD -> getString(R.string.week_type_odd)
            CourseDetailEntity.TYPE_EVEN -> getString(R.string.week_type_even)
            else -> ""
        }
        val weekText = getString(
            R.string.week_bean_to_string,
            detail.startWeek,
            detail.endWeek,
            weekType,
        )
        val timeText = getString(
            R.string.course_time_bean_to_string,
            detail.startNode,
            detail.startNode + detail.step.coerceAtLeast(1) - 1,
            getString(
                AddCourseActivity.DAY_NAME_RES.getOrElse(detail.day - 1) {
                    // 导入的不合规星期值不应导致整个课程详情弹层崩溃。
                    R.string.weekday_short_1
                },
            ),
        )
        return "$weekText\n$timeText"
    }

    /**
     * 渲染课程在整个学期内的全部时间段。
     *
     * 每条数据库时间段都使用独立的 `item_add_course_detail`，并加入布局中的纵向容器；容器位于
     * `NestedScrollView` 内，因此时间段数量超过弹层可见高度后仍可连续滚动查看。
     *
     * @param details 当前课程的全部时间段，调用方已按星期、节次和周次排序
     */
    private fun renderCourseDetails(details: List<CourseDetailEntity>) {
        binding.llCourseDetails.removeAllViews()
        details.forEachIndexed { index, detail ->
            val detailBinding = ItemAddCourseDetailBinding.inflate(
                layoutInflater,
                binding.llCourseDetails,
                false,
            )
            fillDetail(detailBinding, detail, index, details.size)
            binding.llCourseDetails.addView(detailBinding.root)
        }
    }

    /**
     * 填充一条学期时间段的信息与“删除本周单节”操作。
     *
     * @param b 当前时间段对应的视图绑定
     * @param detail 待展示的课程时间段
     * @param index 时间段在完整课程列表中的索引
     * @param count 当前课程的时间段总数
     */
    private fun fillDetail(
        b: ItemAddCourseDetailBinding,
        detail: CourseDetailEntity,
        index: Int,
        count: Int,
    ) {
        b.tvItem.text = if (count == 1) {
            getString(R.string.time_period)
        } else {
            getString(R.string.time_period_index, index + 1)
        }
        b.etWeeks.text = getString(
            R.string.week_bean_to_string,
            detail.startWeek,
            detail.endWeek,
            when (detail.type) {
                CourseDetailEntity.TYPE_ODD -> getString(R.string.week_type_odd)
                CourseDetailEntity.TYPE_EVEN -> getString(R.string.week_type_even)
                else -> ""
            },
        )
        b.etTime.text = getString(
            R.string.course_time_bean_to_string,
            detail.startNode,
            detail.startNode + detail.step.coerceAtLeast(1) - 1,
            getString(
                AddCourseActivity.DAY_NAME_RES.getOrElse(detail.day - 1) {
                    // 导入的不合规星期值不应阻断其余合法时间段的展示。
                    R.string.weekday_short_1
                },
            ),
        )
        b.etTeacher.setText(detail.teacher)
        b.etRoom.setText(detail.room)
        // 每条时间段独立判断目标周，只在该条确实覆盖目标周时开放“删除本周单节”。
        val coversWeek = DateUtils.detailCoversWeek(
            detail.startWeek,
            detail.endWeek,
            detail.type,
            week,
        )
        b.ibDelete.visibility = if (coversWeek) View.VISIBLE else View.GONE
        b.ibDelete.setOnClickListener {
            lifecycleScope.launch {
                repo.deleteDetailThisWeek(detail.id, week)
                dismiss()
            }
        }
    }

    /** 删除整门课程（menu_delete，带确认） */
    private fun confirmDelete(courseId: Long, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_course_title, name))
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteCourse(courseId)
                    dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_DETAIL_ID = "detail_id"
        private const val ARG_WEEK = "week"

        /** @param detailId 点击命中的时间段 id @param week 当前查看的周次 */
        fun newInstance(detailId: Long, week: Int): CourseDetailSheet =
            CourseDetailSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_DETAIL_ID, detailId)
                    putInt(ARG_WEEK, week)
                }
            }
    }
}
