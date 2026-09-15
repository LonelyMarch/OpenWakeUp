package com.openwakeup.schedule.core.designsystem.component

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.PathInterpolator
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.WeakHashMap

/**
 * MaterialSwitch 的全局强调动效。
 *
 * Material 组件内部滑块位移固定为较短时长，主题资源只能调整拇指图标的形变阶段。这里在用户
 * 切换时叠加轻微压缩与舒展，使总反馈延长到 520ms；动画只改变 View 缩放，不修改选中状态、
 * 颜色或触摸区域。
 */
object SwitchMotion {

    /** 正在运行的动画按 View 弱引用保存，避免 RecyclerView 回收后持有旧页面。 */
    private val runningAnimators = WeakHashMap<MaterialSwitch, AnimatorSet>()

    /**
     * 播放一次切换强调动画。
     *
     * @param view 刚完成选中状态切换的 MaterialSwitch
     */
    fun play(view: MaterialSwitch) {
        // 先终止旧动画并恢复其最终值，再读取当前缩放基准，避免快速连点逐次缩小控件。
        runningAnimators.remove(view)?.cancel()
        val settledScaleX = view.scaleX
        val settledScaleY = view.scaleY
        val compressedScaleX = settledScaleX * COMPRESSED_SCALE
        val compressedScaleY = settledScaleY * COMPRESSED_SCALE

        // 快速压缩负责确认点击，较慢回弹让选中颜色与拇指位置变化更容易被用户感知。
        val compress = AnimatorSet().apply {
            duration = COMPRESS_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, settledScaleX, compressedScaleX),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, settledScaleY, compressedScaleY),
            )
        }
        val settle = AnimatorSet().apply {
            duration = SETTLE_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, compressedScaleX, settledScaleX),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, compressedScaleY, settledScaleY),
            )
        }
        val animator = AnimatorSet().apply {
            playSequentially(compress, settle)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 无论正常结束还是快速切换导致取消，都恢复 XML 样式声明的最终缩放。
                    view.scaleX = settledScaleX
                    view.scaleY = settledScaleY
                    if (runningAnimators[view] === animation) runningAnimators.remove(view)
                }
            })
        }
        runningAnimators[view] = animator
        animator.start()
    }

    private const val COMPRESSED_SCALE = 0.86f
    private const val COMPRESS_DURATION_MS = 160L
    private const val SETTLE_DURATION_MS = 360L
}
