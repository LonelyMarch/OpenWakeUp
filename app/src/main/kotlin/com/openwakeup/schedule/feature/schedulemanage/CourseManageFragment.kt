package com.openwakeup.schedule.feature.schedulemanage

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.dao.CourseWithDetails
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.feature.courseedit.AddCourseActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 课程管理：两列瀑布流课程卡（高 120dp、0 投影、描边），列表头提示"轻触编辑，长按删除"、
 * 页尾 240dp 占位、空态图 + 文案。
 * 轻触 → AddCourseActivity 编辑；长按 → 删除确认；工具栏"清空"由宿主 Activity 提供。
 */
class CourseManageFragment : Fragment() {

    private val repo by lazy { ScheduleRepository(requireContext()) }
    private var tableId: Long = -1L
    private var visibleNodeLimit = AppDefaults.Table.NODES
    private lateinit var adapter: CourseListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tableId = requireActivity().intent.getLongExtra(
            ScheduleManageActivity.EXTRA_SELECTED_TABLE_ID, -1L
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_list_manage, container, false)
        recyclerView = root.findViewById(R.id.rv_list)
        val d = resources.displayMetrics.density
        recyclerView.setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        val span = if (resources.displayMetrics.widthPixels <
            resources.getDimensionPixelSize(R.dimen.wide_screen)
        ) 2 else 4
        recyclerView.layoutManager =
            StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL)
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val gap = (8 * d).toInt()
                outRect.set(gap, gap, gap, gap)
            }
        })
        adapter = CourseListAdapter()
        recyclerView.adapter = adapter
        // 空态（anko_empty_view）：图 + 还没有添加任何课程哦
        emptyView = buildEmptyView()
        (root as ViewGroup).addView(
            emptyView,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(
                Intent(requireContext(), AddCourseActivity::class.java)
                    .putExtra(AddCourseActivity.EXTRA_TABLE_ID, tableId)
            )
        }
        loadCourses()
        return root
    }

    private fun buildEmptyView(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val box = LinearLayoutCompat(requireContext()).apply {
            id = R.id.anko_empty_view
            orientation = LinearLayoutCompat.VERTICAL
            setPadding(0, dp(72f), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(
            AppCompatImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_schedule_empty)
                adjustViewBounds = true
            },
            LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(200f))
        )
        box.addView(
            AppCompatTextView(requireContext()).apply {
                text = getString(R.string.course_empty_tip)
                gravity = Gravity.CENTER
            },
            LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16f)
            }
        )
        box.visibility = View.GONE
        return box
    }

    /** 返回本页（编辑/新增课程结束）时刷新列表 */
    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) loadCourses()
    }

    fun loadCourses() {
        lifecycleScope.launch {
            val table = repo.tableOnce(tableId) ?: return@launch
            visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(table.nodes)
            // 课程管理必须读取未过滤的关联数据，才能展示并修正非法课程。
            val courses = repo.coursesWithDetails(tableId).first()
            adapter.submit(courses)
            emptyView.visibility = if (courses.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun deleteCourse(course: CourseEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_tips)
            .setMessage(R.string.msg_delete_course)
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteCourse(course.id)
                    loadCourses()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 清空当前课表全部课程（宿主工具栏"清空"调用） */
    fun clearCourses() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_tips)
            .setMessage(R.string.msg_clear_course)
            .setPositiveButton(R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        repo.clearCourses(tableId)
                        loadCourses()
                    }.onSuccess {
                        android.widget.Toast.makeText(
                            requireContext(), R.string.op_success, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { e ->
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.op_failed, e.message ?: ""),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class CourseListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val data = mutableListOf<CourseWithDetails>()

        fun submit(courses: List<CourseWithDetails>) {
            data.clear()
            data.addAll(courses)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> TYPE_HEADER
            data.size + 1 -> TYPE_FOOTER
            else -> TYPE_COURSE
        }

        override fun getItemCount(): Int = data.size + 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = parent.resources.displayMetrics.density
            fun dp(v: Float) = (v * d).toInt()
            return when (viewType) {
                TYPE_HEADER -> {
                    val hint = AppCompatTextView(parent.context).apply {
                        text = getString(R.string.course_manage_hint)
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        setPadding(0, dp(8f), 0, dp(8f))
                        gravity = Gravity.CENTER
                    }
                    val lp = StaggeredGridLayoutManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { isFullSpan = true }
                    hint.layoutParams = lp
                    HeaderHolder(hint)
                }

                TYPE_FOOTER -> {
                    val space = View(parent.context)
                    val lp = StaggeredGridLayoutManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(240f)
                    ).apply { isFullSpan = true }
                    space.layoutParams = lp
                    FooterHolder(space)
                }

                else -> CourseHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_course_list, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is CourseHolder) return
            val courseWithDetails = data[position - 1]
            val course = courseWithDetails.course
            val isInvalid = !CourseRangePolicy.isCourseValid(
                courseWithDetails.details,
                visibleNodeLimit,
            )
            var color = course.color
            if (color.isEmpty()) {
                val colors = resources.obtainTypedArray(R.array.customizedColors)
                val picked = colors.getColor(course.id.toInt() % 9, Color.GRAY)
                colors.recycle()
                color = String.format("#%08X", picked)
            }
            val parsed = runCatching { Color.parseColor(color) }.getOrDefault(Color.GRAY)
            val surfaceColor = MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorSurface,
                Color.WHITE,
            )
            val onSurfaceColor = MaterialColors.getColor(
                requireContext(),
                com.google.android.material.R.attr.colorOnSurface,
                Color.BLACK,
            )
            if (isInvalid) {
                val errorContainerColor = MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorErrorContainer,
                    Color.RED,
                )
                val onErrorContainerColor = MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorOnErrorContainer,
                    Color.WHITE,
                )
                holder.card.setCardBackgroundColor(errorContainerColor)
                holder.name.setTextColor(onErrorContainerColor)
                holder.stateIcon.imageTintList = ColorStateList.valueOf(onErrorContainerColor)
                holder.stateIcon.visibility = View.VISIBLE
            } else {
                // RecyclerView 会复用非法课程卡，合法项必须显式恢复普通课程颜色与图标状态。
                holder.card.setCardBackgroundColor(
                    ColorUtils.blendARGB(
                        surfaceColor,
                        parsed,
                        0.32f
                    )
                )
                holder.name.setTextColor(onSurfaceColor)
                holder.stateIcon.visibility = View.GONE
            }
            holder.name.text = course.courseName
            holder.card.setOnClickListener {
                startActivity(
                    Intent(requireContext(), AddCourseActivity::class.java)
                        .putExtra(AddCourseActivity.EXTRA_COURSE_ID, course.id)
                        .putExtra(AddCourseActivity.EXTRA_TABLE_ID, course.tableId)
                )
            }
            holder.card.setOnLongClickListener {
                deleteCourse(course)
                true
            }
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)
    private class FooterHolder(view: View) : RecyclerView.ViewHolder(view)
    private class CourseHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cv_course)
        val name: TextView = view.findViewById(R.id.tv_course_name)
        val stateIcon: AppCompatImageView = view.findViewById(R.id.iv_course_state)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_COURSE = 1
        private const val TYPE_FOOTER = 2
    }
}
