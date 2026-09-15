package com.openwakeup.schedule.feature.settings.schedule

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityScheduleShiftBinding
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单张课表的日期调课页。
 *
 * 新增调课只写入独立记录，不破坏课程的循环周次数据；主页渲染时应用记录，点击“撤销”删除
 * 对应记录即可恢复原日期课程。
 */
class ScheduleShiftActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityScheduleShiftBinding
    private var tableId: Long = -1L
    private var fromDate: LocalDate = LocalDate.now()
    private var toDate: LocalDate = LocalDate.now().plusDays(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScheduleShiftBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsAppearance.applyCards(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        tableId = intent.getLongExtra(EXTRA_TABLE_ID, -1L)
        if (tableId <= 0L) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnFromDate.setOnClickListener {
            pickDate(fromDate) {
                fromDate = it; renderDates()
            }
        }
        binding.btnToDate.setOnClickListener { pickDate(toDate) { toDate = it; renderDates() } }
        binding.btnAddShift.setOnClickListener { addShift() }
        renderDates()
        observeRecords()
    }

    /** 显示当前选择的原日期和目标日期。 */
    private fun renderDates() {
        val pattern = Prefs.get(this).dateFormat
        binding.btnFromDate.text =
            getString(R.string.shift_from_format, AppDateFormatter.formatFull(fromDate, pattern))
        binding.btnToDate.text =
            getString(R.string.shift_to_format, AppDateFormatter.formatFull(toDate, pattern))
    }

    /** 使用 Material 日期选择器更新指定日期。 */
    private fun pickDate(current: LocalDate, onPicked: (LocalDate) -> Unit) {
        MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.setting_modify_schedule_by_date)
            .setSelection(current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .build()
            .apply {
                addOnPositiveButtonClickListener { millis ->
                    onPicked(
                        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    )
                }
                show(supportFragmentManager, "shift_date")
            }
    }

    /** 校验并新增一条调课记录。 */
    private fun addShift() {
        if (fromDate == toDate) {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                R.string.shift_same_date_error,
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
            ).show()
            return
        }
        lifecycleScope.launch {
            repo.addScheduleShift(tableId, fromDate.toString(), toDate.toString())
        }
    }

    /** 持续观察记录，新增和撤销后无需手动刷新。 */
    private fun observeRecords() {
        lifecycleScope.launch {
            repo.scheduleShifts(tableId).collect { records -> renderRecords(records) }
        }
    }

    /**
     * 用 Expressive 分段卡渲染调课记录列表。
     *
     * 每行使用专用布局明确划分日期和撤销按钮的宽度，避免动态追加按钮时与日期文本重叠；
     * 撤销按钮固定在行尾，并由 Material 3 Expressive Filled Tonal 图标按钮提供视觉和状态反馈。
     */
    private fun renderRecords(records: List<ScheduleShiftEntity>) {
        binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.llRecords.removeAllViews()
        val pattern = Prefs.get(this).dateFormat
        records.forEachIndexed { index, record ->
            val row = layoutInflater.inflate(
                R.layout.item_schedule_shift_record,
                binding.llRecords,
                false
            )
                    as com.google.android.material.listitem.ListItemLayout
            val card = row.findViewById<com.google.android.material.listitem.ListItemCardView>(
                R.id.shift_record_card,
            )
            val title = row.findViewById<com.google.android.material.textview.MaterialTextView>(
                R.id.shift_record_title,
            )
            // 记录展示走全局日期格式；数据库内仍保存 ISO 值。
            title.text = getString(
                R.string.shift_record_format,
                AppDateFormatter.formatFull(LocalDate.parse(record.fromDate), pattern),
                AppDateFormatter.formatFull(LocalDate.parse(record.toDate), pattern),
            )
            SettingsAppearance.applySegmentedCard(
                layout = row,
                card = card,
                index = index,
                count = records.size,
            )
            row.findViewById<View>(R.id.btn_undo_shift).setOnClickListener {
                lifecycleScope.launch { repo.undoScheduleShift(record.id) }
            }
            binding.llRecords.addView(row)
        }
    }

    companion object {
        /** Intent 中传递所属课表 id 的参数名。 */
        const val EXTRA_TABLE_ID = "table_id"
    }
}
