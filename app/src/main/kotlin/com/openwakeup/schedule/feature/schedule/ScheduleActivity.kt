package com.openwakeup.schedule.feature.schedule

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.config.AppDefaults
import com.openwakeup.schedule.core.data.Prefs
import com.openwakeup.schedule.core.database.entity.CourseDetailEntity
import com.openwakeup.schedule.core.database.entity.CourseEntity
import com.openwakeup.schedule.core.database.entity.ScheduleShiftEntity
import com.openwakeup.schedule.core.database.entity.TableEntity
import com.openwakeup.schedule.core.database.entity.TimeDetailEntity
import com.openwakeup.schedule.core.designsystem.component.CascadeMenu
import com.openwakeup.schedule.core.format.AppDateFormatter
import com.openwakeup.schedule.core.util.DateUtils
import com.openwakeup.schedule.core.validation.CourseRangePolicy
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.feature.about.AboutActivity
import com.openwakeup.schedule.feature.clock.ClockActivity
import com.openwakeup.schedule.feature.courseedit.AddCourseActivity
import com.openwakeup.schedule.feature.importexport.BackupExportActivity
import com.openwakeup.schedule.feature.importexport.BackupImportActivity
import com.openwakeup.schedule.feature.importexport.CourseImportPolicy
import com.openwakeup.schedule.feature.importexport.CourseImportRangeReport
import com.openwakeup.schedule.feature.importexport.CsvImportActivity
import com.openwakeup.schedule.feature.importexport.HtmlImportActivity
import com.openwakeup.schedule.feature.importexport.IcsExporter
import com.openwakeup.schedule.feature.importexport.IcsImportActivity
import com.openwakeup.schedule.feature.importexport.SchoolListActivity
import com.openwakeup.schedule.feature.schedulemanage.ScheduleManageActivity
import com.openwakeup.schedule.feature.settings.global.SettingsActivity
import com.openwakeup.schedule.feature.settings.schedule.ScheduleSettingsActivity
import com.openwakeup.schedule.feature.settings.timetable.TimeSettingsActivity
import com.openwakeup.schedule.feature.settings.widget.WidgetSettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 主课表界面：
 * 视图树由 [ScheduleActivityUI] 代码构建；交互：
 * tv_week→锚定菜单（设置当前周/回到本周/新建课表）、ib_add→加课、ib_import→导入菜单（含子菜单）、
 * ib_share→导出菜单、ib_more→展开底部菜单（BACK 收起）、宫格跳转、周次滑条换周、课表切换。
 */
class ScheduleActivity : AppCompatActivity() {

    private val repo by lazy { ScheduleRepository(this) }
    private lateinit var ui: ScheduleActivityUI

    private var table: TableEntity? = null
    private var displayWeek = 1
    private var currentWeek = 0
    private var courses: List<CourseEntity> = emptyList()
    private var details: List<CourseDetailEntity> = emptyList()
    private var times: List<TimeDetailEntity> = emptyList()
    private var shifts: List<ScheduleShiftEntity> = emptyList()
    private var tableAdapter: TableNameAdapter? = null

