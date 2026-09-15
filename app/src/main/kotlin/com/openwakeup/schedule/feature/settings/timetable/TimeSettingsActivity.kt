package com.openwakeup.schedule.feature.settings.timetable

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.database.entity.TimeTableEntity
import com.openwakeup.schedule.core.designsystem.component.CascadeMenu
import com.openwakeup.schedule.core.util.ActivityTransitionCompat
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityTimeSettingsBinding
import com.openwakeup.schedule.feature.settings.SettingsAppearance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 时间表列表与管理页。
 *
 * 页面负责当前课表绑定、新建时间表以及“编辑/复制/重命名/删除”管理操作；具体节次编辑移动到
 * [TimeTableDetailActivity]，点击时间表后使用淡入淡出动画进入详情页。
 */
class TimeSettingsActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityTimeSettingsBinding
    private var table: com.openwakeup.schedule.core.database.entity.TableEntity? = null
    private var boundTimeTableId: Long = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityTimeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsAppearance.applyCards(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.cardBind.setOnClickListener {
            if (table == null) {
                Toast.makeText(this, R.string.no_current_table, Toast.LENGTH_SHORT).show()
            } else {
                showBindDialog()
            }
        }
        binding.fabAdd.setOnClickListener {
            showNameDialog(null) { name ->
                lifecycleScope.launch {
                    val times =
                        repo.timeDetailsOnce(boundTimeTableId).map { it.startTime to it.endTime }
                    repo.createTimeTable(name, times)
                    renderTimeTables()
                }
            }
        }
        observeCurrentTable()
    }

    override fun onResume() {
        super.onResume()
        // 从详情页或重命名弹窗返回后，立即刷新列表名称和状态。
        if (::binding.isInitialized) renderTimeTables()
    }

    /** 展示当前课表绑定的时间表选择弹窗，并用单选点标识当前绑定项。 */
    private fun showBindDialog() {
        if (table == null) {
            Toast.makeText(this, R.string.no_current_table, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val timeTables = repo.timeTables().first()
            val names = timeTables.map { it.name }.toTypedArray()
            val checkedItem = timeTables.indexOfFirst { timeTable ->
                timeTable.id == boundTimeTableId
            }
            MaterialAlertDialogBuilder(this@TimeSettingsActivity)
                .setTitle(R.string.current_table_bind)
                .setSingleChoiceItems(names, checkedItem) { dialog, which ->
                    dialog.dismiss()
                    lifecycleScope.launch {
                        table?.let { current ->
                            repo.updateTable(current.copy(timeTableId = timeTables[which].id))
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 观察当前课表，并同步顶部绑定信息。 */
    private fun observeCurrentTable() {
        lifecycleScope.launch {
            repo.currentTable.collect { current ->
                table = current
                boundTimeTableId = current?.timeTableId ?: 1L
                if (current == null) {
                    // 第一设置组必须明确表达“没有当前课表”，不能再显示空课表名或默认绑定。
                    binding.tvCurrentTable.setText(R.string.no_current_table)
                    binding.tvBind.setText(R.string.no_current_table)
                } else {
                    binding.tvCurrentTable.text =
                        getString(R.string.current_table_is, current.tableName)
                    val bound = repo.timeTableOnce(boundTimeTableId)
                    binding.tvBind.text = bound?.name ?: getString(R.string.default_time_table)
                }
                renderTimeTables()
            }
        }
    }

    /**
     * 用 Expressive 分段列表渲染时间表：每行 = ListItemLayout + ListItemCardView
     * （segmented 容器色与设置页一致），行内 schedule 图标 + 名称 + 行内更多按钮。
     * 整行点击进入详情，圆角按 first/middle/last 由组件 updateAppearance 机制驱动。
     */
    private fun renderTimeTables() {
        lifecycleScope.launch {
            val timeTables = repo.timeTables().first()
            binding.llTimeTables.removeAllViews()
            val inflater = layoutInflater
            timeTables.forEachIndexed { index, timeTable ->
                val row = inflater.inflate(R.layout.item_timetable_row, binding.llTimeTables, false)
                        as com.google.android.material.listitem.ListItemLayout
                val card =
                    row.findViewById<com.google.android.material.listitem.ListItemCardView>(R.id.setting_card)
                val name =
                    row.findViewById<com.google.android.material.textview.MaterialTextView>(R.id.setting_title)
                val menu =
                    row.findViewById<androidx.appcompat.widget.AppCompatImageView>(R.id.timetable_more)
                name.text = timeTable.name
                row.findViewById<android.widget.ImageView>(R.id.setting_icon)
                    .setImageResource(R.drawable.ms_schedule_24)
                // 组件状态机：single/first/middle/last 驱动行尾箭头以外的容器状态
                row.updateAppearance(
                    when {
                        timeTables.size == 1 -> com.google.android.material.listitem.ListItemLayout.POSITION_SINGLE
                        index == 0 -> com.google.android.material.listitem.ListItemLayout.POSITION_FIRST
                        index == timeTables.lastIndex -> com.google.android.material.listitem.ListItemLayout.POSITION_LAST
                        else -> com.google.android.material.listitem.ListItemLayout.POSITION_MIDDLE
                    },
                )
                // 容器色与设置页分段卡保持一致（Expressive state-list 在旧主题下不生效）
                card.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@TimeSettingsActivity,
                        R.color.setting_card_container
                    ),
                )
                val radius = resources.displayMetrics.density * 16f
                val shapeBuilder = com.google.android.material.shape.ShapeAppearanceModel.builder()
                when {
                    timeTables.size == 1 -> shapeBuilder.setAllCornerSizes(radius)
                    index == 0 -> shapeBuilder.setTopLeftCornerSize(radius)
                        .setTopRightCornerSize(radius)

                    index == timeTables.lastIndex -> shapeBuilder.setBottomLeftCornerSize(radius)
                        .setBottomRightCornerSize(radius)

                    else -> shapeBuilder.setAllCornerSizes(0f)
                }
                card.shapeAppearanceModel = shapeBuilder.build()
                row.setOnClickListener { openTimeTableDetail(timeTable.id) }
                menu.setOnClickListener { anchor -> showRowMenu(anchor, timeTable) }
                binding.llTimeTables.addView(row)
            }
        }
    }

    /**
     * 淡入打开指定时间表的详情编辑页。
     *
     * @param timeTableId 要编辑的时间表主键
     */
    private fun openTimeTableDetail(timeTableId: Long) {
        startActivity(
            Intent(this, TimeTableDetailActivity::class.java)
                .putExtra(TimeTableDetailActivity.EXTRA_TIME_TABLE_ID, timeTableId),
        )
        ActivityTransitionCompat.applyOpen(
            this,
            R.anim.activity_fade_anim_in,
            R.anim.activity_fade_anim_out,
        )
    }

    /** 展示“编辑、复制、重命名、删除”四级管理菜单。 */
    private fun showRowMenu(anchor: View, timeTable: TimeTableEntity) {
        CascadeMenu(anchor, 0, Gravity.END).apply {
            item(getString(R.string.menu_edit), R.drawable.ms_edit_24) {
                openTimeTableDetail(timeTable.id)
            }
            item(getString(R.string.menu_copy), R.drawable.ms_content_copy_24) {
                lifecycleScope.launch {
                    val times =
                        repo.timeDetailsOnce(timeTable.id).map { it.startTime to it.endTime }
                    repo.createTimeTable(
                        getString(R.string.time_table_copy_name, timeTable.name),
                        times
                    )
                    renderTimeTables()
                }
            }
            item(getString(R.string.rename), R.drawable.ms_edit_24) {
                showNameDialog(timeTable) { name ->
                    lifecycleScope.launch {
                        repo.updateTimeTable(timeTable.copy(name = name))
                        renderTimeTables()
                    }
                }
            }
            // 删除前二次确认；仓库层会一并清掉节次时间并把绑定的课表改绑到剩余作息表。
            item(getString(R.string.delete), R.drawable.ms_delete_24) {
                requestDeleteTimeTable(timeTable)
            }
        }.show()
    }

    /**
     * 校验剩余数量后请求删除时间表。
     *
     * 仅剩一张时直接在屏幕中央显示 Material 提示弹窗；数量充足时才展示二次确认，真正
     * 删除前仓库层还会再次校验，以防并发操作造成列表数量变化。
     *
     * @param timeTable 用户当前选择的时间表
     */
    private fun requestDeleteTimeTable(timeTable: TimeTableEntity) {
        lifecycleScope.launch {
            val tables = repo.timeTables().first()
            if (tables.size <= 1) {
                MaterialAlertDialogBuilder(this@TimeSettingsActivity)
                    .setTitle(R.string.title_tips)
                    .setMessage(R.string.time_table_at_least_one)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@TimeSettingsActivity)
                .setTitle(R.string.title_tips)
                .setMessage(getString(R.string.confirm_delete_time_table, timeTable.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch {
                        if (!repo.deleteTimeTable(timeTable.id)) {
                            // 二次确认期间若其他入口改变了数量，仍以仓库最终校验结果为准。
                            MaterialAlertDialogBuilder(this@TimeSettingsActivity)
                                .setTitle(R.string.title_tips)
                                .setMessage(R.string.time_table_at_least_one)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                        renderTimeTables()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 展示时间表名称输入弹窗。
     *
     * @param initial 重命名时的原时间表；新建时传入 `null`
     * @param onOk 名称校验通过后的回调
     */
    private fun showNameDialog(initial: TimeTableEntity?, onOk: (String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        editText.setText(initial?.name ?: "")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_class_time_table)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editText.text?.toString()?.trim()
            if (!name.isNullOrEmpty()) {
                onOk(name)
                dialog.dismiss()
            }
        }
    }
}
