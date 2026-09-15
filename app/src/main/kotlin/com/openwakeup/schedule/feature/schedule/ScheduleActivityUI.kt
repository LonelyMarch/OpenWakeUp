/*
 * 本文件基于 YZune/WakeupSchedule_Kotlin 的 Apache-2.0 代码修改。
 * 原始版权：Copyright 2019 YZune。
 * OpenWakeUp 于 2026 年重构了主课表视图树、Material 组件和自适应布局；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.feature.schedule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.constraintlayout.widget.ConstraintSet
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.openwakeup.schedule.R
import kotlin.math.roundToInt

/**
 * 主课表界面视图树构建器：主界面无 XML，全部由代码构建。
 * 结构：CoordinatorLayout(anko_root) → [ConstraintLayout(anko_cl_schedule)(背景图+日期/周次/星期+
 * 四个图标按钮+ViewPager2), LinearLayout(anko_bottom_sheet)(卡1 周次滑条区+卡2 导航宫格)]。
 * 尺寸/边距/字号：16dp 边距、32dp 按钮、12sp 菜单标题、10sp 宫格文字、48px 卡片圆角。
 */
class ScheduleActivityUI(private val activity: ScheduleActivity) {

    /** 根布局（CoordinatorLayout，insets 监听用） */
    val rootLayout: CoordinatorLayout

    private val sheetBehavior: BottomSheetBehavior<*>

    /** 底部菜单行为（hideable=true、peekHeight=0、fitToContents、SAVE_ALL） */
    val behavior: BottomSheetBehavior<*> get() = sheetBehavior

    val viewPager: ViewPager2 = ViewPager2(activity).apply { id = R.id.anko_vp_schedule }
    val ivBg: AppCompatImageView = AppCompatImageView(activity).apply {
        id = R.id.anko_iv_bg
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    val tvDate: AppCompatTextView = AppCompatTextView(activity).apply {
        id = R.id.anko_tv_date
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(activity, R.color.md_theme_onSurface))
        textSize = 13f
    }
    val tvWeek: AppCompatTextView = AppCompatTextView(activity).apply {
        id = R.id.anko_tv_week
        setTextColor(ContextCompat.getColor(activity, R.color.md_theme_onSurface))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }
    val tvWeekday: AppCompatTextView = AppCompatTextView(activity).apply {
        id = R.id.anko_tv_weekday
        setTextColor(ContextCompat.getColor(activity, R.color.md_theme_onSurface))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }
    val ibAdd: AppCompatImageButton = toolbarButton(R.id.anko_ib_add, R.drawable.ms_add_24)
    val ibMore: AppCompatImageButton = toolbarButton(R.id.anko_ib_more, R.drawable.ms_more_vert_24)

    /** 点浮窗外部区域收起用的透明遮罩（展开时才可见/可点） */
    val sheetScrim: View = View(activity).apply {
        id = R.id.anko_sheet_scrim
        visibility = View.GONE
        // 仅负责拦截浮窗外部点击，不绘制颜色，展开菜单时主课表保持原亮度。
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        alpha = 1f
        isClickable = true
        isFocusable = false
    }

    /** 卡片1：周次滑条 + 课表列表 */
    val sliderWeek: Slider = Slider(activity).apply {
        id = R.id.bottom_sheet_slider_week
        stepSize = 1f
        setLabelFormatter { value -> activity.getString(R.string.week_num, value.roundToInt()) }
        haloRadius = 0
        thumbElevation = 0f
        thumbHeight = (20 * activity.resources.displayMetrics.density).roundToInt()
        trackHeight = (16 * activity.resources.displayMetrics.density).roundToInt()
        // Material 1.14 以半径控制刻度显示；半径为 0 时不绘制活动与非活动刻度。
        tickActiveRadius = 0
        tickInactiveRadius = 0
    }
    val rvTable: RecyclerView = RecyclerView(activity).apply {
        id = R.id.bottom_sheet_rv_table
        layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
    }
    private val btnChangeWeek: MaterialButton =
        menuButton(R.id.bottom_sheet_change_week_btn, R.string.main_modify_current_week)
    private val btnCreateSchedule: MaterialButton =
        menuButton(R.id.bottom_sheet_create_schedule_btn, R.string.main_create_new_schedule)
    private val btnManageSchedule: MaterialButton =
        menuButton(R.id.bottom_sheet_manage_schedule_btn, R.string.main_manage_schedules)

