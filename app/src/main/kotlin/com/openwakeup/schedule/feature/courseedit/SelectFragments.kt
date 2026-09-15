package com.openwakeup.schedule.feature.courseedit


import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.designsystem.component.SelectedRecyclerView
import com.openwakeup.schedule.core.designsystem.component.SquareTextView
import com.openwakeup.schedule.core.designsystem.component.colorpicker.ArgbAlphaTrackView
import com.openwakeup.schedule.core.designsystem.component.colorpicker.ArgbColorPreviewView
import com.openwakeup.schedule.core.designsystem.component.colorpicker.ColorPickerView
import com.openwakeup.schedule.core.format.AppDateFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 选择器三件套（加课页弹出）：选时间（fragment_select_time 三滚轮）/选周次
 * （fragment_select_week 宫格）/选颜色（fragment_color_picker 取色器）。
 */
class SelectTimeFragment : DialogFragment() {

    var onSaved: ((day: Int, startNode: Int, step: Int) -> Unit)? = null
    private var day = 1
    private var start = 1
    private var step = 1
    private var nodes = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        day = requireArguments().getInt(ARG_DAY, 1)
        start = requireArguments().getInt(ARG_START, 1)
        step = requireArguments().getInt(ARG_STEP, 1)
        nodes = requireArguments().getInt(ARG_NODES, 12).coerceAtLeast(1)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // AlertDialog 不会可靠地自动接管 DialogFragment 的 onCreateView 结果，必须显式 setView。
        val view = layoutInflater.inflate(R.layout.fragment_select_time, null, false)
        bindView(view)
        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    /** 当前语言的星期短名列表（替代旧的硬编码 DAY_NAMES 数组）。 */
    private fun dayNames(): Array<String> =
        (1..7).map { getString(AddCourseActivity.DAY_NAME_RES[it - 1]) }.toTypedArray()

    /** 初始化星期、起始节和结束节三个非循环滚轮。 */
    private fun bindView(view: View) {
        val wpDay =
            view.findViewById<com.openwakeup.schedule.core.designsystem.component.NumberPickerView>(
                R.id.wp_day
            )
        val wpStart =
            view.findViewById<com.openwakeup.schedule.core.designsystem.component.NumberPickerView>(
                R.id.wp_start
            )
        val wpEnd =
            view.findViewById<com.openwakeup.schedule.core.designsystem.component.NumberPickerView>(
                R.id.wp_end
            )
        // 三个滚轮全部为非循环模式；到达首尾后不能继续绕回另一端。
        wpDay.setDisplayedValues(dayNames())
        wpDay.setMinValue(0)
        wpDay.setMaxValue(7)
        wpDay.setWrapSelectorWheel(false)
        wpDay.value = (day - 1).coerceIn(0, 6)
        val nodeLabels = (1..nodes).map { getString(R.string.add_course_lesson, it) }.toTypedArray()
        wpStart.setDisplayedValues(nodeLabels)
        wpStart.setMinValue(1)
        wpStart.setMaxValue(nodes + 1)
        wpStart.setWrapSelectorWheel(false)
        wpStart.value = start.coerceIn(1, nodes)
        wpEnd.setDisplayedValues(nodeLabels)
        wpEnd.setMinValue(1)
        wpEnd.setMaxValue(nodes + 1)
        wpEnd.setWrapSelectorWheel(false)
        wpEnd.value = (start + step - 1).coerceIn(1, nodes)
        wpStart.setOnValueChangeListener { _, _, newV ->
            if (wpEnd.value < newV) wpEnd.value = newV
        }
        wpEnd.setOnValueChangeListener { _, _, newV ->
            if (wpStart.value > newV) wpStart.value = newV
        }
        view.findViewById<View>(R.id.btn_cancel)?.setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save)?.setOnClickListener {
            onSaved?.invoke(
                wpDay.value + 1,
                wpStart.value,
                (wpEnd.value - wpStart.value + 1).coerceAtLeast(1)
            )
            dismiss()
        }
    }

    companion object {
        private const val ARG_DAY = "day"
        private const val ARG_START = "start"
        private const val ARG_STEP = "step"
        private const val ARG_NODES = "nodes"

        fun newInstance(day: Int, start: Int, step: Int, nodes: Int): SelectTimeFragment =
            SelectTimeFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_DAY, day)
                    putInt(ARG_START, start)
                    putInt(ARG_STEP, step)
                    putInt(ARG_NODES, nodes)
                }
            }
    }
}

