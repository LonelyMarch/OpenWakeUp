package com.openwakeup.schedule.core.util

import android.app.Activity
import android.os.Build
import androidx.annotation.AnimRes

/**
 * 为 Android 13–17 统一设置 Activity 打开与关闭动画。
 *
 * Android 14 新增的 [Activity.overrideActivityTransition] 会持续覆盖指定类型的转场；Android 13
 * 只能使用一次性的旧接口。项目最低版本是 API 33，因此这里只保留这两条必要路径。
 */
object ActivityTransitionCompat {

    /**
     * 设置当前 Activity 的打开动画。
     *
     * @param activity 刚刚启动目标页面的宿主 Activity
     * @param enterAnim 目标页面进入动画资源
     * @param exitAnim 当前页面退出动画资源
     */
    fun applyOpen(
        activity: Activity,
        @AnimRes enterAnim: Int,
        @AnimRes exitAnim: Int,
    ) = apply(activity, Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim)

    /**
     * 设置当前 Activity 的关闭动画。
     *
     * @param activity 正在结束的 Activity
     * @param enterAnim 下层页面进入动画资源
     * @param exitAnim 当前页面退出动画资源
     */
    fun applyClose(
        activity: Activity,
        @AnimRes enterAnim: Int,
        @AnimRes exitAnim: Int,
    ) = apply(activity, Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim)

    /** 根据运行时版本选择系统提供的转场覆盖接口。 */
    private fun apply(
        activity: Activity,
        overrideType: Int,
        @AnimRes enterAnim: Int,
        @AnimRes exitAnim: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(overrideType, enterAnim, exitAnim)
        } else {
            applyAndroid13Transition(activity, enterAnim, exitAnim)
        }
    }

    /** Android 13 没有 overrideActivityTransition，只能调用该版本仍支持的旧接口。 */
    @Suppress("DEPRECATION")
    private fun applyAndroid13Transition(
        activity: Activity,
        @AnimRes enterAnim: Int,
        @AnimRes exitAnim: Int,
    ) {
        activity.overridePendingTransition(enterAnim, exitAnim)
    }
}