    private val addCourseLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) refreshPage()
        }

    /** 导出 iCal（text/calendar CREATE_DOCUMENT；每条时间段生成周重复 VEVENT） */
    private val exportICalLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            val t = table ?: return@registerForActivityResult
            if (uri != null) {
                lifecycleScope.launch {
                    runCatching {
                        val text = buildICal(t)
                        contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        }
                    }.onFailure {
                        android.widget.Toast.makeText(
                            this@ScheduleActivity,
                            R.string.export_tips,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    /**
     * 读取当前课表的完整课程与作息快照，并构建标准 ICS 文本。
     *
     * 导出时重新查询数据库而不依赖页面异步加载中的 [times] 缓存，确保用户刚修改作息后
     * 立即导出也能获得最新时间；协议字段、文本转义与内容行折叠统一由 [IcsExporter] 负责。
     */
    private suspend fun buildICal(t: TableEntity): String = IcsExporter.build(
        table = t,
        courses = repo.coursesOnce(t.id),
        details = repo.detailsOnce(t.id),
        timeDetails = repo.timeDetailsOnce(t.timeTableId),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 13 及以上统一使用 edge-to-edge；导航栏保持透明并沿用深色图标。
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ui = ScheduleActivityUI(this)
        applyWindowInsets()
        ui.rootLayout.post {
            showPersistentNavigationBar()
            // 主题切换会恢复 BottomSheet 状态，但新建遮罩仍默认为 GONE；首帧主动补齐两者状态。
            syncBottomSheetScrim()
        }

        // BACK：底部菜单展开时先收起
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (ui.behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                        ui.behavior.state = BottomSheetBehavior.STATE_HIDDEN
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })

        setupToolbar()
        setupBottomSheet()
        setupPager()
        observeData()
        consumeImportResult(intent)
    }

    /**
     * 接收 `singleTask` 主课表被再次启动时携带的新导入结果。
     *
     * @param intent 网页导入页返回主课表时创建的新 Intent
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeImportResult(intent)
    }

    /**
     * 在主课表视图上展示网页导入的越界课程警告，并确保同一个 Intent 只被消费一次。
     *
     * 成功消息已经在网页页完成淡入、停留和淡出，这里仅接收一次性的 `Serializable` 范围报告。
     * 报告数据量只包含异常课程摘要，适合 Activity 间的短距离结果传递。先移除额外参数再投递
     * UI，避免配置变化重建页面后重复提示。
     *
     * @param sourceIntent 可能携带网页导入结果的启动 Intent
     */
    private fun consumeImportResult(sourceIntent: Intent) {
        if (!sourceIntent.hasExtra(EXTRA_IMPORT_RANGE_REPORT)) return
        val rangeReport = sourceIntent.getSerializableExtra(
            EXTRA_IMPORT_RANGE_REPORT,
            CourseImportRangeReport::class.java,
        )
        sourceIntent.removeExtra(EXTRA_IMPORT_RANGE_REPORT)

        // 等主课表完成本轮布局后再展示警告，保证弹窗的窗口令牌已经就绪。
        ui.rootLayout.post {
            rangeReport?.let { report ->
                CourseImportPolicy.showInvalidCourseDialog(this, report)
            }
        }
    }

    /**
     * View 层级完成状态恢复后，再次同步浮窗遮罩。
     *
     * BottomSheetBehavior 的保存状态由 CoordinatorLayout 在 `super.onRestoreInstanceState` 中恢复，
     * 必须在其后读取 state，才能覆盖主题切换后“浮窗已展开但遮罩仍为 GONE”的时间窗口。
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (::ui.isInitialized) {
            ui.rootLayout.post { syncBottomSheetScrim() }
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(ui.rootLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val stableNavigationBars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars(),
            )
            val compatibilityClearance =
                (BOTTOM_SHEET_NAVIGATION_CLEARANCE_DP * resources.displayMetrics.density).toInt()
            // 使用公开 Insets 作为真实导航栏高度，并额外上移 32dp 兼容厂商三键导航触控区域。
            val navigationSafeHeight = stableNavigationBars.bottom + compatibilityClearance
            // 根内容不增加顶部 padding，让课表背景完整绘制到透明状态栏背后；
            // 只下移标题区，避免周次文字与状态栏图标重叠。
            val weekParams = ui.tvWeek.layoutParams as ConstraintLayout.LayoutParams
            if (weekParams.topMargin != statusBars.top) {
                weekParams.topMargin = statusBars.top
                ui.tvWeek.layoutParams = weekParams
            }
            // BottomSheetBehavior 会自行定位外层容器，普通 bottomMargin 在展开态可能被覆盖。
            // 因此保持外层与屏幕等高，再由底对齐容器的内部安全区把全部卡片托到导航栏上方。
            ui.bottomSheet.updatePadding(bottom = navigationSafeHeight)
            // 无参构造的 BottomSheetBehavior 里 paddingTopSystemWindowInsets=false：它在第一次
            // onLayoutChild 之后才注册内部 insets 监听并记录 insetTop（状态栏高度），展开偏移
            // fitToContentsOffset=insetTop 依赖随后的重新布局才能算出。冷启动浮窗先停在 COLLAPSED，
            // 该重布局由 updatePeekHeight 自动触发；而主题切换重建把浮窗直接恢复为 EXPANDED，
            // updatePeekHeight 对展开态不触发任何布局，展开偏移会永久停在 0，整个浮窗比正常抬高
            // 一个状态栏高度。padding 变化本身走 setPadding 会合并进同一帧布局，无法覆盖第二次
            // 派发；因此每次 insets 派发都显式要求重布局，让 onLayoutChild 用最新 insetTop 重算。
            ui.bottomSheet.requestLayout()
            insets
        }
    }

    private fun setupToolbar() {
        ui.tvWeek.setOnClickListener {
            val menu = CascadeMenu(it, 0, Gravity.START)
            menu.item(getString(R.string.main_modify_current_week), R.drawable.ms_event_repeat_24) {
                openScheduleSettingsIfAvailable()
            }
            if (currentWeek in 1..(table?.maxWeek
                    ?: 0) && ui.viewPager.currentItem != currentWeek - 1
            ) {
                menu.item(getString(R.string.main_back_to_current_week), R.drawable.ms_today_24) {
                    displayWeek = currentWeek
                    ui.viewPager.setCurrentItem(currentWeek - 1, false)
                    ui.sliderWeek.value = displayWeek.toFloat()
                    refreshHeaders()
                }
            }
            menu.item(
                getString(R.string.main_create_new_schedule),
                R.drawable.ms_add_24
            ) { showCreateScheduleDialog() }
            menu.show()
        }
        ui.ibAdd.setOnClickListener {
            val currentTable = table
            if (currentTable == null) {
                showNoCurrentTableToast()
                return@setOnClickListener
            }
            addCourseLauncher.launch(
                Intent(this@ScheduleActivity, AddCourseActivity::class.java)
                    .putExtra(AddCourseActivity.EXTRA_TABLE_ID, currentTable.id),
            )
        }
        ui.ibMore.setOnClickListener {
            ui.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /** 导入课表菜单（原顶栏 ib_import，已移入底部浮窗宫格） */
    private fun showImportMenu(view: View) {
        val width = (196 * resources.displayMetrics.density).toInt()
        CascadeMenu(view, width, Gravity.START).apply {
            // 五个导入入口按通用文件格式、外部数据源、OpenWakeUp 专用备份的顺序展示。
            item(getString(R.string.import_dialog_from_excel), R.drawable.ms_table_rows_24) {
                startActivity(Intent(this@ScheduleActivity, CsvImportActivity::class.java))
            }
            item(getString(R.string.import_dialog_from_ics), R.drawable.ms_calendar_month_24) {
                startActivity(Intent(this@ScheduleActivity, IcsImportActivity::class.java))
            }
            item(getString(R.string.import_dialog_from_html), R.drawable.ms_code_24) {
                startActivity(Intent(this@ScheduleActivity, HtmlImportActivity::class.java))
            }
            item(getString(R.string.import_dialog_from_eas), R.drawable.ms_school_24) {
                startActivity(Intent(this@ScheduleActivity, SchoolListActivity::class.java))
            }
            item(getString(R.string.import_dialog_from_backup), R.drawable.ms_dataset_24) {
                startActivity(Intent(this@ScheduleActivity, BackupImportActivity::class.java))
            }
        }.showCentered()
    }

    /** 导出数据菜单（原顶栏 ib_share，已移入底部浮窗宫格） */
    private fun showExportMenu(view: View) {
        val width = (260 * resources.displayMetrics.density).toInt()
        CascadeMenu(view, width, Gravity.END).apply {
            item(getString(R.string.export_as_backup), R.drawable.ms_dataset_24) {
                startActivity(Intent(this@ScheduleActivity, BackupExportActivity::class.java))
            }
            item(getString(R.string.export_as_ical_file), R.drawable.ms_today_24) {
                exportICalLauncher.launch((table?.tableName ?: "unnamed") + ".ics")
            }
            // 图标与菜单保持既有语义和视觉风格。
            item(getString(R.string.share_schedule_online), R.drawable.ms_share_24) {
                toastPending()
            }
        }.showCentered()
    }

    private fun toastPending() {
        android.widget.Toast.makeText(
            this,
            R.string.feature_pending_server,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupBottomSheet() {
        // 课表列表 adapter 与拖拽 helper 只装一次：
        // 每次 tables() 发射就重建会清掉选中态，并把 ItemTouchHelper 叠在同一 RecyclerView 上。
        val adapter = TableNameAdapter { picked ->
            lifecycleScope.launch { repo.switchTable(picked.id) }
        }
        tableAdapter = adapter
        ui.rvTable.adapter = adapter
        attachTableDragAndDrop()

        ui.btnChange.setOnClickListener { openScheduleSettingsIfAvailable() }
        ui.btnCreate.setOnClickListener { showCreateScheduleDialog() }
        ui.btnManage.setOnClickListener {
            startActivity(Intent(this, ScheduleManageActivity::class.java))
        }
        ui.navTime.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    TimeSettingsActivity::class.java
                )
            )
        }
        ui.navScheduleSetting.setOnClickListener { openScheduleSettingsIfAvailable() }
        ui.navCourse.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    WidgetSettingsActivity::class.java
                )
            )
        }
        ui.navImport.setOnClickListener { view -> showImportMenu(view) }
        ui.navAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        ui.navExport.setOnClickListener { view -> showExportMenu(view) }
        ui.navSettings.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        }
        ui.navSuda.setOnClickListener { startActivity(Intent(this, ClockActivity::class.java)) }

        // 浮窗展开时点外部收起（BottomSheetBehavior 自身不带 outside-touch 行为）
        ui.sheetScrim.setOnClickListener {
            ui.behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }
        ui.behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                syncBottomSheetScrim(newState)
                // 浮窗显隐不再改变系统导航栏状态，避免 Insets 变化导致浮窗上下漂移。
                showPersistentNavigationBar()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // 透明遮罩仅在浮窗可见时拦截外部点击，不改变主页明暗。
                val progress = slideOffset.coerceIn(0f, 1f)
                if (progress > 0f) ui.sheetScrim.visibility = View.VISIBLE
            }
        })

        ui.sliderWeek.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                displayWeek = value.toInt()
                ui.viewPager.setCurrentItem(displayWeek - 1, false)
                refreshHeaders()
            }
        }
    }

    /**
     * 依据 BottomSheet 当前状态同步外部触摸遮罩。
     *
     * Activity 因主题切换重建时，Behavior 可以直接恢复为展开态而不重新派发状态回调；
     * 因此该方法既供回调调用，也会在首帧和窗口重新获得焦点时主动执行，确保课表触摸不会穿透。
     *
     * @param state 待同步状态；默认读取 Behavior 当前状态
     */
    private fun syncBottomSheetScrim(state: Int = ui.behavior.state) {
        val showing = state != BottomSheetBehavior.STATE_HIDDEN &&
                state != BottomSheetBehavior.STATE_COLLAPSED
        ui.sheetScrim.visibility = if (showing) View.VISIBLE else View.GONE
    }

    /**
     * 底部浮窗展开时隐藏系统导航栏，收起后恢复。
     *
     * 导航栏可见时，[applyWindowInsets] 会把浮窗内容整体托到导航栏上方；隐藏后系统重新派发
     * 零高度 Insets，浮窗才可延伸到物理屏幕底部，因此两种导航模式都不会遮挡菜单内容。
     */
    private fun showPersistentNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, ui.rootLayout)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.isAppearanceLightNavigationBars = true
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    /** 从系统页面、文件选择器或其他 Activity 返回时，再次确认导航栏处于常驻状态。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::ui.isInitialized) {
            ui.rootLayout.post {
                showPersistentNavigationBar()
                syncBottomSheetScrim()
            }
        }
    }

    /** 新建课表对话框（dialog_edit_text + 非空校验） */
    private fun showCreateScheduleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
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
                    repo.createTable(name, LocalDate.now().toString(), 20)
                    dialog.dismiss()
                }
            }
        }
    }

    /**
     * 快速加课完成后打开添加课程页。
     *
     * 无课表状态仍会绘制一份只读意义上的空白周网格，方便用户浏览未来 48 周；因此快速添加
     * 手势可能生成 `tableId = 0` 的临时草稿。此处必须再次校验真实当前课表，防止把占位 id
     * 传给课程编辑页。
     *
     * @param draft 周网格生成的快速添加草稿
     */
    private fun launchQuickAdd(draft: com.openwakeup.schedule.feature.schedule.quickadd.QuickAddDraft) {
        if (table == null || draft.tableId <= 0L) {
            showNoCurrentTableToast()
            return
        }
        val intent = com.openwakeup.schedule.feature.schedule.quickadd.QuickAddIntentContract.apply(
            Intent(this, AddCourseActivity::class.java)
                .putExtra(AddCourseActivity.EXTRA_TABLE_ID, draft.tableId),
            draft,
        )
        addCourseLauncher.launch(intent)
    }

    private fun setupPager() {
        ui.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                displayWeek = position + 1
                if (ui.sliderWeek.value.toInt() != displayWeek) {
                    ui.sliderWeek.value = displayWeek.toFloat()
                }
                refreshHeaders()
                refreshPage()
            }
        })
    }

    private fun observeData() {
        // 当前表 + 该表课程/作息合为一条链：collectLatest 在切表时取消上一张表的课程收集，
        // 避免"内层 Room Flow 永不结束 → 外层 StateFlow 新值被合并丢弃 → 换表后仍是旧课程"。
        lifecycleScope.launch {
            repo.currentTable.collectLatest { t ->
                if (t == null) {
                    renderNoTableState()
                    return@collectLatest
                }
                val previous = table
                val tableChanged = previous?.id != t.id
                // 周数/列数变化需要重建 ViewPager；仅改颜色等外观时保留当前页，避免跳回本周
                val needRebuild = previous == null || tableChanged || previous.maxWeek != t.maxWeek
                table = t
                if (tableChanged) {
                    // 新表课程尚未到达，先清掉上一张表的数据，防止页面闪出别的表的课
                    courses = emptyList()
                    details = emptyList()
                    times = emptyList()
                    shifts = emptyList()
                }
                currentWeek =
                    DateUtils.currentWeek(LocalDate.parse(t.startDate)).coerceIn(1, t.maxWeek)
                displayWeek = if (needRebuild) {
                    (if (t.currentWeekOverride > 0) t.currentWeekOverride else currentWeek)
                        .coerceIn(1, t.maxWeek)
                } else {
                    displayWeek.coerceIn(1, t.maxWeek)
                }
                applyBackground(t.background)
                if (needRebuild) {
                    ui.viewPager.adapter = object : FragmentStateAdapter(this@ScheduleActivity) {
                        override fun getItemCount(): Int = t.maxWeek
                        override fun createFragment(position: Int): Fragment =
                            WeekPageFragment.newInstance(position + 1).also(::bindWeekPageCallbacks)
                    }
                    ui.viewPager.setCurrentItem(displayWeek - 1, false)
                }
                // 先落到 valueFrom 再改上界，避免旧 value 超出新 maxWeek 时 Slider 校验抛异常
                ui.sliderWeek.value = 1f
                ui.sliderWeek.valueFrom = 1f
                ui.sliderWeek.valueTo = t.maxWeek.toFloat()
                ui.sliderWeek.value = displayWeek.toFloat()
                refreshHeaders()
                tableAdapter?.setCurrentId(t.id)
                refreshPage()

                combine(
                    repo.coursesWithDetails(t.id),
                    repo.timeDetails(t.timeTableId),
                    repo.scheduleShifts(t.id),
                ) { cwds, times, shifts ->
                    Triple(
                        cwds,
                        times,
                        shifts
                    )
                }.collect { (cwds, times, shifts) ->
                    val visibleNodeLimit = CourseRangePolicy.visibleNodeLimit(t.nodes)
                    val visibleCourses = cwds.filter { courseWithDetails ->
                        CourseRangePolicy.isCourseValid(courseWithDetails.details, visibleNodeLimit)
                    }
                    // 非法课程保留在数据库和课程管理页，主课表只接收范围完整合法的课程。
                    courses = visibleCourses.map { courseWithDetails -> courseWithDetails.course }
                    details =
                        visibleCourses.flatMap { courseWithDetails -> courseWithDetails.details }
                    this@ScheduleActivity.times = times
                    this@ScheduleActivity.shifts = shifts
                    refreshPage()
                }
            }
        }
        lifecycleScope.launch {
            repo.tables().collect { tables -> tableAdapter?.submit(tables) }
        }
        // 全局日期格式变化时立即重绘主页日期行（WeekPageFragment 在 onResume 拉取最新格式）。
        lifecycleScope.launch {
            Prefs.get(this@ScheduleActivity).dateFormatFlow.collect { refreshHeaders() }
        }
    }

    private fun refreshHeaders() {
        // 主页日期使用全局日期格式，而不是系统 SHORT 格式。
        ui.tvDate.text = AppDateFormatter.formatFull(LocalDate.now(), Prefs.get(this).dateFormat)
        // 顶栏图标跟随课表界面文字色（素材不可 tint，图标需与素材文字保持同一对比度）
        table?.let {
            val color = ScheduleThemeColors.tableTextColor(this, it.textColor)
            ui.setHeaderContentColor(color)
        }
        ui.tvWeek.text = getString(R.string.week_num, displayWeek)
        val inSemester = table != null && displayWeek in 1..(table?.maxWeek ?: 0)
        ui.tvWeekday.text = when {
            // 无课表预览把本周定义为第 1 周，因此标题继续显示今天的星期，而不是“学期结束”。
            table == null -> AppDateFormatter.weekdayShort(LocalDate.now())
            inSemester -> AppDateFormatter.weekdayShort(LocalDate.now())
            else -> getString(R.string.semester_ended)
        }
    }

    private fun refreshPage() {
        val hasTable = table != null
        val t = table ?: createNoTablePreview()
        val snapshot = WeekPageFragment.Snapshot(
            table = t,
            times = if (hasTable) times else emptyList(),
            currentWeek = if (hasTable) currentWeek.coerceIn(0, t.maxWeek) else 1,
            startDate = LocalDate.parse(t.startDate),
            source = if (hasTable) {
                courses.flatMap { course ->
                    details.filter { detail -> detail.courseId == course.id }
                        .map { detail -> course to detail }
                }
            } else {
                emptyList()
            },
            shifts = if (hasTable) shifts else emptyList(),
        )
        WeekPageFragment.latest = snapshot
        val fragment =
            supportFragmentManager.findFragmentByTag("f${displayWeek - 1}") as? WeekPageFragment
        fragment?.let { page ->
            // 主题切换后 FragmentManager 会恢复旧页面实例；恢复实例必须重新绑定 Activity 回调。
            bindWeekPageCallbacks(page)
            page.update(
                snapshot.table, snapshot.times, displayWeek,
                snapshot.currentWeek, snapshot.startDate, snapshot.source, snapshot.shifts,
            )
        }
    }

    /**
     * 将主页切换到“没有课表”的可浏览状态。
     *
     * 空态仍提供 48 个周页面和完整周网格：第 1 周从当前自然周的周一开始，后续页面按周递增。
     * 这里构造的课表仅供 [WeekPageFragment] 排版使用，不会写入 Room，也不会被课程编辑流程接受。
     */
    private fun renderNoTableState() {
        table = null
        currentWeek = 1
        displayWeek = 1
        courses = emptyList()
        details = emptyList()
        times = emptyList()
        shifts = emptyList()

        val preview = createNoTablePreview()
        applyBackground(preview.background)
        ui.setHeaderContentColor(ScheduleThemeColors.tableTextColor(this, preview.textColor))
        // 先发布快照，再安装适配器；Fragment 首次进入 onResume 时即可读取正确的空态数据。
        refreshPage()
        ui.viewPager.adapter = object : FragmentStateAdapter(this@ScheduleActivity) {
            override fun getItemCount(): Int = AppDefaults.Table.MAX_SUPPORTED_WEEKS

            override fun createFragment(position: Int): Fragment =
                WeekPageFragment.newInstance(position + 1).also(::bindWeekPageCallbacks)
        }
        ui.viewPager.setCurrentItem(0, false)
        ui.sliderWeek.value = 1f
        ui.sliderWeek.valueFrom = 1f
        ui.sliderWeek.valueTo = AppDefaults.Table.MAX_SUPPORTED_WEEKS.toFloat()
        ui.sliderWeek.value = 1f
        tableAdapter?.setCurrentId(0L)
        refreshHeaders()
    }

    /**
     * 创建无课表状态的显示模型。
     *
     * @return 以本周周一为起点、总周数为系统上限的非持久化课表模型
     */
    private fun createNoTablePreview(): TableEntity = TableEntity(
        id = 0L,
        tableName = getString(R.string.widget_no_table),
        startDate = DateUtils.mondayOfWeek(LocalDate.now()).toString(),
        maxWeek = AppDefaults.Table.MAX_SUPPORTED_WEEKS,
    )

    /** 有当前课表时进入课表设置，否则留在当前页面并给出统一提示。 */
    private fun openScheduleSettingsIfAvailable() {
        if (table == null) {
            showNoCurrentTableToast()
            return
        }
        startActivity(Intent(this, ScheduleSettingsActivity::class.java))
    }

    /** 使用轻量 Toast 提示依赖当前课表的操作暂不可用。 */
    private fun showNoCurrentTableToast() {
        android.widget.Toast.makeText(
            this,
            R.string.no_current_table,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 为新建或系统恢复的周页面绑定宿主 Activity 回调。
     *
     * Fragment 的函数类型字段不会进入 savedInstanceState。浅色/深色主题切换触发 Activity
     * 重建后，ViewPager2 会直接复用恢复的 Fragment，因此每次取得页面都必须重新绑定。
     *
     * @param page 需要连接课程详情与快速添加流程的周页面
     */
    private fun bindWeekPageCallbacks(page: WeekPageFragment) {
        page.onCourseClick = { detailId, week ->
            CourseDetailSheet.newInstance(detailId, week)
                .show(supportFragmentManager, "detail")
        }
        page.onQuickAddRequest = { draft -> launchQuickAdd(draft) }
    }

    /** 背景：色值直接铺色，图片 uri 走 BitmapFactory 解码（避免 Coil UI 依赖） */
    private fun applyBackground(background: String) {
        ScheduleThemeColors.darkTableBackgroundColor(this)?.let { darkBackground ->
            // 夜间模式强制使用 M3E 深色表面，避免浅色自定义图片或颜色让主表格重新变亮。
            ui.ivBg.setImageDrawable(null)
            ui.ivBg.setBackgroundColor(darkBackground)
            return
        }
        if (background.isBlank() || background.startsWith("#")) {
            ui.ivBg.setImageDrawable(null)
            if (background.startsWith("#")) {
                ui.ivBg.setBackgroundColor(Color.parseColor(background))
            } else {
                ui.ivBg.setBackgroundResource(R.drawable.main_gradient_background)
            }
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bmp = runCatching {
                contentResolver.openInputStream(Uri.parse(background))?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bmp != null) ui.ivBg.setImageBitmap(bmp)
            }
        }
    }

    /** 底部浮窗课表项拖拽排序（横向，与多课表管理共用 tableOrder） */
    private fun attachTableDragAndDrop() {
        val callback = object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                makeMovementFlags(
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT
                            or androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
                )

            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val adapter = rv.adapter as? TableNameAdapter ?: return false
                return adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    tableAdapter?.setDragging(true)
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                tableAdapter?.setDragging(false)
                (rv.adapter as? TableNameAdapter)?.persistOrder()
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(callback).attachToRecyclerView(ui.rvTable)
    }

    /** 表格菜单课表项（item_table_select_main：背景图+选中勾角标+名称；点角标弹改名/删除菜单） */
    private inner class TableNameAdapter(
        private val onPick: (TableEntity) -> Unit
    ) : RecyclerView.Adapter<TableNameHolder>() {

        private val tables = mutableListOf<TableEntity>()
        private var currentId = -1L

        /** 拖拽中不接受列表刷新，否则手指下的项会被抽走 */
        private var dragging = false

        /** 课表列表更新（新建/删除/改名/排序写回后 Room 重发） */
        fun submit(list: List<TableEntity>) {
            if (dragging) return
            tables.clear()
            tables.addAll(list)
            notifyDataSetChanged()
        }

        /** 当前表变化 → 重画选中勾 */
        fun setCurrentId(id: Long) {
            if (currentId == id) return
            currentId = id
            notifyDataSetChanged()
        }

        fun setDragging(value: Boolean) {
            dragging = value
        }

        /** 拖拽换位（clearView 时统一持久化） */
        fun move(from: Int, to: Int): Boolean {
            if (from == to || from !in tables.indices || to !in tables.indices) return false
            tables.add(to, tables.removeAt(from))
            notifyItemMoved(from, to)
            return true
        }

        fun persistOrder() {
            lifecycleScope.launch { repo.reorderTables(tables.toList()) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TableNameHolder {
            val view = layoutInflater.inflate(R.layout.item_table_select_main, parent, false)
            return TableNameHolder(view)
        }

        override fun getItemCount(): Int = tables.size

        override fun onBindViewHolder(holder: TableNameHolder, position: Int) {
            val t = tables[position]
            val image = holder.itemView.findViewById<ImageView>(R.id.iv_table_bg)
            if (t.background.startsWith("#")) {
                try {
                    image.setBackgroundColor(Color.parseColor(t.background))
                } catch (_: IllegalArgumentException) {
                    image.setBackgroundResource(R.drawable.main_gradient_background)
                }
            } else {
                image.setBackgroundResource(R.drawable.main_gradient_background)
            }
            image.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val d = view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, 12 * d)
                }
            }
            image.clipToOutline = true
            val setting = holder.itemView.findViewById<ImageView>(R.id.menu_setting)
            if (t.id == currentId) {
                setting.setImageResource(R.drawable.ms_check_24)
                setting.visibility = View.VISIBLE
            } else {
                setting.visibility = View.GONE
            }
            holder.itemView.findViewById<TextView>(R.id.tv_table_name).text = t.tableName
            holder.itemView.setOnClickListener { onPick(t) }
            setting.setOnClickListener { view ->
                CascadeMenu(view, 0, Gravity.END).apply {
                    item(getString(R.string.rename), R.drawable.ms_edit_24) {
                        showRenameDialog(t)
                    }
                    item(getString(R.string.delete), R.drawable.ms_delete_24) {
                        lifecycleScope.launch {
                            repo.deleteTable(t.id)
                        }
                    }
                }.show()
            }
        }

    }

    /** 课表重命名（简化为输入对话框，语义一致） */
    private fun showRenameDialog(t: TableEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        editText.setText(t.tableName)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setting_schedule_name)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editText.text?.toString()?.trim()
            if (!name.isNullOrEmpty()) {
                lifecycleScope.launch {
                    repo.updateTable(t.copy(tableName = name))
                    dialog.dismiss()
                }
            }
        }
    }

    companion object {
        /** 网页导入成功后传递给主课表页的越界课程摘要。 */
        const val EXTRA_IMPORT_RANGE_REPORT =
            "com.openwakeup.schedule.extra.IMPORT_RANGE_REPORT"

        /** 厂商导航栏高度报告不准时，底部浮窗额外保留的视觉安全距离。 */
        private const val BOTTOM_SHEET_NAVIGATION_CLEARANCE_DP = 32
    }
}

private class TableNameHolder(view: View) : RecyclerView.ViewHolder(view)