/** 周次选择宫格（SelectedRecyclerView + SquareTextView，快速选 全部/单周/双周） */
class SelectWeekFragment : DialogFragment() {

    var onSaved: ((weeks: BooleanArray) -> Unit)? = null
    private var maxWeek = AppDefaults.Table.MAX_WEEK
    private var selected = BooleanArray(0)
    private var semesterStart: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maxWeek = requireArguments().getInt(ARG_MAX_WEEK, 20).coerceAtLeast(1)
        val pre = requireArguments().getBooleanArray(ARG_SELECTED) ?: BooleanArray(maxWeek) { true }
        selected = BooleanArray(maxWeek) { pre.getOrNull(it) ?: false }
        semesterStart = runCatching {
            LocalDate.parse(requireArguments().getString(ARG_SEMESTER_START))
        }.getOrDefault(LocalDate.now())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.fragment_select_week, null, false)
        bindView(view)
        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    /** 初始化周次宫格、快捷模式和日期模式入口。 */
    private fun bindView(view: View) {
        val rv = view.findViewById<SelectedRecyclerView>(R.id.rv_week)
        val span = maxWeek.coerceAtMost(6)
        rv.layoutManager = StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL)
        val primary = MaterialColors.getColor(
            requireContext(),
            androidx.appcompat.R.attr.colorPrimary,
            "week grid"
        )
        val onSurface = MaterialColors.getColor(
            requireContext(),
            com.google.android.material.R.attr.colorOnSurface,
            "week grid"
        )
        var refreshShortcuts: () -> Unit = {}
        rv.adapter = object : RecyclerView.Adapter<WeekHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekHolder =
                WeekHolder(layoutInflater.inflate(R.layout.item_select_week, parent, false))

            override fun getItemCount(): Int = maxWeek

            override fun onBindViewHolder(holder: WeekHolder, position: Int) {
                val num = holder.tv
                num.text = (position + 1).toString()
                num.isSelected = selected[position]
                if (selected[position]) {
                    num.setTextColor(Color.WHITE)
                    num.setBackgroundResource(R.drawable.week_selected_bg)
                } else {
                    num.setTextColor(onSurface)
                    // 未选中周次不保留浅色圆底，仅显示纯文字。
                    num.background = null
                }
                num.alpha = 1f
                num.setOnClickListener {
                    selected[position] = !selected[position]
                    notifyItemChanged(position)
                    refreshShortcuts()
                }
            }
        }
        rv.positionChangedListener = { position, pressed ->
            if (pressed && position in selected.indices && !selected[position]) {
                selected[position] = true
                rv.adapter?.notifyItemChanged(position)
                refreshShortcuts()
            }
        }
        val all = view.findViewById<TextView>(R.id.tv_all)
        val odd = view.findViewById<TextView>(R.id.tv_type1)
        val even = view.findViewById<TextView>(R.id.tv_type2)
        refreshShortcuts = {
            styleShortcut(all, selected.all { it }, primary, onSurface)
            styleShortcut(
                odd,
                selected.indices.all { selected[it] == ((it + 1) % 2 == 1) },
                primary,
                onSurface,
            )
            styleShortcut(
                even,
                selected.indices.all { selected[it] == ((it + 1) % 2 == 0) },
                primary,
                onSurface,
            )
        }
        all.setOnClickListener {
            selected = BooleanArray(maxWeek) { true }
            rv.adapter?.notifyDataSetChanged()
            refreshShortcuts()
        }
        odd.setOnClickListener {
            selected = BooleanArray(maxWeek) { (it + 1) % 2 == 1 }
            rv.adapter?.notifyDataSetChanged()
            refreshShortcuts()
        }
        even.setOnClickListener {
            selected = BooleanArray(maxWeek) { (it + 1) % 2 == 0 }
            rv.adapter?.notifyDataSetChanged()
            refreshShortcuts()
        }
        refreshShortcuts()
        view.findViewById<View>(R.id.tv_date_mode)?.setOnClickListener { showDateModeNotice() }
        view.findViewById<View>(R.id.btn_cancel)?.setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save)?.setOnClickListener {
            if (selected.none { it }) {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.add_course_least_one_week,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            onSaved?.invoke(selected.copyOf())
            dismiss()
        }
    }

    /** 为“全周/单周/双周”快速入口绘制胶囊选中态。 */
    private fun styleShortcut(
        view: TextView,
        selected: Boolean,
        primary: Int,
        onSurface: Int
    ) {
        view.setTextColor(if (selected) Color.WHITE else onSurface)
        view.background = if (selected) {
            GradientDrawable().apply {
                setColor(primary)
                cornerRadius = 48f
            }
        } else {
            null
        }
    }

    /** 首次切换日期模式前展示“仍按周数存储”的说明。 */
    private fun showDateModeNotice() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_tips)
            .setMessage(R.string.add_course_date_mode_notice)
            .setPositiveButton(R.string.ok) { _, _ -> openDateMode() }
            .show()
    }

    /** 将当前周次选择转换成日期范围并打开不循环日期滚轮。 */
    private fun openDateMode() {
        val firstWeek = selected.indexOfFirst { it }.takeIf { it >= 0 }?.plus(1) ?: 1
        val lastWeek = selected.indexOfLast { it }.takeIf { it >= 0 }?.plus(1) ?: maxWeek
        val dateDialog = SelectDateRangeFragment.newInstance(
            semesterStart.toString(),
            maxWeek,
            firstWeek,
            lastWeek,
        ).apply {
            onSaved = { weeks -> this@SelectWeekFragment.onSaved?.invoke(weeks) }
        }
        val manager = parentFragmentManager
        dismissNow()
        dateDialog.show(manager, "date_range")
    }

    class WeekHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tv: SquareTextView = view.findViewById(R.id.tv_num)
    }

    companion object {
        private const val ARG_MAX_WEEK = "max_week"
        private const val ARG_SELECTED = "selected"
        private const val ARG_SEMESTER_START = "semester_start"

        fun newInstance(
            maxWeek: Int,
            selected: BooleanArray,
            semesterStart: String
        ): SelectWeekFragment =
            SelectWeekFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MAX_WEEK, maxWeek)
                    putBooleanArray(ARG_SELECTED, selected)
                    putString(ARG_SEMESTER_START, semesterStart)
                }
            }
    }
}

