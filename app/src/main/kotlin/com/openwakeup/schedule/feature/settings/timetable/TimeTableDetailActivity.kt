package com.openwakeup.schedule.feature.settings.timetable

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
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
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.database.entity.TimeTableEntity
import com.openwakeup.schedule.core.designsystem.component.SwitchMotion
import com.openwakeup.schedule.core.util.ActivityTransitionCompat
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityTimeTableDetailBinding
import com.openwakeup.schedule.databinding.ItemTimeEditBinding
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import com.openwakeup.schedule.feature.settings.timetable.TimeTableDetailActivity.Companion.TIME_FORMATTER
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 单个时间表的详情编辑页。
 *
 * 列表页只负责选择和管理时间表，本页专门编辑每节课的起止时间；进入和退出均使用淡入淡出，
 * 避免原先在列表内突然展开大量输入框造成的视觉跳变。
 */
class TimeTableDetailActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityTimeTableDetailBinding
    private val rows = mutableListOf<Pair<String, String>>()
    private val timeAdapter = TimeAdapter()
    private var timeTableId: Long = -1L
    private var timeTable: TimeTableEntity? = null
    private var sameDuration: Boolean = AppDefaults.Timetable.SAME_DURATION
    private var durationMinutes: Int = AppDefaults.Timetable.DURATION_MINUTES
    private var loadingTimeTable: Boolean = true
    private var loadedSnapshot: EditorSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTimeTableDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsAppearance.applyCards(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        timeTableId = intent.getLongExtra(EXTRA_TIME_TABLE_ID, -1L)
        if (timeTableId <= 0L) {
            finish()
            return
        }

        binding.recyclerTime.layoutManager = LinearLayoutManager(this)
        binding.recyclerTime.adapter = timeAdapter
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnAddNode.setOnClickListener { addNode() }
        binding.btnSave.isEnabled = false
        binding.btnAddNode.isEnabled = false
        binding.checkSameDuration.isEnabled = false
        binding.rowSameDuration.setOnClickListener { binding.checkSameDuration.performClick() }
        binding.rowTimeTableName.setOnClickListener { showNameDialog() }
        binding.checkSameDuration.setOnCheckedChangeListener { _, checked ->
            if (!loadingTimeTable) {
                SwitchMotion.play(binding.checkSameDuration)
            }
            sameDuration = checked
            renderDurationSetting(animate = !loadingTimeTable)
            if (checked) {
                // 按当前时间表保存的时长立即同步所有结束时间。
                normalizeDurations()
            }
            timeAdapter.notifyDataSetChanged()
        }
        binding.rowDuration.setOnClickListener { showDurationDialog() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })
        loadTimeTable()
    }

    /** 加载时间表名称和所有节次时间。 */
    private fun loadTimeTable() {
        lifecycleScope.launch {
            val timeTable = repo.timeTableOnce(timeTableId) ?: run {
                finish()
                return@launch
            }
            this@TimeTableDetailActivity.timeTable = timeTable
            binding.tvTitle.setText(R.string.edit_time_table)
            binding.tvTimeTableName.text = timeTable.name
            rows.clear()
            rows.addAll(repo.timeDetailsOnce(timeTableId).map { it.startTime to it.endTime })
            sameDuration = timeTable.sameDuration
            durationMinutes =
                timeTable.durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
            binding.checkSameDuration.isChecked = sameDuration
            renderDurationSetting(animate = false)
            if (sameDuration) {
                normalizeDurations()
            }
            // 规范化完成后记录基线，避免加载阶段的自动结束时间计算被误判为用户修改。
            loadedSnapshot = currentSnapshot()
            timeAdapter.notifyDataSetChanged()
            binding.btnSave.isEnabled = true
            binding.checkSameDuration.isEnabled = true
            loadingTimeTable = false
            updateAddNodeButton()
        }
    }

    /**
     * 在时间表末尾追加一节课程时间。
     *
     * 新节从上一节结束时间开始，并按当前“一节课时长”计算默认结束时间；空时间表或上一节
     * 时间格式异常时从 08:00 开始，结束时间计算失败时回退到 08:45。这里只修改编辑器内存，
     * 仍需点击右上角保存后才会写入数据库。系统最多允许 24 节，达到上限后按钮自动禁用。
     */
    private fun addNode() {
        if (rows.size >= AppDefaults.Table.MAX_SUPPORTED_NODES) return
        val startTime = rows.lastOrNull()?.second
            ?.takeIf { candidate -> isEditableTime(candidate) }
            ?: DEFAULT_NEW_NODE_START_TIME
        val endTime = calculateEndTime(startTime).ifBlank { DEFAULT_NEW_NODE_END_TIME }
        rows.add(startTime to endTime)
        timeAdapter.notifyItemInserted(rows.lastIndex)
        updateAddNodeButton()

        // RecyclerView 关闭了嵌套滚动并按内容展开，因此滚动外层容器，让新节和添加按钮
        // 在较长时间表中仍能立即进入可视区域。
        binding.scrollContent.post {
            val contentHeight = binding.scrollContent.getChildAt(0)?.height ?: 0
            binding.scrollContent.smoothScrollTo(0, contentHeight)
        }
    }

    /** 同步添加按钮状态；数据尚未加载或已经达到 24 节时按钮不可用。 */
    private fun updateAddNodeButton() {
        binding.btnAddNode.isEnabled = !loadingTimeTable &&
                rows.size < AppDefaults.Table.MAX_SUPPORTED_NODES
    }

    /**
     * 判断字符串能否作为编辑时间表中的标准 24 小时时间。
     *
     * @param value 待检查的 `HH:mm` 文本
     * @return [TIME_FORMATTER] 能够完整解析时返回 `true`
     */
    private fun isEditableTime(value: String): Boolean = runCatching {
        LocalTime.parse(value, TIME_FORMATTER)
    }.isSuccess

    /** 保存当前输入并以淡出动画返回时间表列表。 */
    private fun saveAndFinish() {
        lifecycleScope.launch {
            timeTable?.let {
                repo.updateTimeTable(
                    it.copy(sameDuration = sameDuration, durationMinutes = durationMinutes),
                )
            }
            repo.replaceTimeDetails(timeTableId, rows)
            loadedSnapshot = currentSnapshot()
            finish()
        }
    }

    /**
     * 处理工具栏与系统返回手势。
     *
     * 名称、相同时长开关、单节时长或任意节次起止时间变化时，使用居中的 Material 弹窗
     * 询问是否保存；没有变化或数据仍未加载完成时直接退出。
     */
    private fun handleBackNavigation() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.need_save_tip)
            .setPositiveButton(R.string.save) { _, _ -> saveAndFinish() }
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .show()
    }

    /** 返回当前编辑器的不可变快照，用于比较是否存在未保存修改。 */
    private fun currentSnapshot(): EditorSnapshot = EditorSnapshot(
        name = timeTable?.name.orEmpty(),
        sameDuration = sameDuration,
        durationMinutes = durationMinutes,
        // toList 创建独立列表，防止后续原地修改 rows 同时改变基线内容。
        rows = rows.toList(),
    )

    /** 判断当前编辑状态是否偏离加载完成时记录的基线。 */
    private fun hasUnsavedChanges(): Boolean {
        val baseline = loadedSnapshot ?: return false
        return currentSnapshot() != baseline
    }

    /** Activity 退出时与进入动画配对，保持页面切换连续。 */
    override fun finish() {
        super.finish()
        ActivityTransitionCompat.applyClose(
            this,
            R.anim.activity_fade_anim_in,
            R.anim.activity_fade_anim_out,
        )
    }

    /** 时间编辑列表适配器；输入变化会直接回写内存中的对应节次。 */
    private inner class TimeAdapter : RecyclerView.Adapter<TimeHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimeHolder =
            TimeHolder(
                ItemTimeEditBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: TimeHolder, position: Int) {
            holder.bindingValues = true
            val (start, end) = rows[position]
            holder.binding.tvNode.text = getString(R.string.time_node_n, position + 1)
            holder.binding.editStart.setText(start)
            holder.binding.editEnd.setText(end)
            holder.binding.editEnd.isEnabled = !sameDuration
            holder.binding.editEnd.isFocusable = !sameDuration
            holder.binding.editEnd.isFocusableInTouchMode = !sameDuration
            holder.binding.editEnd.alpha = if (sameDuration) 0.38f else 1f
            holder.bindingValues = false
        }
    }

    /** 节次输入行；监听器只注册一次，避免 RecyclerView 重绑时叠加 TextWatcher。 */
    private inner class TimeHolder(val binding: ItemTimeEditBinding) :
        RecyclerView.ViewHolder(binding.root) {
        /** 绑定已有数据时不允许监听器覆盖正在加载的时间。 */
        var bindingValues: Boolean = false

        init {
            binding.editStart.addTextChangedListener(
                com.openwakeup.schedule.core.util.TextWatcherHelper { value ->
                    val position = bindingAdapterPosition
                    if (!bindingValues && position in rows.indices) {
                        val start = value ?: ""
                        val end =
                            if (sameDuration) calculateEndTime(start) else rows[position].second
                        rows[position] = start to end
                        if (sameDuration && binding.editEnd.text?.toString() != end) {
                            binding.editEnd.setText(end)
                        }
                    }
                },
            )
            binding.editEnd.addTextChangedListener(
                com.openwakeup.schedule.core.util.TextWatcherHelper { value ->
                    val position = bindingAdapterPosition
                    if (!bindingValues && !sameDuration && position in rows.indices) {
                        rows[position] = rows[position].first to (value ?: "")
                    }
                },
            )
        }
    }

    /** 将开始时间增加当前时间表的单节时长，输入不完整时返回空字符串。 */
    private fun calculateEndTime(start: String): String = runCatching {
        LocalTime.parse(start, TIME_FORMATTER).plusMinutes(durationMinutes.toLong())
            .format(TIME_FORMATTER)
    }.getOrDefault("")

    /** 名称行可点击编辑，直到点击右上角保存才写入数据库。 */
    private fun showNameDialog() {
        val current = timeTable ?: return
        val view = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val input = view.findViewById<TextInputEditText>(R.id.edit_text)
        input.setText(current.name)
        MaterialAlertDialogBuilder(this).setTitle(R.string.time_table_name).setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    timeTable = current.copy(name = name)
                    binding.tvTimeTableName.text = name
                }
            }.show()
    }

    /** 按固定时长重算当前所有节次的结束时间。 */
    private fun normalizeDurations() {
        rows.indices.forEach { index ->
            rows[index] = rows[index].first to calculateEndTime(rows[index].first)
        }
    }

    /** 根据相同时长开关显示或隐藏“一节课时长”设置行。 */
    private fun renderDurationSetting(animate: Boolean) {
        binding.tvDuration.text = getString(R.string.duration_minutes_format, durationMinutes)
        if (animate) {
            val rowHeight = (64f * resources.displayMetrics.density).toInt()
            SettingsAppearance.animateVisibility(
                binding.rowDuration,
                show = sameDuration,
                expandedHeightPx = rowHeight,
            )
        } else {
            binding.rowDuration.visibility = if (sameDuration) View.VISIBLE else View.GONE
            binding.rowDuration.alpha = 1f
            binding.rowDuration.translationY = 0f
        }
    }

    /** 弹出范围为 10–180 分钟的单节课时长设置框。 */
    private fun showDurationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout.helperText = getString(
            R.string.range_format,
            MIN_DURATION_MINUTES,
            MAX_DURATION_MINUTES,
        )
        inputLayout.suffixText = getString(R.string.minutes_unit)
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.setText(durationMinutes.toString())
        editText.setSelection(editText.text?.length ?: 0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.one_course_duration)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText.text?.toString()?.toIntOrNull()
            if (value == null || value !in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
                inputLayout.error =
                    getString(R.string.range_format, MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                return@setOnClickListener
            }
            durationMinutes = value
            normalizeDurations()
            renderDurationSetting(animate = false)
            timeAdapter.notifyDataSetChanged()
            dialog.dismiss()
        }
    }

    companion object {
        /** Intent 中传递时间表主键的参数名。 */
        const val EXTRA_TIME_TABLE_ID = "time_table_id"

        private const val MIN_DURATION_MINUTES = 10
        private const val MAX_DURATION_MINUTES = 180
        private const val DEFAULT_NEW_NODE_START_TIME = "08:00"
        private const val DEFAULT_NEW_NODE_END_TIME = "08:45"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    /** 编辑页用于脏数据比较的完整不可变状态。 */
    private data class EditorSnapshot(
        val name: String,
        val sameDuration: Boolean,
        val durationMinutes: Int,
        val rows: List<Pair<String, String>>,
    )
}
