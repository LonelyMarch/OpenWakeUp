package com.openwakeup.schedule.feature.schedulemanage

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.databinding.ActivityCourseManageBinding
import com.openwakeup.schedule.databinding.ActivityMultiTableManageBinding
import com.openwakeup.schedule.databinding.ItemTableListBinding
import com.openwakeup.schedule.feature.importexport.BackupExportActivity
import com.openwakeup.schedule.feature.settings.schedule.ScheduleSettingsActivity
import kotlinx.coroutines.launch

/**
 * 多课表管理：工具栏 + 两条提示 + 课表卡片列表 + FAB。
 * 卡片=item_table_list（背景图区+名称+✏️编辑+🗑删除）；点卡片切换到该课表；FAB 新建课表。
 * 长按卡片可纵向拖动排序，写回 tableOrder（与底部浮窗横向排序共用同一顺序）。
 */
class ScheduleManageActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var binding: ActivityMultiTableManageBinding
    private var manageAdapter: ManageAdapter? = null

    companion object {
        /** 设置页"管理已添加课程"入口：携带即进入课程管理目的地 */
        const val EXTRA_SELECTED_TABLE_ID = "selectedTableId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (intent.hasExtra(EXTRA_SELECTED_TABLE_ID)) {
            // 带 selectedTableId → 课程管理目的地（标题"课程管理" + 清空 + 课程卡网格）
            setContentViewForCourseManage()
            return
        }
        binding = ActivityMultiTableManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.fabAdd.setOnClickListener { showCreateScheduleDialog() }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        // adapter 与拖拽 helper 只装一次：每次 tables() 发射就重建会打断进行中的拖拽，
        // 并把 ItemTouchHelper 反复叠在同一 RecyclerView 上。
        val adapter = ManageAdapter()
        manageAdapter = adapter
        binding.rvList.adapter = adapter
        attachDragAndDrop()
        observe()
    }

    /** 课程管理模式：activity_course_manage + CourseManageFragment */
    private fun setContentViewForCourseManage() {
        val bindingCourse = ActivityCourseManageBinding.inflate(layoutInflater)
        setContentView(bindingCourse.root)
        ViewCompat.setOnApplyWindowInsetsListener(bindingCourse.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        bindingCourse.btnBack.setOnClickListener { finish() }
        val fragment = CourseManageFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        bindingCourse.tvClear.setOnClickListener { fragment.clearCourses() }
    }

    private fun observe() {
        lifecycleScope.launch {
            repo.tables().collect { tables ->
                val isEmpty = tables.isEmpty()
                binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvList.visibility = if (isEmpty) View.GONE else View.VISIBLE
                manageAdapter?.submit(tables)
            }
        }
    }

    /** 长按纵向拖拽排序（与底部浮窗共用 tableOrder 持久化） */
    private fun attachDragAndDrop() {
        val callback = object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder,
            ): Boolean {
                val adapter = rv.adapter as? ManageAdapter ?: return false
                return adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    manageAdapter?.setDragging(true)
                    // 抬起手感：拖拽中的卡片浮起
                    vh?.itemView?.isPressed = true
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.isPressed = false
                manageAdapter?.setDragging(false)
                (rv.adapter as? ManageAdapter)?.persistOrder()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvList)
    }

    private fun showCreateScheduleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout =
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_schedule_name)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editText.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                inputLayout.error = getString(R.string.schedule_name_cant_empty)
            } else {
                lifecycleScope.launch {
                    repo.createTable(name, java.time.LocalDate.now().toString(), 20)
                    dialog.dismiss()
                }
            }
        }
    }

    private inner class ManageAdapter : RecyclerView.Adapter<ManageHolder>() {

        private val tables = mutableListOf<TableEntity>()

        /** 拖拽中不接受列表刷新，否则手指下的卡片会被抽走 */
        private var dragging = false

        fun submit(list: List<TableEntity>) {
            if (dragging) return
            tables.clear()
            tables.addAll(list)
            notifyDataSetChanged()
        }

        fun setDragging(value: Boolean) {
            dragging = value
        }

        /** 拖拽换位（不落库，clearView 时统一持久化） */
        fun move(from: Int, to: Int): Boolean {
            if (from == to || from !in tables.indices || to !in tables.indices) return false
            tables.add(to, tables.removeAt(from))
            notifyItemMoved(from, to)
            return true
        }

        /** 写回 tableOrder（两列表共用同一持久化顺序） */
        fun persistOrder() {
            lifecycleScope.launch { repo.reorderTables(tables.toList()) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManageHolder =
            ManageHolder(ItemTableListBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = tables.size

        override fun onBindViewHolder(holder: ManageHolder, position: Int) {
            val t = tables[position]
            val b = holder.binding
            if (t.background.startsWith("#")) {
                try {
                    b.ivPic.setBackgroundColor(Color.parseColor(t.background))
                } catch (_: IllegalArgumentException) {
                    b.ivPic.setBackgroundResource(R.drawable.main_gradient_background)
                }
            } else {
                b.ivPic.setBackgroundResource(R.drawable.main_gradient_background)
            }
            b.tvTableName.text = t.tableName
            b.cvTable.setOnClickListener {
                lifecycleScope.launch {
                    repo.switchTable(t.id)
                    finish()
                }
            }
            b.ibEdit.setOnClickListener {
                // 编辑符号 → 该课表的设置页
                startActivity(
                    Intent(this@ScheduleManageActivity, ScheduleSettingsActivity::class.java)
                        .putExtra(ScheduleSettingsActivity.EXTRA_TABLE_ID, t.id)
                )
            }
            b.ibDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this@ScheduleManageActivity)
                    .setTitle(R.string.confirm_delete_table)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        lifecycleScope.launch { repo.deleteTable(t.id) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            b.ibShare.setOnClickListener {
                // 课表管理页的行内导出保持“只导出此课表”语义；新页面会自动补选关联时间表。
                startActivity(
                    Intent(this@ScheduleManageActivity, BackupExportActivity::class.java)
                        .putExtra(BackupExportActivity.EXTRA_PRESELECTED_SCHEDULE_ID, t.id),
                )
            }
        }
    }

    private class ManageHolder(val binding: ItemTableListBinding) :
        RecyclerView.ViewHolder(binding.root)
}
