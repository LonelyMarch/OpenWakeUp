package com.openwakeup.schedule.core.designsystem.component

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openwakeup.schedule.R

/**
 * Cascade 风格锚定弹出菜单：PopupWindow + ViewFlipper + RecyclerView，
 * 二级子菜单以翻转动画展开。视觉与交互保持 Cascade 库风格：
 * 圆角 surface 卡片背景、行内 icon+标题，点击项执行回调后淡出关闭。
 *
 * 用法：
 * ```
 * CascadeMenu(anchorView, widthPx, gravity).apply {
 *     item("标题", iconRes) { /* 点击 */ }
 * }.show()
 * ```
 */
class CascadeMenu(
    private val anchor: View,
    private val widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    private val gravity: Int = Gravity.START
) {

    /** 单层菜单项 */
    private class Entry(val title: CharSequence, val iconRes: Int, val onClick: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** 添加普通项 */
    fun item(title: CharSequence, iconRes: Int = 0, onClick: () -> Unit): CascadeMenu {
        entries.add(Entry(title, iconRes, onClick))
        return this
    }

    private var popup: PopupWindow? = null
    private var activeMenu: View? = null
    private var activeOverlay: FrameLayout? = null

    /**
     * 在锚点下方展示菜单。
     *
     * 该入口保留给顶部工具栏等空间充足的调用方，行为与 Cascade 的
     * `showAsDropDown` 语义一致。
     */
    fun show() {
        show(centered = false)
    }

    /**
     * 在整个应用窗口中央展示菜单。
     *
     * 底部浮窗中的入口靠近屏幕底边，若继续向下展开会超出可视区域。居中展示可确保
     * 菜单在不同屏幕尺寸下完整可见，并使用半透明遮罩降低其余内容的视觉干扰。
     */
    fun showCentered() {
        show(centered = true)
    }

    /**
     * 创建并展示弹窗。
     *
     * @param centered `true` 时相对根窗口居中，否则相对锚点向下展示
     */
    private fun show(centered: Boolean) {
        if (entries.isEmpty()) return
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val flipper = ViewFlipper(context).apply {
            setBackgroundResource(R.drawable.cascade_bg)
            elevation = 8f * density
            clipToOutline = true
        }
        flipper.addView(buildLevel(context, entries))
        val menuWidth = if (widthPx > 0) widthPx else ViewGroup.LayoutParams.WRAP_CONTENT
        val popupContent: View
        val popupWidth: Int
        val popupHeight: Int
        val centeredOverlay: FrameLayout?
        if (centered) {
            // 全屏遮罩既能稳定暗化背景，也把空白区域统一变成“点击关闭”热区。
            val overlay = FrameLayout(context).apply {
                // 遮罩从完全透明开始，展示后再用短动画渐暗。
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                addView(
                    flipper, FrameLayout.LayoutParams(
                        menuWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                )
            }
            // 消费菜单卡片空白处的点击，避免事件落到外层遮罩后误关闭。
            flipper.isClickable = true
            centeredOverlay = overlay
            popupContent = overlay
            popupWidth = ViewGroup.LayoutParams.MATCH_PARENT
            popupHeight = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            centeredOverlay = null
            popupContent = flipper
            popupWidth = menuWidth
            popupHeight = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val window = PopupWindow(
            popupContent,
            popupWidth,
            popupHeight,
            true,
        ).apply {
            // 透明窗口背景保证返回键与外部点击的 dismiss 行为在不同 Android 版本上一致。
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = !centered
            elevation = 8f * density
            if (centered) {
                // PopupWindow 默认只在状态栏与导航栏之间的可用区域布局，即使内容高度是
                // MATCH_PARENT，遮罩也无法覆盖透明系统栏。居中菜单必须按整块物理屏幕布局，
                // 并关闭边界裁剪，让同一层遮罩连续延伸到状态栏和三键/手势导航区域。
                setAttachedInDecor(false)
                // 该 API 的 Java setter 名为 setIsLaidOutInScreen，不满足 Kotlin 可变属性映射规则。
                setIsLaidOutInScreen(true)
                isClippingEnabled = false
            }
        }
        popup = window
        activeMenu = flipper
        activeOverlay = centeredOverlay
        centeredOverlay?.setOnClickListener { dismissAnimated {} }
        if (centered) {
            // 使用根视图作为定位基准，避免坐标受到 BottomSheet 自身位置影响。
            window.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
        } else {
            val xOffset = if (widthPx > 0 && gravity == Gravity.END) -widthPx + anchor.width else 0
            window.showAsDropDown(anchor, xOffset, 0)
        }
        animateAppearance(flipper, centeredOverlay)
    }

    /**
     * 先淡出当前菜单与暗色遮罩，再执行选项回调。
     *
     * 这样从居中菜单进入下一个页面或对话框时不会瞬间跳变。
     */
    private fun dismissAnimated(afterDismiss: () -> Unit) {
        val currentPopup = popup ?: run {
            afterDismiss()
            return
        }
        val menu = activeMenu ?: run {
            currentPopup.dismiss()
            afterDismiss()
            return
        }
        activeOverlay?.let { overlay ->
            ValueAnimator.ofInt(CENTERED_SCRIM_ALPHA, 0).apply {
                duration = POPUP_EXIT_DURATION_MS
                addUpdateListener { animator ->
                    overlay.setBackgroundColor(Color.argb(animator.animatedValue as Int, 0, 0, 0))
                }
                start()
            }
        }
        menu.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(POPUP_EXIT_DURATION_MS)
            .withEndAction {
                currentPopup.dismiss()
                popup = null
                activeMenu = null
                activeOverlay = null
                afterDismiss()
            }
            .start()
    }

    /**
     * 播放菜单弹出和背景渐暗动画。
     *
     * @param menu 菜单卡片视图
     * @param overlay 居中菜单的全屏遮罩；锚定菜单传入 `null`
     */
    private fun animateAppearance(menu: View, overlay: FrameLayout?) {
        menu.alpha = 0f
        menu.scaleX = 0.92f
        menu.scaleY = 0.92f
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(POPUP_ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (overlay != null) {
            ValueAnimator.ofInt(0, CENTERED_SCRIM_ALPHA).apply {
                duration = SCRIM_FADE_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val alpha = animator.animatedValue as Int
                    overlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
                }
                start()
            }
        }
    }

    private fun buildLevel(context: Context, items: List<Entry>): RecyclerView {
        val recycler = RecyclerView(context)
        recycler.id = R.id.anko_layout
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        val inflater = LayoutInflater.from(context)
        recycler.adapter = object : RecyclerView.Adapter<RowHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
                RowHolder(inflater.inflate(R.layout.cascade_menu_item, parent, false))

            override fun getItemCount(): Int = items.size

            override fun onBindViewHolder(holder: RowHolder, position: Int) {
                val entry = items[position]
                holder.title.text = entry.title
                if (entry.iconRes != 0) {
                    holder.icon.setImageResource(entry.iconRes)
                    holder.icon.visibility = View.VISIBLE
                } else {
                    holder.icon.visibility = View.GONE
                }
                holder.itemView.setOnClickListener {
                    dismissAnimated(entry.onClick)
                }
            }
        }
        return recycler
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.cascade_icon)
        val title: TextView = view.findViewById(R.id.cascade_title)
    }

    private companion object {
        /** 居中菜单背景遮罩透明度，约 32%，与 Material 对话框的弱暗化视觉接近。 */
        const val CENTERED_SCRIM_ALPHA = 82

        /** 菜单卡片弹出动画时长。 */
        const val POPUP_ENTER_DURATION_MS = 170L

        /** 背景遮罩渐暗时长；略短于卡片动画，使焦点快速收束到菜单。 */
        const val SCRIM_FADE_DURATION_MS = 120L

        /** 菜单和遮罩淡出时长。 */
        const val POPUP_EXIT_DURATION_MS = 110L
    }
}