    /** 底部菜单"设置当前周"按钮 */
    val btnChange: MaterialButton get() = btnChangeWeek

    /** 底部菜单"新建课表"按钮 */
    val btnCreate: MaterialButton get() = btnCreateSchedule

    /** 底部菜单"管理课表"按钮 */
    val btnManage: MaterialButton get() = btnManageSchedule

    /**
     * 卡片2：导航宫格。顶栏的导入/导出按钮已并入此处，
     * 两行按功能对称排序：导入课表 导出数据 课表设置 全局设置 / 上课时间 小部件设置 课程闹钟 关于。
     */
    val navImport: LinearLayout =
        navCell(R.id.main_nav_help, R.string.main_nav_import, R.drawable.ms_download_24)
    val navScheduleSetting: LinearLayout = navCell(
        R.id.main_nav_schedule_setting,
        R.string.title_schedule_settings,
        R.drawable.ms_tune_24
    )
    val navCourse: LinearLayout =
        navCell(R.id.main_nav_course, R.string.title_widget_settings, R.drawable.ms_widgets_24)
    val navTime: LinearLayout =
        navCell(R.id.main_nav_time, R.string.setting_class_time, R.drawable.ms_schedule_24)
    val navExport: LinearLayout =
        navCell(R.id.main_nav_feedback, R.string.main_nav_export, R.drawable.ms_share_24)
    val navSettings: LinearLayout =
        navCell(R.id.main_nav_settings, R.string.title_settings, R.drawable.ms_settings_24)
    val navSuda: LinearLayout =
        navCell(R.id.main_nav_suda, R.string.main_nav_alarm, R.drawable.ms_alarm_24)
    val navAbout: LinearLayout =
        navCell(R.id.main_nav_about, R.string.title_about, R.drawable.ms_info_24)

    /** 底部菜单容器（anko_bottom_sheet） */
    val bottomSheet: LinearLayout
    val clSchedule: ConstraintLayout

    init {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).roundToInt()

        // ===== 顶栏区（anko_cl_schedule） =====
        val cl = ConstraintLayout(activity).apply { id = R.id.anko_cl_schedule }

        cl.addView(ivBg, LayoutParams(0, 0).apply {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = LayoutParams.PARENT_ID
        })

