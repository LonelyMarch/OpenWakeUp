package com.openwakeup.schedule.feature.clock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityClockBinding
import com.openwakeup.schedule.feature.settings.schedule.ScheduleSettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 课程时钟页面：TextClock 大字时间/日期 + 当前课程块（节次范围/跑马灯课名/
 * 教室/教师/备注）+ 后续课程列表；ClockTheme 全屏，分钟级刷新，无返回键，BACK 直接退出。
 */
class ClockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClockBinding
    private val repo by lazy { ScheduleRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000L)
        }
    }

    private data class NodeTime(val node: Int, val start: LocalTime, val end: LocalTime)

    private var nodeTimes: List<NodeTime> = emptyList()
    private var startDate: LocalDate? = null
    private var maxWeek = AppDefaults.Table.MAX_WEEK
    private var courses: List<Pair<CourseEntity, CourseDetailEntity>> = emptyList()
    private var times: List<TimeDetailEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ClockTheme windowFullscreen 全屏；BACK 直接退出（无返回键）
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.ibSettings.setOnClickListener {
            startActivity(Intent(this, ScheduleSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 日期行跟随全局日期格式（EEEE 由系统按当前 locale 输出本地化星期）；
        // 时间行仍由 TextClock 按 HH:mm 自行刷新。
        val datePattern = Prefs.get(this).dateFormat.fullPattern + " EEEE"
        binding.textDate.format12Hour = datePattern
        binding.textDate.format24Hour = datePattern
        lifecycleScope.launch {
            val tableId = repo.currentTableId()
            val t = repo.tableOnce(tableId)
            if (t != null) {
                maxWeek = t.maxWeek
                startDate = LocalDate.parse(t.startDate)
                times = repo.timeDetailsOnce(t.timeTableId)
                nodeTimes = times.map {
                    NodeTime(it.node, LocalTime.parse(it.startTime), LocalTime.parse(it.endTime))
                }.sortedBy { it.node }
                val cwds = repo.coursesWithDetails(tableId).first()
                val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(t.nodes)
                courses = cwds
                    .filter { courseWithDetails ->
                        CourseRangePolicy.isCourseValid(courseWithDetails.details, visibleNodeLimit)
                    }
                    .flatMap { courseWithDetails ->
                        courseWithDetails.details.map { detail -> courseWithDetails.course to detail }
                    }
                render()
            }
            tick.run()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val now = LocalTime.now()
        val start = startDate ?: return
        val week = DateUtils.currentWeek(start).coerceIn(1, maxWeek)

        val inWeek = courses.filter { (_, d) ->
            week in d.startWeek..d.endWeek &&
                    (d.type == 0 || (d.type == 1 && week % 2 == 1) || (d.type == 2 && week % 2 == 0))
        }
        val todayCourses = inWeek.filter { it.second.day == LocalDate.now().dayOfWeek.value }
            .sortedBy { it.second.startNode }

        // 当前课程块
        val current = todayCourses.firstOrNull { (_, d) ->
            val s = times.getOrNull(d.startNode - 1)?.startTime
            val e = times.getOrNull((d.startNode + d.step - 2).coerceIn(0, times.size - 1))?.endTime
            s != null && e != null && !now.isBefore(LocalTime.parse(s)) && !now.isAfter(
                LocalTime.parse(
                    e
                )
            )
        }
        if (current != null) {
            val (course, d) = current
            val e = times.getOrNull((d.startNode + d.step - 2).coerceIn(0, times.size - 1))?.endTime
            binding.courseTime.text = getString(
                R.string.course_time_range,
                d.startNode, d.startNode + d.step - 1,
                times.getOrNull(d.startNode - 1)?.startTime ?: "", e ?: "",
            )
            binding.currentCourseName.text = course.courseName
            binding.tvRoom.text = course.let { d.room }
            binding.tvTeacher.text = d.teacher
            binding.tvNote.text = ""
            binding.currentContent.visibility = View.VISIBLE
        } else {
            binding.currentContent.visibility = View.GONE
        }

        // 后续课程列表（当天剩余）
        val upcoming = todayCourses.filter { (_, d) ->
            val s = times.getOrNull(d.startNode - 1)?.startTime
            s != null && LocalTime.parse(s).isAfter(now)
        }
        binding.rvList.adapter = object : RecyclerView.Adapter<UpcomingHolder>() {
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int
            ): UpcomingHolder {
                val tv = android.widget.TextView(parent.context).apply {
                    setPadding(24, 16, 24, 16)
                    textSize = 14f
                }
                return UpcomingHolder(tv)
            }

            override fun getItemCount(): Int = upcoming.size

            override fun onBindViewHolder(holder: UpcomingHolder, position: Int) {
                val (course, d) = upcoming[position]
                val s = times.getOrNull(d.startNode - 1)?.startTime ?: ""
                val e =
                    times.getOrNull((d.startNode + d.step - 2).coerceIn(0, times.size - 1))?.endTime
                        ?: ""
                holder.tv.text = getString(
                    R.string.clock_upcoming_course,
                    d.startNode,
                    d.startNode + d.step - 1,
                    s,
                    e,
                    course.courseName,
                    d.room,
                )
            }
        }
    }

    class UpcomingHolder(val tv: android.widget.TextView) : RecyclerView.ViewHolder(tv)
}