/**
 * 日期范围选择弹窗：使用年/月/日三个非循环滚轮，并在“开始/截止”之间切换当前编辑目标。
 * 最终结果仍转换成周次区间，以兼容课程时间段的数据模型。
 */
class SelectDateRangeFragment : DialogFragment() {

    var onSaved: ((weeks: BooleanArray) -> Unit)? = null

    private var semesterStart: LocalDate = LocalDate.now()
    private var semesterEnd: LocalDate = LocalDate.now()
    private var selectedStart: LocalDate = LocalDate.now()
    private var selectedEnd: LocalDate = LocalDate.now()
    private var maxWeek: Int = 20
    private var editingStart: Boolean = true
    private var refreshingPickers: Boolean = false

    private lateinit var wpYear: com.openwakeup.schedule.core.designsystem.component.NumberPickerView
    private lateinit var wpMonth: com.openwakeup.schedule.core.designsystem.component.NumberPickerView
    private lateinit var wpDay: com.openwakeup.schedule.core.designsystem.component.NumberPickerView
    private lateinit var pickerRow: View
    private lateinit var btnStart: com.google.android.material.button.MaterialButton
    private lateinit var btnEnd: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        maxWeek = args.getInt(ARG_MAX_WEEK, 20).coerceAtLeast(1)
        semesterStart = runCatching { LocalDate.parse(args.getString(ARG_SEMESTER_START)) }
            .getOrDefault(LocalDate.now())
        semesterEnd = semesterStart.plusWeeks(maxWeek.toLong()).minusDays(1)
        val startWeek = args.getInt(ARG_START_WEEK, 1).coerceIn(1, maxWeek)
        val endWeek = args.getInt(ARG_END_WEEK, maxWeek).coerceIn(startWeek, maxWeek)
        selectedStart = semesterStart.plusWeeks((startWeek - 1).toLong())
        selectedEnd = semesterStart.plusWeeks(endWeek.toLong()).minusDays(1)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.fragment_select_date_range, null, false)
        bindView(view)
        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    /** 初始化开始/截止切换按钮及年月日三个非循环滚轮。 */
    private fun bindView(view: View) {
        wpYear = view.findViewById(R.id.wp_year)
        wpMonth = view.findViewById(R.id.wp_month)
        wpDay = view.findViewById(R.id.wp_date_day)
        pickerRow = view.findViewById(R.id.date_picker_row)
        btnStart = view.findViewById(R.id.btn_date_start)
        btnEnd = view.findViewById(R.id.btn_date_end)

        listOf(wpYear, wpMonth, wpDay).forEach { it.setWrapSelectorWheel(false) }
        wpYear.setOnValueChangeListener { _, _, _ -> updateDateFromPickers() }
        wpMonth.setOnValueChangeListener { _, _, _ -> updateDateFromPickers() }
        wpDay.setOnValueChangeListener { _, _, _ -> updateDateFromPickers() }
        btnStart.setOnClickListener { switchEditingTarget(start = true) }
        btnEnd.setOnClickListener { switchEditingTarget(start = false) }
        view.findViewById<View>(R.id.tv_week_mode).setOnClickListener { openWeekMode() }
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener {
            onSaved?.invoke(selectedWeeks())
            dismiss()
        }
        refreshPickers()
    }

    /** 切换正在编辑的日期，并用一次短淡出/淡入明确表示滚轮数据已刷新。 */
    private fun switchEditingTarget(start: Boolean) {
        if (editingStart == start) return
        editingStart = start
        pickerRow.animate().cancel()
        pickerRow.animate().alpha(0.25f).setDuration(100L).withEndAction {
            refreshPickers()
            pickerRow.animate().alpha(1f).setDuration(180L).start()
        }.start()
    }

    /** 从三个滚轮合成日期，并限制开始日期不得晚于截止日期，截止日期不得早于开始日期。 */
    private fun updateDateFromPickers() {
        if (refreshingPickers) return
        val year = wpYear.value
        val month = wpMonth.value.coerceIn(1, 12)
        val maxDay = java.time.YearMonth.of(year, month).lengthOfMonth()
        val day = wpDay.value.coerceIn(1, maxDay)
        val minimum = if (editingStart) semesterStart else selectedStart
        val maximum = if (editingStart) selectedEnd else semesterEnd
        val candidate = clampDate(LocalDate.of(year, month, day), minimum, maximum)
        if (editingStart) selectedStart = candidate else selectedEnd = candidate
        refreshPickers()
    }

    /** 根据当前目标及另一端日期，重建三个连续但不循环的可选值域。 */
    private fun refreshPickers() {
        refreshingPickers = true
        val minimum = if (editingStart) semesterStart else selectedStart
        val maximum = if (editingStart) selectedEnd else semesterEnd
        var current = if (editingStart) selectedStart else selectedEnd
        current = clampDate(current, minimum, maximum)

        val years = (minimum.year..maximum.year).toList()
        configurePicker(wpYear, years, current.year)
        val year = wpYear.value
        val firstMonth = if (year == minimum.year) minimum.monthValue else 1
        val lastMonth = if (year == maximum.year) maximum.monthValue else 12
        val months = (firstMonth..lastMonth).toList()
        configurePicker(wpMonth, months, current.monthValue.coerceIn(firstMonth, lastMonth))
        val month = wpMonth.value

        val monthLength = java.time.YearMonth.of(year, month).lengthOfMonth()
        val firstDay =
            if (year == minimum.year && month == minimum.monthValue) minimum.dayOfMonth else 1
        val lastDay =
            if (year == maximum.year && month == maximum.monthValue) maximum.dayOfMonth else monthLength
        val days = (firstDay..lastDay).toList()
        configurePicker(
            wpDay,
            days,
            current.dayOfMonth.coerceIn(firstDay, lastDay),
            padTwoDigits = true
        )

        val normalized = LocalDate.of(wpYear.value, wpMonth.value, wpDay.value)
        if (editingStart) selectedStart = normalized else selectedEnd = normalized
        btnStart.text = formatDate(selectedStart)
        btnEnd.text = formatDate(selectedEnd)
        btnStart.isChecked = editingStart
        btnEnd.isChecked = !editingStart
        refreshingPickers = false
    }

    /** 配置一个以实际数字作为 value 的连续滚轮，最大值按控件的右开区间规则加一。 */
    private fun configurePicker(
        picker: com.openwakeup.schedule.core.designsystem.component.NumberPickerView,
        values: List<Int>,
        selected: Int,
        padTwoDigits: Boolean = false,
    ) {
        picker.setDisplayedValues(
            values.map { if (padTwoDigits) it.toString().padStart(2, '0') else it.toString() }
                .toTypedArray(),
        )
        picker.setMinValue(values.first())
        picker.setMaxValue(values.last() + 1)
        picker.setWrapSelectorWheel(false)
        picker.value = selected.coerceIn(values.first(), values.last())
    }

    /** 返回当前日期覆盖的连续周次选择。 */
    private fun selectedWeeks(): BooleanArray {
        val firstWeek = (ChronoUnit.DAYS.between(semesterStart, selectedStart) / 7L + 1L)
            .toInt().coerceIn(1, maxWeek)
        val lastWeek = (ChronoUnit.DAYS.between(semesterStart, selectedEnd) / 7L + 1L)
            .toInt().coerceIn(firstWeek, maxWeek)
        return BooleanArray(maxWeek) { index -> index + 1 in firstWeek..lastWeek }
    }

    /** 返回周模式时保留当前日期范围对应的周次。 */
    private fun openWeekMode() {
        val weeks = selectedWeeks()
        val weekDialog =
            SelectWeekFragment.newInstance(maxWeek, weeks, semesterStart.toString()).apply {
                onSaved = { selected -> this@SelectDateRangeFragment.onSaved?.invoke(selected) }
            }
        val manager = parentFragmentManager
        dismissNow()
        weekDialog.show(manager, "weeks")
    }

    private fun formatDate(date: LocalDate): String =
        AppDateFormatter.formatFull(date, Prefs.get(requireContext()).dateFormat)

    /** `LocalDate` 实现的是 `Comparable<ChronoLocalDate>`，因此使用显式边界比较保持返回类型准确。 */
    private fun clampDate(value: LocalDate, minimum: LocalDate, maximum: LocalDate): LocalDate =
        when {
            value.isBefore(minimum) -> minimum
            value.isAfter(maximum) -> maximum
            else -> value
        }

    companion object {
        private const val ARG_SEMESTER_START = "semester_start"
        private const val ARG_MAX_WEEK = "max_week"
        private const val ARG_START_WEEK = "start_week"
        private const val ARG_END_WEEK = "end_week"

        fun newInstance(
            semesterStart: String,
            maxWeek: Int,
            startWeek: Int,
            endWeek: Int,
        ): SelectDateRangeFragment = SelectDateRangeFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMESTER_START, semesterStart)
                putInt(ARG_MAX_WEEK, maxWeek)
                putInt(ARG_START_WEEK, startWeek)
                putInt(ARG_END_WEEK, endWeek)
            }
        }
    }
}

