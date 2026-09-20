package com.openwakeup.schedule.feature.importexport

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.openwakeup.schedule.R
import com.openwakeup.schedule.feature.schedule.ScheduleActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CSV、HTML、ICS、教务网页和备份导入共用的成功反馈与返回主课表流程。
 *
 * 应用主题直接继承 Material 3 Expressive，因此这里创建的 [Snackbar] 会使用组件库自带的容器、
 * 排版和 fade 动画。消息淡入完成后保持一秒，再主动触发淡出；只有淡出结束且导入页仍在前台时
 * 才启动主课表。用户主动离开或切到后台时不强制导航，并通过 [onNavigationSkipped] 恢复当前页。
 */
internal object ImportSuccessFeedback {

    /**
     * 使用标准课程导入文案展示成功消息，并把越界课程报告带到主课表页。
     *
     * @param activity 当前课程导入页
     * @param root Snackbar 用于查找父容器的页面根视图
     * @param anchor Snackbar 在页面底部避让的悬浮导入按钮
     * @param result 已写入数据库的课程数量与越界课程报告
     * @param onNavigationSkipped 页面不再位于前台时，用于恢复按钮等当前页状态
     */
    fun showThenReturnToSchedule(
        activity: AppCompatActivity,
        root: View,
        anchor: View,
        result: CourseImportResult,
        onNavigationSkipped: () -> Unit,
    ) {
        val successMessage = activity.resources.getQuantityString(
            R.plurals.import_ok_courses,
            result.importedSessionCount,
            result.importedSessionCount,
        )
        showThenReturnToSchedule(
            activity = activity,
            root = root,
            anchor = anchor,
            successMessage = successMessage,
            rangeReport = result.rangeReport,
            onNavigationSkipped = onNavigationSkipped,
        )
    }

    /**
     * 展示指定成功文案，并在完整动画结束后按页面状态决定是否返回主课表。
     *
     * 备份导入没有课程越界报告，可保持 [rangeReport] 为 `null`；[onNavigationStarted] 用于在导航
     * 已经发起后应用可能触发 Activity 重建的主题或语言，避免重建过程提前截断 Snackbar。
     *
     * @param activity 当前导入页
     * @param root Snackbar 用于查找父容器的页面根视图
     * @param anchor Snackbar 在页面底部避让的悬浮导入按钮
     * @param successMessage 当前导入类型对应的成功文案
     * @param rangeReport 课程越界报告；备份导入时为 `null`
     * @param onNavigationStarted 主课表启动请求发出后的附加操作
     * @param onNavigationSkipped 页面不再位于前台时，用于恢复按钮等当前页状态
     */
    fun showThenReturnToSchedule(
        activity: AppCompatActivity,
        root: View,
        anchor: View,
        successMessage: CharSequence,
        rangeReport: CourseImportRangeReport? = null,
        onNavigationStarted: () -> Unit = {},
        onNavigationSkipped: () -> Unit,
    ) {
        var holdFinished = false
        var fadeOutFinished = false
        var navigationStarted = false
        val successSnackbar = Snackbar.make(
            root,
            successMessage,
            Snackbar.LENGTH_INDEFINITE,
        ).setAnchorView(anchor)

        /** 在导入页仍位于前台时导航，否则恢复仍存活的原页面。 */
        fun finishSuccessFlow() {
            val pageIsVisible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            when {
                navigationStarted || activity.isFinishing || activity.isDestroyed -> Unit
                pageIsVisible -> {
                    navigationStarted = true
                    val scheduleIntent = Intent(activity, ScheduleActivity::class.java).apply {
                        // ScheduleActivity 为 singleTask；这两个标记同时明确清理导入流程中间页的语义。
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        rangeReport?.let { report ->
                            putExtra(ScheduleActivity.EXTRA_IMPORT_RANGE_REPORT, report)
                        }
                    }
                    activity.startActivity(scheduleIntent)
                    onNavigationStarted()
                }

                else -> onNavigationSkipped()
            }
        }

        successSnackbar.addCallback(object : Snackbar.Callback() {
            override fun onShown(shownSnackbar: Snackbar?) {
                activity.lifecycleScope.launch {
                    delay(SUCCESS_MESSAGE_HOLD_MILLIS)
                    holdFinished = true
                    if (fadeOutFinished) {
                        // 提前划走消息时仍等待满一秒，然后再依据页面前台状态决定是否导航。
                        finishSuccessFlow()
                    } else {
                        shownSnackbar?.dismiss() ?: successSnackbar.dismiss()
                    }
                }
            }

            override fun onDismissed(dismissedSnackbar: Snackbar?, event: Int) {
                fadeOutFinished = true
                if (holdFinished) finishSuccessFlow()
            }
        })
        successSnackbar.show()
    }

    /** 成功消息淡入完成后完整停留的时间。 */
    private const val SUCCESS_MESSAGE_HOLD_MILLIS = 1_000L
}