        // 第一行：第N周 + 星期（加粗大字）；第二行：日期（常规小字）
        cl.addView(
            tvWeek, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID
                marginStart = dp(16f)
            })

        cl.addView(
            tvWeekday, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToEnd = R.id.anko_tv_week
                baselineToBaseline = R.id.anko_tv_week
                marginStart = dp(8f)
            })

        cl.addView(
            tvDate, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = R.id.anko_tv_week
                topToBottom = R.id.anko_tv_week
            })

        fun toolbarLp(endToStart: Int, marginEnd: Float): LayoutParams =
            LayoutParams(dp(32f), dp(32f)).apply {
                this.endToStart = endToStart
                topToTop = R.id.anko_tv_week
                bottomToBottom = R.id.anko_tv_date
                this.marginEnd = dp(marginEnd)
            }

        cl.addView(ibAdd, toolbarLp(R.id.anko_ib_more, 16f))
        cl.addView(ibMore, LayoutParams(dp(32f), dp(32f)).apply {
            endToEnd = LayoutParams.PARENT_ID
            topToTop = R.id.anko_tv_week
            bottomToBottom = R.id.anko_tv_date
            marginEnd = dp(8f)
        })

        cl.addView(viewPager, LayoutParams(0, 0).apply {
            topToBottom = R.id.anko_tv_date
            bottomToBottom = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topMargin = dp(4f)
        })
        clSchedule = cl

        // ===== 卡片1：周次 + 课表列表 =====
        val weekCard = ConstraintLayout(activity).apply { isMotionEventSplittingEnabled = false }
        val titleWeek = AppCompatTextView(activity).apply {
            id = R.id.bottom_sheet_title_week
            text = getString(R.string.main_sheet_title_week)
            textSize = 12f
        }
        weekCard.addView(
            titleWeek, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID
                topMargin = dp(16f)
                marginStart = dp(16f)
            })
        weekCard.addView(
            btnChangeWeek, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                endToEnd = LayoutParams.PARENT_ID
                topToTop = R.id.bottom_sheet_title_week
                bottomToBottom = R.id.bottom_sheet_title_week
                marginEnd = dp(8f)
            })
        weekCard.addView(
            sliderWeek, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToBottom = R.id.bottom_sheet_title_week
                marginStart = dp(8f)
                marginEnd = dp(8f)
            })
        val titleSchedule = AppCompatTextView(activity).apply {
            id = R.id.bottom_sheet_title_schedule
            text = getString(R.string.main_sheet_title_multi_schedules)
            textSize = 12f
        }
        weekCard.addView(
            titleSchedule, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = LayoutParams.PARENT_ID
                topToBottom = R.id.bottom_sheet_slider_week
                marginStart = dp(16f)
            })
        weekCard.addView(
            rvTable, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = LayoutParams.PARENT_ID
                endToEnd = LayoutParams.PARENT_ID
                topToBottom = R.id.bottom_sheet_title_schedule
                bottomToBottom = LayoutParams.PARENT_ID
                topMargin = dp(16f)
                marginStart = dp(16f)
                marginEnd = dp(16f)
                bottomMargin = dp(16f)
            })
        weekCard.addView(
            btnManageSchedule, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                endToEnd = LayoutParams.PARENT_ID
                topToTop = R.id.bottom_sheet_title_schedule
                bottomToBottom = R.id.bottom_sheet_title_schedule
                marginEnd = dp(8f)
            })
        weekCard.addView(
            btnCreateSchedule, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                endToStart = R.id.bottom_sheet_manage_schedule_btn
                topToTop = R.id.bottom_sheet_title_schedule
                bottomToBottom = R.id.bottom_sheet_title_schedule
            })

        // ===== 卡片2：导航宫格（2×4，行高 64dp，横向 spread 链等分四列） =====
        val navGrid = ConstraintLayout(activity)
        val cellHeight = dp(64f)
        // 顺序即从左到右、从上到下的实际渲染顺序，与用户指定的功能分组保持一致。
        // 行1 导入课表 / 导出数据 / 课表设置 / 全局设置；行2 上课时间 / 小部件设置 / 课程闹钟 / 关于
        val navOrder = listOf(
            navImport, navExport, navScheduleSetting, navSettings,
            navTime, navCourse, navSuda, navAbout,
        )
        navOrder.forEach { view -> navGrid.addView(view, LayoutParams(0, cellHeight)) }
        val navSet = ConstraintSet()
        navSet.clone(navGrid)
        val navCols = 4
        navOrder.forEachIndexed { index, view ->
            val id = view.id
            val col = index % navCols
            val row = index / navCols
            navSet.constrainHeight(id, cellHeight)
            navSet.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            if (col == 0) {
                navSet.connect(
                    id,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    0
                )
            } else {
                navSet.connect(
                    id,
                    ConstraintSet.START,
                    navOrder[index - 1].id,
                    ConstraintSet.END,
                    0
                )
            }
            if (col == navCols - 1) {
                navSet.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
            } else {
                navSet.connect(
                    id,
                    ConstraintSet.END,
                    navOrder[index + 1].id,
                    ConstraintSet.START,
                    0
                )
            }
            if (row == 0) {
                navSet.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0)
            } else {
                navSet.connect(
                    id,
                    ConstraintSet.TOP,
                    navOrder[index - navCols].id,
                    ConstraintSet.BOTTOM,
                    0
                )
                navSet.connect(
                    id,
                    ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.BOTTOM,
                    0
                )
            }
        }
        navSet.applyTo(navGrid)

        // ===== 底部菜单容器（宽窄屏两种排布） =====
        val surfaceAlpha240 = withAlpha(
            ContextCompat.getColor(activity, R.color.md_theme_surface), 240
        )
        val isWide = activity.resources.getDimensionPixelSize(R.dimen.wide_screen) <=
                activity.resources.displayMetrics.widthPixels
        bottomSheet = LinearLayout(activity).apply {
            id = R.id.anko_bottom_sheet
            gravity = Gravity.BOTTOM
            orientation = LinearLayout.VERTICAL
        }
        if (!isWide) {
            bottomSheet.addView(
                weekCardView(activity, weekCard, surfaceAlpha240), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16f), 0, dp(16f), 0) })
            bottomSheet.addView(
                weekCardView(activity, navGrid, surfaceAlpha240), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16f), dp(16f), dp(16f), dp(16f)) })
        } else {
            bottomSheet.addView(
                weekCardView(activity, weekCard, surfaceAlpha240),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8f), 0, dp(4f), dp(8f))
                    weight = 1f
                })
            bottomSheet.addView(
                weekCardView(activity, navGrid, surfaceAlpha240),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4f), 0, dp(8f), dp(8f))
                    weight = 1f
                })
        }

        // ===== 根布局 =====
        val root = CoordinatorLayout(activity).apply { id = R.id.anko_root }
        rootLayout = root
        root.addView(
            cl, CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 遮罩夹在内容与浮窗之间：浮窗展开时吃掉外部点击并收起浮窗
        root.addView(
            sheetScrim, CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val sheetLp = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        val behavior = BottomSheetBehavior<View>()
        behavior.isHideable = true
        behavior.peekHeight = 0
        behavior.isFitToContents = true
        behavior.saveFlags = BottomSheetBehavior.SAVE_ALL
        sheetBehavior = behavior
        sheetLp.behavior = behavior
        root.addView(bottomSheet, sheetLp)

        activity.setContentView(root)
    }

    private fun weekCardView(context: Context, content: View, bgColor: Int): MaterialCardView =
        MaterialCardView(context).apply {
            setCardBackgroundColor(bgColor)
            radius = 48f // 48px（非 dp）
            cardElevation = 0f
            addView(
                content, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

    private fun toolbarButton(id: Int, iconRes: Int): AppCompatImageButton {
        val button = AppCompatImageButton(activity)
        button.id = id
        button.setImageResource(iconRes)
        button.setBackgroundResource(selectableItemBackground())
        return button
    }

    /**
     * 同步主课表左上角日期、周次、星期与顶栏操作图标的前景色。
     *
     * @param color 当前主题解析出的课表高对比文字色
     */
    fun setHeaderContentColor(color: Int) {
        // 日期是左上角字号较小的一行，必须与周次同时刷新，避免暗色模式仍残留黑色文字。
        tvDate.setTextColor(color)
        tvWeek.setTextColor(color)
        tvWeekday.setTextColor(color)
        ibAdd.setColorFilter(color)
        ibMore.setColorFilter(color)
    }

    private fun menuButton(id: Int, titleRes: Int): MaterialButton =
        MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            this.id = id
            text = getString(titleRes)
            minWidth = 0
            minimumWidth = 0
            textSize = 12f
            val pad = (8 * activity.resources.displayMetrics.density).roundToInt()
            setPadding(pad, 0, pad, 0)
        }

    /**
     * 创建底部浮窗的 Material Symbols Rounded 导航项。
     *
     * 图标使用与全局设置页一致的 `onSurfaceVariant` 灰色，标题使用 `onSurface`；两者都从
     * 当前主题属性解析，因此深浅主题切换后仍保持同一套 M3E 视觉层级。
     */
    private fun navCell(id: Int, titleRes: Int, iconRes: Int): LinearLayout =
        LinearLayout(activity).apply {
            this.id = id
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(selectableItemBackground())
            val iconColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            val labelColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurface,
            )
            addView(AppCompatImageView(activity).apply {
                setImageResource(iconRes)
                // 所有 ms_* 资源均为 Material Symbols Rounded；图标灰色与设置页 leading icon 完全同源。
                imageTintList = ColorStateList.valueOf(iconColor)
            })
            addView(
                AppCompatTextView(activity).apply {
                    textSize = 10f
                    setText(titleRes)
                    setTextColor(labelColor)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * activity.resources.displayMetrics.density).roundToInt()
                })
        }

    private fun selectableItemBackground(): Int {
        val outValue = TypedValue()
        activity.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            outValue,
            true
        )
        return outValue.resourceId
    }

    private fun getString(res: Int): String = activity.getString(res)

    private companion object {
        fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)
    }
}
