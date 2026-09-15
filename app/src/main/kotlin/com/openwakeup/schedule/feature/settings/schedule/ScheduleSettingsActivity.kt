package com.openwakeup.schedule.feature.settings.schedule

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityScheduleSettingsBinding
import com.openwakeup.schedule.feature.schedulemanage.ScheduleManageActivity
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.HorizontalItem
import com.openwakeup.schedule.feature.settings.NumberItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.SettingsRestoreDialog
import com.openwakeup.schedule.feature.settings.SwitchItem
import com.openwakeup.schedule.feature.settings.VerticalItem
import com.openwakeup.schedule.feature.settings.timetable.TimeSettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 课表设置：RecyclerView 设置项列表，
 * 分组卡＝名称卡 / 课表数据卡（上课时间·第一周的第一天·日期调课·当前周·节数·周数·管理已添加课程）/
 * 显示选项卡（周六·周日·课程时间·教师·地点·非本周课程）/ 默认配置卡。
 * 数值行点击弹 dialog_edit_text（范围 helper + 前后缀），勾选行联动写库。
 */
class ScheduleSettingsActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityScheduleSettingsBinding
    private lateinit var adapter: SettingsListAdapter
    private var table: TableEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScheduleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        adapter = SettingsListAdapter()
        binding.rvList.layoutManager = LinearLayoutManager(this)
        (binding.rvList.itemAnimator as? DefaultItemAnimator)?.addDuration = 250
        binding.rvList.adapter = adapter
        adapter.onItemClickListener = ::onItemClicked
        adapter.onItemLongClickListener = { item, _ -> requestRestoreDefault(item) }
        adapter.onItemCheckListener = { item, checked ->
            when (item.name) {
                R.string.setting_show_sat -> updateTable { it.copy(showSat = checked) }
                R.string.setting_show_sun -> updateTable { it.copy(showSun = checked) }
                R.string.setting_item_show_time -> updateTable { it.copy(showTime = checked) }
                R.string.setting_item_show_teacher -> updateTable { it.copy(showTeacher = checked) }
                R.string.setting_item_show_location -> updateTable { it.copy(showLocation = checked) }
                R.string.setting_show_other_week -> updateTable { it.copy(showOtherWeekCourse = checked) }
            }
        }
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            // 支持从多课表管理页编辑符号进入：指定 tableId 时编辑该表（非当前表）
            val targetId = intent.getLongExtra(EXTRA_TABLE_ID, -1L)
            val t = if (targetId > 0) repo.tableOnce(targetId) else repo.currentTable.first()
            if (t == null) {
                // 该页面的全部设置都归属于具体课表；无课表时不展示一个无法操作的空列表。
                Toast.makeText(
                    this@ScheduleSettingsActivity,
                    R.string.no_current_table,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            table = t
            render()
        }
    }

    private fun updateTable(transform: (TableEntity) -> TableEntity) {
        val t = table ?: return
        lifecycleScope.launch {
            table = transform(t)
            repo.updateTable(table!!)
            render()
        }
    }

    private fun render() {
        val t = table ?: return
        adapter.submit(buildItems(t))
    }

    private fun buildItems(t: TableEntity): List<CategoryItem> {
        val startDate = runCatching { LocalDate.parse(t.startDate) }.getOrElse { LocalDate.now() }
        val dateText = AppDateFormatter.formatFullWithWeekday(startDate, Prefs.get(this).dateFormat)
        val override = t.currentWeekOverride.takeIf { it > 0 }
        val currentWeek = override ?: DateUtils.currentWeek(startDate)
        val currentWeekItem = NumberItem(
            R.string.setting_current_week, currentWeek, 1, t.maxWeek,
            getString(R.string.unit_week), getString(R.string.prefix_week),
            leadingIconRes = R.drawable.ms_view_week_24,
            format = {
                if (currentWeek < 1) getString(R.string.semester_not_start_yet)
                else getString(R.string.semester_ended)
            }
        )
        return listOf(
            CategoryItem(
                listOf(
                    HeaderItem(R.string.setting_blank),
                    HorizontalItem(
                        R.string.setting_schedule_name,
                        t.tableName,
                        R.drawable.ms_edit_24
                    ),
                )
            ),
            CategoryItem(
                listOf(
                    HeaderItem(R.string.setting_schedule_config),
                    HorizontalItem(
                        R.string.setting_class_time,
                        getString(R.string.click_here_to_change),
                        R.drawable.ms_schedule_24,
                        showChevron = true,
                    ),
                    HorizontalItem(
                        R.string.setting_term_start_date,
                        dateText,
                        R.drawable.ms_calendar_month_24
                    ),
                    HorizontalItem(
                        R.string.setting_modify_schedule_by_date,
                        getString(R.string.click_here_to_change),
                        R.drawable.ms_event_repeat_24,
                        showChevron = true,
                    ),
                    currentWeekItem,
                    NumberItem(
                        R.string.setting_nodes,
                        t.nodes,
                        1,
                        AppDefaults.Table.MAX_SUPPORTED_NODES,
                        getString(R.string.unit_lesson),
                        leadingIconRes = R.drawable.ms_format_list_numbered_24,
                    ),
                    NumberItem(
                        R.string.setting_weeks,
                        t.maxWeek,
                        1,
                        AppDefaults.Table.MAX_SUPPORTED_WEEKS,
                        getString(R.string.unit_week),
                        leadingIconRes = R.drawable.ms_calendar_month_24,
                    ),
                    HorizontalItem(
                        R.string.setting_manage_course,
                        "",
                        R.drawable.ms_school_24,
                        showChevron = true,
                    ),
                )
            ),
            CategoryItem(
                listOf(
                    HeaderItem(R.string.display_options),
                    SwitchItem(
                        R.string.setting_show_sat,
                        t.showSat,
                        leadingIconRes = R.drawable.ms_view_week_24
                    ),
                    SwitchItem(
                        R.string.setting_show_sun,
                        t.showSun,
                        leadingIconRes = R.drawable.ms_view_week_24
                    ),
                    SwitchItem(
                        R.string.setting_item_show_time,
                        t.showTime,
                        leadingIconRes = R.drawable.ms_schedule_24
                    ),
                    SwitchItem(
                        R.string.setting_item_show_teacher,
                        t.showTeacher,
                        leadingIconRes = R.drawable.ms_person_24
                    ),
                    SwitchItem(
                        R.string.setting_item_show_location,
                        t.showLocation,
                        leadingIconRes = R.drawable.ms_location_on_24
                    ),
                    SwitchItem(
                        R.string.setting_show_other_week,
                        t.showOtherWeekCourse,
                        leadingIconRes = R.drawable.ms_layers_24
                    ),
                )
            ),
            CategoryItem(
                listOf(
                    // 使用真实题头建立标准组间距，使单项卡片保持独立且四角圆润。
                    HeaderItem(R.string.setting_default_configuration),
                    VerticalItem(
                        R.string.setting_schedule_config_default,
                        getString(R.string.desc_schedule_config_default),
                        leadingIconRes = R.drawable.ms_save_24,
                    ),
                )
            ),
        )
    }

    private fun onItemClicked(item: SettingsItem, position: Int) {
        when (item.name) {
            R.string.setting_schedule_name -> showRenameDialog((item as HorizontalItem).value)
            R.string.setting_class_time -> {
                val intent = Intent(this, TimeSettingsActivity::class.java)
                val targetId = intent.getLongExtra(EXTRA_TABLE_ID, -1L)
                if (targetId > 0) intent.putExtra(EXTRA_TABLE_ID, targetId)
                startActivity(intent)
            }

            R.string.setting_term_start_date -> showDatePicker()
            R.string.setting_modify_schedule_by_date -> {
                val current = table ?: return
                startActivity(
                    Intent(this, ScheduleShiftActivity::class.java)
                        .putExtra(ScheduleShiftActivity.EXTRA_TABLE_ID, current.id),
                )
            }

            R.string.setting_manage_course -> {
                val t = table ?: return
                startActivity(
                    Intent(this, ScheduleManageActivity::class.java)
                        .putExtra(ScheduleManageActivity.EXTRA_SELECTED_TABLE_ID, t.id)
                )
            }

            R.string.setting_schedule_config_default -> showApplyDefaultDialog()
            else -> {
                if (item is NumberItem && table != null) {
                    showNumberDialog(item) { value ->
                        when (item.name) {
                            R.string.setting_current_week -> updateTable {
                                it.copy(
                                    currentWeekOverride = value
                                )
                            }

                            R.string.setting_nodes -> updateTable { it.copy(nodes = value) }
                            R.string.setting_weeks -> updateTable { it.copy(maxWeek = value) }
                        }
                    }
                }
            }
        }
    }

    /** 长按可恢复的当前课表设置项时，先展示居中确认弹窗。 */
    private fun requestRestoreDefault(item: SettingsItem): Boolean {
        val supported = item.name in setOf(
            R.string.setting_current_week,
            R.string.setting_nodes,
            R.string.setting_weeks,
            R.string.setting_show_sat,
            R.string.setting_show_sun,
            R.string.setting_item_show_time,
            R.string.setting_item_show_teacher,
            R.string.setting_item_show_location,
            R.string.setting_show_other_week,
        )
        if (!supported) return false
        SettingsRestoreDialog.show(this, item.name) { restoreDefault(item) }
        return true
    }

    /** 将单个当前课表字段恢复为 [TableEntity] 的构造默认值。 */
    private fun restoreDefault(item: SettingsItem) {
        updateTable { current ->
            when (item.name) {
                R.string.setting_current_week -> current.copy(
                    currentWeekOverride = AppDefaults.Table.CURRENT_WEEK_OVERRIDE,
                )

                R.string.setting_nodes -> current.copy(nodes = AppDefaults.Table.NODES)
                R.string.setting_weeks -> current.copy(maxWeek = AppDefaults.Table.MAX_WEEK)
                R.string.setting_show_sat -> current.copy(showSat = AppDefaults.Table.SHOW_SATURDAY)
                R.string.setting_show_sun -> current.copy(showSun = AppDefaults.Table.SHOW_SUNDAY)
                R.string.setting_item_show_time -> current.copy(showTime = AppDefaults.Table.SHOW_TIME)
                R.string.setting_item_show_teacher -> current.copy(showTeacher = AppDefaults.Table.SHOW_TEACHER)
                R.string.setting_item_show_location -> current.copy(showLocation = AppDefaults.Table.SHOW_LOCATION)
                R.string.setting_show_other_week -> current.copy(
                    showOtherWeekCourse = AppDefaults.Table.SHOW_OTHER_WEEK_COURSE,
                )

                else -> current
            }
        }
    }

    private fun showRenameDialog(current: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        editText.setText(current)
        editText.setSelection(current.length)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_schedule_name)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = editText.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) updateTable { it.copy(tableName = name) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDatePicker() {
        val t = table ?: return
        val current = runCatching { LocalDate.parse(t.startDate) }.getOrElse { return }
        val builder = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.setting_term_start_date)
        builder.setSelection(
            current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        builder.build().apply {
            addOnPositiveButtonClickListener { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                updateTable {
                    it.copy(
                        startDate = date.toString(),
                        currentWeekOverride = AppDefaults.Table.CURRENT_WEEK_OVERRIDE,
                    )
                }
                Snackbar.make(binding.root, R.string.term_date_pick_hint, Snackbar.LENGTH_SHORT)
                    .show()
            }
            show(supportFragmentManager, "date")
        }
    }

    /** NumberItem 弹窗：dialog_edit_text + 范围 helper + 前缀/后缀 + 数字键盘 */
    private fun showNumberDialog(item: NumberItem, onSet: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        inputLayout.helperText = getString(R.string.range_format, item.min, item.max)
        if (item.prefix.isNotEmpty()) inputLayout.prefixText = item.prefix
        inputLayout.suffixText = item.unit
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        val clamped = item.value.coerceIn(item.min, item.max)
        editText.setText(clamped.toString())
        editText.setSelection(clamped.toString().length)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = editText.text?.toString()?.toIntOrNull() ?: return@setOnClickListener
            onSet(value.coerceIn(item.min, item.max))
            dialog.dismiss()
        }
    }

    /** 将此课表配置用作默认配置（新建课表时套用） */
    private fun showApplyDefaultDialog() {
        val t = table ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_tips)
            .setMessage(R.string.msg_apply_default_config)
            .setPositiveButton(R.string.ok) { _, _ ->
                getSharedPreferences(PREF_CONFIG, MODE_PRIVATE)
                    .edit().putLong(PREF_DEFAULT_CONFIG_ID, t.id).apply()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_TABLE_ID = "table_id"
        const val PREF_CONFIG = "config"
        const val PREF_DEFAULT_CONFIG_ID = "default_config_id"
    }
}
