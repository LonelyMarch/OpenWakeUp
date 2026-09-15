package com.openwakeup.schedule.feature.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.shape.ShapeAppearanceModel
import com.openwakeup.schedule.R
import java.util.WeakHashMap

/**
 * 手工 XML 卡片的统一外观入口：容器色与圆角取设置分段卡同一 token
 * （setting_card_container / 16dp），避免与 Expressive 列表出现两套观感。
 */
object SettingsAppearance {
    const val CORNER_RADIUS_DP = 16f

    /** 正在运行的显隐动画；弱引用避免设置页面销毁后保留 View。 */
    private val visibilityAnimators = WeakHashMap<View, ValueAnimator>()

    /** 对设置页中的 XML 卡片应用统一容器色与圆角；真实小部件预览宿主保留自身布局与背景。 */
    fun applyCards(view: View) {
        if (view.id == R.id.preview_widget_host) return
        if (view is MaterialCardView) {
            view.setCardBackgroundColor(
                ContextCompat.getColor(view.context, R.color.setting_card_container),
            )
            view.radius = CORNER_RADIUS_DP * view.resources.displayMetrics.density
            view.cardElevation = 0f
            view.strokeWidth = 0
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyCards(view.getChildAt(index))
        }
    }

    /**
     * 应用全局设置列表使用的分段卡外观。
     *
     * @param layout 承载分段位置状态的 ListItemLayout
     * @param card 实际绘制容器色与圆角的 ListItemCardView
     * @param index 当前项在分组中的零基序号
     * @param count 当前分组总项数
     */
    fun applySegmentedCard(
        layout: ListItemLayout,
        card: ListItemCardView,
        index: Int,
        count: Int,
    ) {
        layout.updateAppearance(index, count)
        card.setCardBackgroundColor(
            ContextCompat.getColor(
                card.context,
                R.color.setting_card_container
            )
        )
        val radius = CORNER_RADIUS_DP * card.resources.displayMetrics.density
        val shape = ShapeAppearanceModel.builder()
        when {
            count <= 1 -> shape.setAllCornerSizes(radius)
            index == 0 -> shape.setTopLeftCornerSize(radius).setTopRightCornerSize(radius)
            index == count - 1 -> shape.setBottomLeftCornerSize(radius)
                .setBottomRightCornerSize(radius)

            else -> shape.setAllCornerSizes(0f)
        }
        card.shapeAppearanceModel = shape.build()
        card.cardElevation = 0f
        card.strokeWidth = 0
    }

    /**
     * 让动态设置项通过“高度展开/收起 + 淡入/淡出 + 轻微纵向位移”出现或消失。
     *
     * 高度在每一帧参与重新布局，因此该设置项下方的分组和条目会与它同步移动，不会等动画结束后
     * 突然跳位。出现时条目从上一行底部向下展开，消失时完全按相反方向收回。
     *
     * @param view 要改变显隐状态的设置项
     * @param show `true` 为展开显示，`false` 为收起隐藏
     * @param expandedHeightPx 展开后的固定高度；传 `null` 时根据内容测量
     */
    fun animateVisibility(view: View, show: Boolean, expandedHeightPx: Int? = null) {
        if ((view.parent as? View)?.isLaidOut != true) {
            // 页面首次绑定时父容器尚无布局坐标，直接建立正确初态；只有用户操作才播放动画。
            // 不能用 view.isLaidOut 判断：GONE 的设置行本身始终未布局，会因此永远跳过首次展开动画。
            view.visibility = if (show) View.VISIBLE else View.GONE
            view.alpha = 1f
            view.translationY = 0f
            expandedHeightPx?.let { height ->
                view.layoutParams = view.layoutParams.apply { this.height = height }
            }
            return
        }

        visibilityAnimators.remove(view)?.cancel()
        val fullHeight = expandedHeightPx ?: measureExpandedHeight(view)
        val startHeight = if (show) {
            if (view.visibility == View.VISIBLE) view.height.coerceAtLeast(0) else 0
        } else {
            view.height.takeIf { it > 0 } ?: fullHeight
        }
        val endHeight = if (show) fullHeight else 0
        if (startHeight == endHeight && view.visibility == (if (show) View.VISIBLE else View.GONE)) return

        val distance = 12f * view.resources.displayMetrics.density
        if (show) {
            // 先把高度压到动画起点再改为可见，避免 GONE → VISIBLE 的第一帧闪出完整内容。
            view.layoutParams = view.layoutParams.apply { height = startHeight }
            view.alpha = startHeight.toFloat().div(fullHeight).coerceIn(0f, 1f)
            view.translationY = -distance * (1f - view.alpha)
            view.visibility = View.VISIBLE
        }
        val animator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = 320L
            // Material 标准缓入缓出曲线直接使用系统 PathInterpolator，避免额外依赖。
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { valueAnimator ->
                val animatedHeight = valueAnimator.animatedValue as Int
                // 透明度直接跟随当前高度，快速连续点击并反向播放时也不会产生亮度跳变。
                val visibleFraction = animatedHeight.toFloat().div(fullHeight).coerceIn(0f, 1f)
                view.layoutParams = view.layoutParams.apply {
                    height = animatedHeight
                }
                view.alpha = visibleFraction
                view.translationY = -distance * (1f - visibleFraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 快速反向点击时，旧动画不得覆盖新动画正在维护的状态。
                    if (visibilityAnimators[view] !== animation) return
                    visibilityAnimators.remove(view)
                    view.visibility = if (show) View.VISIBLE else View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                    view.layoutParams = view.layoutParams.apply {
                        height = expandedHeightPx ?: ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            })
        }
        visibilityAnimators[view] = animator
        animator.start()
    }

    /** 测量 `wrap_content` 设置项完全展开时的高度。 */
    private fun measureExpandedHeight(view: View): Int {
        val parentWidth = (view.parent as? View)?.width ?: view.resources.displayMetrics.widthPixels
        val horizontalMargins = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.leftMargin + it.rightMargin
        } ?: 0
        val width = (parentWidth - horizontalMargins).coerceAtLeast(1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.coerceAtLeast(1)
    }
}
