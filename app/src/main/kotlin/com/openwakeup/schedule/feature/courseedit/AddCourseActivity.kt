package com.openwakeup.schedule.feature.courseedit

import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.designsystem.component.SwitchMotion
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityAddCourseBinding
import com.openwakeup.schedule.databinding.ItemAddCourseBaseBinding
import com.openwakeup.schedule.databinding.ItemAddCourseBtnBinding
import com.openwakeup.schedule.databinding.ItemAddCourseDetailBinding
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * 添加/编辑课程页面：
 * RecyclerView(anko_layout) 三类条目——基础项（课名/选取颜色/学分/备注，item_add_course_base）、
 * 时间段项（周次/节次/自定义时间/教师/教室/备注 + 删除，item_add_course_detail）、
 * "添加时间段"项（item_add_course_btn）；右下 FAB 同"添加时间段"；SAVE 保存；
 * 有改动按 BACK → "Need to save the current edits?"（不保留退出/保存）。
 */
class AddCourseActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityAddCourseBinding
    private val rows = mutableListOf<Row>()
    private var tableId: Long = 0
    private var editCourseId: Long = 0
    private var courseName: String = ""
    private var selectedColor = "#2979ff"
    private var credit: Float = 0f
    private var note: String = ""
    private var dirty = false
    private var maxWeek = AppDefaults.Table.MAX_WEEK
    private var semesterStart = LocalDate.now().toString()
    private var nodes = 12

    /** 一条时间安排的界面状态 */
    class Row(
        var day: Int = 1,
        var startNode: Int = 1,
        var step: Int = 2,
        var startWeek: Int = 1,
        var endWeek: Int = 20,
        var type: Int = CourseDetailEntity.TYPE_ALL,
        var teacher: String = "",
        var room: String = "",
        var ownTime: Boolean = false,
        var startTime: String = "",
        var endTime: String = "",
        /** 手工逐周选择；为空时由 startWeek/endWeek/type 生成。 */
        var weekSelection: BooleanArray? = null,
    )

    private lateinit var adapter: CourseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAddCourseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        tableId = intent.getLongExtra(EXTRA_TABLE_ID, 0L)
        editCourseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.fabAddTime.setOnClickListener { addRow() }
        binding.btnSave.setOnClickListener {
            // 先收起键盘再保存退出
            currentFocus?.let { v ->
                val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            saveAndExit()
        }

        adapter = CourseAdapter()
        binding.ankoLayout.layoutManager = LinearLayoutManager(this)
        binding.ankoLayout.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) {
                    finish()
                    return
                }
                MaterialAlertDialogBuilder(this@AddCourseActivity)
                    .setMessage(R.string.need_save_tip)
                    .setPositiveButton(R.string.save) { _, _ -> saveAndExit() }
                    .setNegativeButton(R.string.exit) { _, _ -> dirty = false; finish() }
                    .show()
            }
        })

        lifecycleScope.launch {
            refreshTableInfo()
            if (editCourseId > 0L) {
                val cwd = repo.courseWithDetailsOnce(editCourseId)
                if (cwd != null) {
                    courseName = cwd.course.courseName
                    selectedColor = cwd.course.color
                    credit = cwd.course.credit
                    note = cwd.course.note
                    rows.clear()
                    cwd.details.forEach { d ->
                        rows.add(
                            Row(
                                d.day, d.startNode, d.step, d.startWeek, d.endWeek, d.type,
                                d.teacher, d.room, d.ownTime, d.startTime, d.endTime
                            )
                        )
                    }
                    adapter.notifyItemRangeChanged(1, rows.size)
                }
            } else {
                // 快速加课入口：按主课表草稿预填首行（星期/起始节/跨度/仅所选周）；
                // 顶部"＋"等旧入口不带 source 标记，走默认行。
                val prefill =
                    com.openwakeup.schedule.feature.schedule.quickadd.QuickAddIntentContract
                        .readPrefill(intent, maxWeek, nodes)
                rows.add(
                    if (prefill != null) {
                        Row(
                            day = prefill.day,
                            startNode = prefill.startNode,
                            step = prefill.step,
                            startWeek = prefill.week,
                            endWeek = prefill.week,
                        )
                    } else {
                        Row(endWeek = maxWeek)
                    },
                )
                adapter.notifyItemInserted(1)
            }
            adapter.notifyDataSetChanged()
        }
    }

    private suspend fun refreshTableInfo() {
        val t = if (tableId > 0L) repo.tableOnce(tableId) else null
        if (t != null) {
            maxWeek = t.maxWeek.coerceAtLeast(1)
            semesterStart = t.startDate
            // 节次选择器与主课表都以“一天课程节数”为准；绑定作息不足的行由主课表显示
            // 24:00-24:00，不应反向缩小用户可以选择的节次范围。
            nodes = CourseRangePolicy.visibleNodeLimit(t.nodes).coerceAtLeast(1)
        }
    }

    private fun addRow() {
        rows.add(Row(endWeek = maxWeek))
        dirty = true
        adapter.notifyItemInserted(1 + rows.size - 1)
        binding.ankoLayout.scrollToPosition(1 + rows.size - 1)
    }

    private fun markDirty() {
        dirty = true
    }

    private fun saveAndExit() {
        val name = adapter.baseHolder?.binding?.etName?.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return
        courseName = name
        flushVisibleDetailEditors()

        // 保存前再次执行兜底校验，阻止旧数据或异常状态写入不合法的自定义时间范围。
        if (rows.any { row ->
                row.ownTime && !isCustomTimeRangeValid(row.startTime, row.endTime)
            }) {
            showInvalidCustomTimeDialog()
            return
        }
        dirty = false
        lifecycleScope.launch {
            if (editCourseId > 0L) {
                repo.updateCourse(
                    editCourseId,
                    name,
                    selectedColor,
                    "",
                    "",
                    rows.flatMap { it.toEntities() },
                    credit,
                    note,
                )
            } else {
                repo.addCourse(
                    tableId,
                    name,
                    selectedColor,
                    "",
                    "",
                    rows.flatMap { it.toEntities() },
                    credit,
                    note,
                )
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    /**
     * 将任意逐周选择转换为数据库可表达的一个或多个区间。
     *
     * 连续、单周和双周模式只生成一条记录；不规则选择拆成若干连续区间，确保用户逐个关闭的
     * 周次不会在保存后被错误补回。
     */
    private fun Row.toEntities(): List<CourseDetailEntity> {
        // 从课程管理页打开非法课程时，用户可能只修改名称、教师或地点。只要没有主动打开并
        // 保存周次选择器，就原样保留导入的越界周次，避免一次无关编辑把时间段静默删除。
        val weeks = weekSelection ?: return listOf(toEntity(startWeek, endWeek, type))
        val selectedWeeks = weeks.indices.filter { weeks[it] }.map { it + 1 }
        if (selectedWeeks.isEmpty()) return emptyList()
        val first = selectedWeeks.first()
        val last = selectedWeeks.last()
        val singlePattern = detectWeekPattern(weeks, first, last)
        if (singlePattern != null) {
            return listOf(toEntity(first, last, singlePattern))
        }

        val result = mutableListOf<CourseDetailEntity>()
        var cursor = first
        while (cursor <= last) {
            if (!weeks[cursor - 1]) {
                cursor++
                continue
            }
            val rangeStart = cursor
            while (cursor < last && weeks[cursor]) cursor++
            result += toEntity(rangeStart, cursor, CourseDetailEntity.TYPE_ALL)
            cursor++
        }
        return result
    }

    /** 根据指定周次范围创建数据库课程时间段。 */
    private fun Row.toEntity(startWeek: Int, endWeek: Int, weekType: Int): CourseDetailEntity =
        CourseDetailEntity(
            courseId = editCourseId,
            day = day,
            startNode = startNode,
            step = step,
            startWeek = startWeek,
            endWeek = endWeek,
            type = weekType,
            teacher = teacher,
            room = room,
            ownTime = ownTime,
            startTime = startTime,
            endTime = endTime,
        )

    /** 保存按钮可能在输入框仍有焦点时触发，因此主动同步所有仍显示在屏幕上的编辑器。 */
    private fun flushVisibleDetailEditors() {
        rows.indices.forEach { index ->
            val holder =
                binding.ankoLayout.findViewHolderForAdapterPosition(index + 1) as? DetailHolder
                    ?: return@forEach
            rows[index].teacher = holder.binding.etTeacher.text?.toString().orEmpty()
            rows[index].room = holder.binding.etRoom.text?.toString().orEmpty()
        }
    }

    // ================= RecyclerView（三类条目） =================

    private inner class CourseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var baseHolder: BaseHolder? = null

        override fun getItemViewType(position: Int): Int = when {
            position == 0 -> TYPE_BASE
            position <= rows.size -> TYPE_DETAIL
            else -> TYPE_BTN
        }

        override fun getItemCount(): Int = rows.size + 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_BASE -> BaseHolder(
                    ItemAddCourseBaseBinding.inflate(layoutInflater, parent, false)
                )

                TYPE_DETAIL -> DetailHolder(
                    ItemAddCourseDetailBinding.inflate(layoutInflater, parent, false)
                )

                else -> BtnHolder(
                    ItemAddCourseBtnBinding.inflate(layoutInflater, parent, false)
                )
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is BaseHolder -> {
                    baseHolder = holder
                    // 四个基础项使用与全局设置相同的 first/middle/last 分段形状，不绘制横线。
                    listOf(
                        holder.binding.baseNameItem to holder.binding.baseNameCard,
                        holder.binding.baseColorItem to holder.binding.baseColorCard,
                        holder.binding.baseCreditItem to holder.binding.baseCreditCard,
                        holder.binding.baseNoteItem to holder.binding.baseNoteCard,
                    ).forEachIndexed { index, (itemLayout, card) ->
                        SettingsAppearance.applySegmentedCard(itemLayout, card, index, count = 4)
                    }
                    if (!holder.binding.etName.hasFocus() && holder.binding.etName.text?.toString() != courseName) {
                        holder.binding.etName.setText(courseName)
                    }
                    holder.binding.etName.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            courseName = holder.binding.etName.text?.toString().orEmpty()
                            markDirty()
                        }
                    }
                    holder.binding.tvCredit.text = formatCredit(credit)
                    holder.binding.tvNote.text = note
                    val swatchColor = runCatching { Color.parseColor(selectedColor) }
                        .getOrDefault(Color.parseColor("#2979ff"))
                    // 与课表外观页颜色项一致，仅 tint 内部圆形填充，保留外层描边环。
                    holder.binding.ivColor.foregroundTintList =
                        android.content.res.ColorStateList.valueOf(swatchColor)
                    holder.binding.llColor.setOnClickListener {
                        ColorPickerDialog.newInstance(selectedColor).apply {
                            onSaved = { hex ->
                                selectedColor = hex
                                markDirty()
                                notifyItemChanged(0)
                            }
                        }.show(supportFragmentManager, "color")
                    }
                    holder.binding.llCredit.setOnClickListener {
                        showCreditDialog()
                    }
                    holder.binding.llNote.setOnClickListener {
                        showNoteDialog()
                    }
                }

                is DetailHolder -> {
                    val row = rows[position - 1]
                    // 周次、时间、教师、教室组成四段设置卡，图标与正文共享统一左基线。
                    listOf(
                        holder.binding.detailWeeksItem to holder.binding.detailWeeksCard,
                        holder.binding.detailTimeItem to holder.binding.detailTimeCard,
                        holder.binding.detailTeacherItem to holder.binding.detailTeacherCard,
                        holder.binding.detailRoomItem to holder.binding.detailRoomCard,
                    ).forEachIndexed { index, (itemLayout, card) ->
                        SettingsAppearance.applySegmentedCard(itemLayout, card, index, count = 4)
                    }
                    holder.binding.tvItem.text = getString(R.string.time_period)
                    holder.binding.etWeeks.text = formatWeekSummary(row)
                    holder.binding.etTime.text = if (row.ownTime) {
                        getString(
                            R.string.course_time_bean_to_string,
                            row.startNode,
                            row.startNode + row.step - 1,
                            dayName(row.day)
                        )
                    } else {
                        getString(
                            R.string.course_time_bean_to_string,
                            row.startNode,
                            row.startNode + row.step - 1,
                            dayName(row.day)
                        )
                    }
                    holder.binding.cbOwnTime.setOnCheckedChangeListener(null)
                    // 开关与“自定义时间”标签同进同出：只切换外层容器，开关本体始终可见。
                    holder.binding.llOwnTimeToggle.visibility = View.VISIBLE
                    holder.binding.cbOwnTime.isChecked = row.ownTime
                    holder.binding.llOwnTime.visibility =
                        if (row.ownTime) View.VISIBLE else View.GONE
                    holder.binding.btnStartTime.text = row.startTime.ifBlank { "08:00" }
                    holder.binding.btnEndTime.text = row.endTime.ifBlank { "08:45" }

                    // ToggleGroup 会在添加子按钮时重新打开 checkable；运行时显式关闭，防止仅仅
                    // 打开时间弹窗就留下紫色选中态，尤其是在点击空白处取消弹窗之后。
                    holder.binding.btnStartTime.isCheckable = false
                    holder.binding.btnEndTime.isCheckable = false
                    holder.binding.etTeacher.setText(row.teacher)
                    holder.binding.etRoom.setText(row.room)
                    holder.binding.etTeacher.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) row.teacher =
                            holder.binding.etTeacher.text.toString(); markDirty()
                    }
                    holder.binding.etRoom.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) row.room = holder.binding.etRoom.text.toString(); markDirty()
                    }
                    holder.binding.llWeeks.setOnClickListener {
                        SelectWeekFragment.newInstance(maxWeek, weekMask(row), semesterStart)
                            .apply {
                                onSaved = { weeks ->
                                    applyWeekSelection(row, weeks)
                                    markDirty()
                                    notifyItemChanged(position)
                                }
                            }.show(supportFragmentManager, "weeks")
                    }
                    holder.binding.llTime.setOnClickListener {
                        SelectTimeFragment.newInstance(row.day, row.startNode, row.step, nodes)
                            .apply {
                                onSaved = { day, start, step ->
                                    row.day = day
                                    row.startNode = start
                                    row.step = step
                                    markDirty()
                                    notifyItemChanged(position)
                                }
                            }.show(supportFragmentManager, "time")
                    }
                    holder.binding.llOwnTimeToggle.setOnClickListener {
                        // 第二行标签与 Switch 视为同一个操作区，点击文字也能切换自定义时间。
                        holder.binding.cbOwnTime.performClick()
                    }
                    holder.binding.cbOwnTime.setOnCheckedChangeListener { _, checked ->
                        SwitchMotion.play(holder.binding.cbOwnTime)
                        row.ownTime = checked
                        if (checked) {
                            // 首次开启时把界面展示的默认值同步到模型，确保后续校验和数据库一致。
                            row.startTime = row.startTime.ifBlank { DEFAULT_START_TIME }
                            row.endTime = row.endTime.ifBlank { DEFAULT_END_TIME }
                        }
                        // Switch 使用全局延长动效；自定义时间区域仍走统一的高度展开/收起动画。
                        SettingsAppearance.animateVisibility(
                            holder.binding.llOwnTime,
                            show = checked,
                        )
                        markDirty()
                    }
                    holder.binding.btnStartTime.setOnClickListener {
                        pickTime(
                            title = getString(R.string.add_course_start_time),
                            current = row.startTime.ifBlank { DEFAULT_START_TIME },
                        ) { candidate ->
                            val endTime = row.endTime.ifBlank { DEFAULT_END_TIME }
                            if (isCustomTimeRangeValid(candidate, endTime)) {
                                row.startTime = candidate
                                markDirty()
                                notifyItemChanged(position)
                            } else {
                                showInvalidCustomTimeDialog()
                            }
                        }
                    }
                    holder.binding.btnEndTime.setOnClickListener {
                        pickTime(
                            title = getString(R.string.add_course_end_time),
                            current = row.endTime.ifBlank { DEFAULT_END_TIME },
                        ) { candidate ->
                            val startTime = row.startTime.ifBlank { DEFAULT_START_TIME }
                            if (isCustomTimeRangeValid(startTime, candidate)) {
                                row.endTime = candidate
                                markDirty()
                                notifyItemChanged(position)
                            } else {
                                showInvalidCustomTimeDialog()
                            }
                        }
                    }
                    holder.binding.ibDelete.setOnClickListener {
                        rows.removeAt(position - 1)
                        markDirty()
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, rows.size - position + 1)
                    }
                }

                is BtnHolder -> {
                    holder.binding.tvAdd.setOnClickListener { addRow() }
                }
            }
        }
    }

    private fun weekMask(row: Row): BooleanArray {
        row.weekSelection?.takeIf { it.size == maxWeek }?.let { return it.copyOf() }
        val weeks = BooleanArray(maxWeek) { i ->
            val w = i + 1
            w in row.startWeek..row.endWeek && when (row.type) {
                1 -> w % 2 == 1
                2 -> w % 2 == 0
                else -> true
            }
        }
        if (weeks.none { it }) {
            for (i in row.startWeek - 1 until row.endWeek.coerceAtMost(maxWeek)) {
                if (i in weeks.indices) weeks[i] = true
            }
        }
        return weeks
    }

    /** 保存弹窗返回的逐周选择，并同步用于列表摘要的起止周及单双周类型。 */
    private fun applyWeekSelection(row: Row, weeks: BooleanArray) {
        val normalized = BooleanArray(maxWeek) { weeks.getOrNull(it) ?: false }
        val first = normalized.indexOfFirst { it }.takeIf { it >= 0 }?.plus(1) ?: return
        val last = normalized.indexOfLast { it }.plus(1)
        row.weekSelection = normalized
        row.startWeek = first
        row.endWeek = last
        row.type = detectWeekPattern(normalized, first, last) ?: CourseDetailEntity.TYPE_ALL
    }

    /** 判断选择能否用单条“每周/单周/双周”区间无损表示。 */
    private fun detectWeekPattern(weeks: BooleanArray, first: Int, last: Int): Int? {
        if ((first..last).all { weeks.getOrNull(it - 1) == true }) return CourseDetailEntity.TYPE_ALL
        if ((first..last).all { week -> weeks.getOrNull(week - 1) == (week % 2 == 1) }) {
            return CourseDetailEntity.TYPE_ODD
        }
        if ((first..last).all { week -> weeks.getOrNull(week - 1) == (week % 2 == 0) }) {
            return CourseDetailEntity.TYPE_EVEN
        }
        return null
    }

    /** 列表中对不规则逐周选择显示准确周次，常规三种模式继续沿用原摘要格式。 */
    private fun formatWeekSummary(row: Row): String {
        val selection = row.weekSelection
        if (selection != null && detectWeekPattern(selection, row.startWeek, row.endWeek) == null) {
            val selected =
                selection.indices.filter { selection[it] }.joinToString("、") { (it + 1).toString() }
            return getString(R.string.selected_week_list, selected)
        }
        return getString(
            R.string.week_bean_to_string,
            row.startWeek,
            row.endWeek,
            when (row.type) {
                CourseDetailEntity.TYPE_ODD -> getString(R.string.week_type_odd)
                CourseDetailEntity.TYPE_EVEN -> getString(R.string.week_type_even)
                else -> ""
            },
        )
    }

    /**
     * 使用 Material 标准时间选择器编辑自定义上课或下课时间。
     *
     * 选择器固定使用 24 小时制并默认进入表盘模式。MaterialTimePicker 自带左下角模式按钮，
     * 用户可随时切换表盘与键盘输入；点击确定后才写回数据，点击取消、返回键或弹窗外空白处
     * 均不会修改原时间。
     *
     * @param title 弹窗顶部标题，用于区分上课时间和下课时间。
     * @param current 当前 `HH:mm` 时间；格式异常时安全回退到 08:00。
     * @param onPick 用户确认后返回格式化为 `HH:mm` 的时间。
     */
    private fun pickTime(title: CharSequence, current: String, onPick: (String) -> Unit) {
        // 避免用户连续点击两个按钮时叠加多个时间弹窗。
        if (supportFragmentManager.findFragmentByTag(CUSTOM_TIME_PICKER_TAG) != null) return

        val parts = current.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: DEFAULT_TIME_HOUR
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: DEFAULT_TIME_MINUTE
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            onPick(String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute))
        }
        picker.show(supportFragmentManager, CUSTOM_TIME_PICKER_TAG)
    }

    /**
     * 判断同一天内的自定义结束时间是否严格晚于开始时间。
     *
     * @param startTime `HH:mm` 格式的开始时间。
     * @param endTime `HH:mm` 格式的结束时间。
     * @return 两个时间均有效且结束时间严格更晚时返回 `true`。
     */
    private fun isCustomTimeRangeValid(startTime: String, endTime: String): Boolean {
        val startMinutes = parseTimeToMinutes(startTime) ?: return false
        val endMinutes = parseTimeToMinutes(endTime) ?: return false
        return endMinutes > startMinutes
    }

    /** 将 `HH:mm` 转换为当天分钟数；格式或数值越界时返回 `null`。 */
    private fun parseTimeToMinutes(value: String): Int? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * MINUTES_PER_HOUR + minute
    }

    /**
     * 在屏幕中央提示时间范围无效。
     *
     * 使用 `post` 等待 MaterialTimePicker 完成关闭，再展示提示弹窗，避免两个 Dialog 的窗口
     * 动画重叠；候选时间尚未写入模型，因此关闭提示后仍保留修改前的时间。
     */
    private fun showInvalidCustomTimeDialog() {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_course_invalid_time_title)
                .setMessage(R.string.add_course_invalid_time_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /** 居中学分输入框，允许小数并提供清除按钮。 */
    private fun showCreditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(formatCredit(credit).ifBlank { "0.0" })
        input.setSelection(input.text?.length ?: 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.credit)
            .setView(dialogView)
            .setNeutralButton(R.string.clear_input, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            credit = 0f
            markDirty()
            adapter.notifyItemChanged(0)
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text?.toString()?.trim()?.toFloatOrNull()
            if (value == null || !value.isFinite() || value < 0f) {
                inputLayout.error = getString(R.string.credit_invalid)
                return@setOnClickListener
            }
            credit = value
            markDirty()
            adapter.notifyItemChanged(0)
            dialog.dismiss()
        }
    }

    /** 显示带 300 字计数器的居中多行备注弹窗，并提供清除、取消和确定操作。 */
    private fun showNoteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        // counterEnabled getter 在 Material 库中是 package-private，Kotlin 属性语法不可见，走公开 setter
        inputLayout.isCounterEnabled = true
        inputLayout.counterMaxLength = MAX_NOTE_LENGTH
        input.isSingleLine = false
        input.minLines = 3
        input.maxLines = 5
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.filters = arrayOf(InputFilter.LengthFilter(MAX_NOTE_LENGTH))
        input.setText(note)
        input.setSelection(input.text?.length ?: 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note)
            .setView(dialogView)
            .setNeutralButton(R.string.clear_input, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            note = ""
            markDirty()
            adapter.notifyItemChanged(0)
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            note = input.text?.toString().orEmpty().take(MAX_NOTE_LENGTH)
            markDirty()
            adapter.notifyItemChanged(0)
            dialog.dismiss()
        }
    }

    /** 学分为 0 时留空以显示“可不填”提示，其余值去掉无意义的小数尾零。 */
    private fun formatCredit(value: Float): String = when {
        value == 0f -> ""
        value % 1f == 0f -> value.toInt().toString()
        else -> value.toString().trimEnd('0').trimEnd('.')
    }

    private class BaseHolder(val binding: ItemAddCourseBaseBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class DetailHolder(val binding: ItemAddCourseDetailBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class BtnHolder(val binding: ItemAddCourseBtnBinding) :
        RecyclerView.ViewHolder(binding.root)

    /**
     * 返回当前语言的星期短名。
     *
     * 导入的非法课程可能暂时带有 1～7 之外的星期值；编辑页仍需正常打开并展示原值，
     * 让用户可以进入时间选择器完成修正，而不能因数组越界直接崩溃。
     *
     * @param day 数据库中的星期值
     * @return 合法值对应的本地化星期名；非法值返回带原数字的范围说明
     */
    private fun dayName(day: Int): String = DAY_NAME_RES.getOrNull(day - 1)
        ?.let { resourceId -> getString(resourceId) }
        ?: getString(R.string.import_invalid_day_reason, day)

    companion object {
        /** 课表色板（加课页颜色行/默认色） */
        val PALETTE = listOf(
            "#2979ff", "#ff1744", "#ff9100", "#1de9b6", "#fa6278", "#a375ff",
            "#2196f3", "#ff3d00", "#005caf",
        )
        private const val TYPE_BASE = 0
        private const val TYPE_DETAIL = 1
        private const val TYPE_BTN = 2
        private const val MAX_NOTE_LENGTH = 300
        private const val DEFAULT_TIME_HOUR = 8
        private const val DEFAULT_TIME_MINUTE = 0
        private const val DEFAULT_START_TIME = "08:00"
        private const val DEFAULT_END_TIME = "08:45"
        private const val MINUTES_PER_HOUR = 60
        private const val CUSTOM_TIME_PICKER_TAG = "add-course-custom-time-picker"
        const val EXTRA_TABLE_ID = "table_id"
        const val EXTRA_COURSE_ID = "course_id"

        /** 星期名资源 id（加课页滚轮与详情页共用，随应用语言切换）。 */
        val DAY_NAME_RES = intArrayOf(
            R.string.weekday_short_1, R.string.weekday_short_2, R.string.weekday_short_3,
            R.string.weekday_short_4, R.string.weekday_short_5, R.string.weekday_short_6,
            R.string.weekday_short_7,
        )
    }
}
