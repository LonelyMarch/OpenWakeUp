package com.openwakeup.schedule.core.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * 统一、安全地把 HTTP/HTTPS 链接交给系统浏览器处理。
 *
 * 设置列表的点击回调发生在当前触摸事件仍处于派发阶段时。若此时同步启动系统浏览器解析页，
 * 解析页会立即夺走窗口焦点；用户快速取消时，Material 列表项可能尚未完成按压态清理，随后
 * 第一次点击便可能只清除上一轮状态而不触发回调。本工具把外部跳转延后到下一动画帧，确保
 * 当前触摸序列先完整结束，同时不保存任何“正在跳转”标记，取消后可以立即再次发起跳转。
 */
object ExternalWebLinkLauncher {

    /**
     * 在下一动画帧打开一个外部网页。
     *
     * 每次调用都会创建全新的 [Intent]，不会复用上一次被系统解析页取消的请求。仅允许
     * `http` 和 `https`，避免页面配置错误时把任意自定义协议交给外部应用。
     *
     * @param activity 发起外部跳转的前台页面
     * @param url 需要打开的完整 HTTP/HTTPS 地址
     * @param onFailure URL 非法或系统中没有可处理应用时的回调
     */
    fun open(
        activity: Activity,
        url: String,
        onFailure: () -> Unit,
    ) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            onFailure()
            return
        }

        // 等待当前 ACTION_UP、点击回调及 Material 按压态复位完成后，再让系统窗口取得焦点。
        activity.window.decorView.postOnAnimation {
            if (activity.isFinishing || activity.isDestroyed) {
                // 页面生命周期已经结束时直接丢弃待执行跳转，不能再操作原页面上的 Snackbar 等 View。
                return@postOnAnimation
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            runCatching { activity.startActivity(intent) }
                .onFailure { onFailure() }
        }
    }
}