/** 取色器对话框（ColorPickerView + hex 输入 + 预览） */
class ColorPickerDialog : BottomSheetDialogFragment() {

    var onSaved: ((colorHex: String) -> Unit)? = null
    private var initialColor = "#2979ff"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialColor = requireArguments().getString(ARG_INITIAL, "#2979ff")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_color_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picker = view.findViewById<ColorPickerView>(R.id.cpv_color)
        val preview = view.findViewById<ArgbColorPreviewView>(R.id.v_color)
        val input = view.findViewById<EditText>(R.id.et_color)
        val opacitySlider = view.findViewById<Slider>(R.id.slider_color_opacity)
        val opacityLabel = view.findViewById<TextView>(R.id.tv_color_opacity)
        val opacityTrack = view.findViewById<ArgbAlphaTrackView>(R.id.alpha_color_track)
        var synchronizing = false

        /** 同步颜色、十六进制输入与透明度；保护回调，避免控件之间循环更新。 */
        fun synchronize(color: Int, updateInput: Boolean) {
            if (synchronizing) return
            synchronizing = true
            picker.color = color
            // 专用预览控件会在棋盘格上叠加完整 ARGB 色值，透明度变化可直接辨认。
            preview.color = color
            // ARGB 轨道读取当前 RGB，并始终展示从 0% 到 100% 的完整 alpha 范围。
            opacityTrack.color = color
            val percent = (Color.alpha(color) * 100f / 255f).roundToInt()
            opacitySlider.value = percent.toFloat()
            opacityLabel.text = getString(R.string.color_opacity_percent, percent)
            if (updateInput) input.setText(String.format("%08X", color))
            synchronizing = false
        }
        // 布局左侧已经单独显示“#”，输入框内部仅保留 AARRGGBB，避免出现两个井号。
        input.setText(initialColor.removePrefix("#"))
        synchronize(parseOrNull(initialColor) ?: Color.parseColor("#2979ff"), false)
        picker.setOnColorChangedListener { color ->
            synchronize(color, true)
        }
        opacitySlider.setLabelFormatter { "${it.roundToInt()}%" }
        opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !synchronizing) {
                // 只替换 alpha 字节，保留当前色相、饱和度和明度。
                val alpha = (value * 255f / 100f).roundToInt()
                synchronize((picker.color and 0x00FFFFFF) or (alpha shl 24), true)
            }
        }
        input.doAfterTextChanged { text ->
            if (!synchronizing) {
                // 输入未完整时保持现有预览；完整 RGB/ARGB 值实时更新滑条。
                parseOrNull("#${text.toString().trim()}")?.let { synchronize(it, false) }
            }
        }
        view.findViewById<View>(R.id.btn_cancel)?.setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save)?.setOnClickListener {
            val typed = input.text?.toString()?.trim() ?: ""
            val color = if (typed.isNotEmpty() && !typed.startsWith("#")) "#$typed" else typed
            val resolved = parseOrNull(color) ?: picker.color
            onSaved?.invoke(String.format("#%08X", resolved))
            dismiss()
        }
    }

    private fun parseOrNull(color: String): Int? = try {
        Color.parseColor(color)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val ARG_INITIAL = "initial"

        fun newInstance(initial: String): ColorPickerDialog =
            ColorPickerDialog().apply {
                arguments = Bundle().apply { putString(ARG_INITIAL, initial) }
            }
    }
}
