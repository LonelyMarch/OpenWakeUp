package com.openwakeup.schedule.feature.settings

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.designsystem.component.SwitchMotion

/**
 * 设置页列表实现：条目模型 + 单布局 Expressive 分段列表适配器。
 *
 * 每行复用 item_setting_expressive（ListItemLayout + ListItemCardView segmented 样式），
 * 由 Material 组件按 first/middle/last/single 状态自动切换圆角与容器色；
 * 行高密度与旧版一致（min 64dp），布尔行使用 MaterialSwitch（Widget.Material3.Switch.ListItem），
 * trailing 语义只保留 值文本 / 色块 / 开关 / 箭头 四种，避免焦点冲突。
 * 手工 48px 圆角、分隔线与勾选回弹动画已随本组件移除。
 */
sealed class SettingsItem(val name: Int) {
    internal var visible: Boolean = true
    internal var isFirst: Boolean = false
    internal var isLast: Boolean = false

    /** 该行在分组可见行中的序号；供 updateAppearance 使用，submit 时计算。 */
    internal var sectionIndex: Int = 0

    /** 该分组可见行总数；供 updateAppearance 使用，submit 时计算。 */
    internal var sectionCount: Int = 1

    /** leading Material Symbol 资源 id；0 表示该行不显示图标。 */
    open val leadingIconRes: Int get() = 0
    abstract val viewType: Int
}

/** 区块标题（setting_blank 时为占位间距） */
class HeaderItem(name: Int) : SettingsItem(name) {
    override val viewType: Int get() = TYPE_HEADER
}

/** 左标题右值行（trailing = 值文本，默认带进入下层箭头） */
class HorizontalItem(
    name: Int,
    value: String,
    override val leadingIconRes: Int = 0,
    val showChevron: Boolean = false,
) : SettingsItem(name) {
    var value: String = value
    override val viewType: Int get() = TYPE_HORIZONTAL
}

/**
 * 左标题右“前缀+数值+单位”行。
 *
 * @param format 数值越界时（学期未开始/已结束）的替代文案
 */
class NumberItem(
    name: Int,
    value: Int,
    val min: Int,
    val max: Int,
    val unit: String,
    val prefix: String = "",
    override val leadingIconRes: Int = 0,
    val format: (() -> CharSequence)? = null,
) : SettingsItem(name) {
    var value: Int = value
    override val viewType: Int get() = TYPE_NUMBER
}

/**
 * 布尔开关行（trailing = MaterialSwitch，整行点击等价切换）。
 *
 * @param titleText 可选的运行时标题；课表、时间表等数据库名称无法使用字符串资源时传入，
 * 其余设置页保持 `null` 并继续读取 [SettingsItem.name]
 */
class SwitchItem(
    name: Int,
    checked: Boolean,
    val description: String = "",
    override val leadingIconRes: Int = 0,
    val titleText: CharSequence? = null,
) : SettingsItem(name) {
    var checked: Boolean = checked
    override val viewType: Int get() = TYPE_SWITCH
}

/**
 * 上标题下说明行（纯信息/操作说明，setting_blank + 多换行作页尾占位）。
 *
 * @param isSpanned 描述文本是否为 Spanned（关于页开源致谢长文）
 * @param showChevron Navigation 语义：行尾显示进入下层箭头（如跳系统设置的操作行）
 * @param colorHex 颜色预览值（#RRGGBB[AA]）；非空时行尾显示圆形色块（颜色行语义）
 * @param usePrimaryTitleColor 标题文字是否使用与设置组题头一致的主题主色
 * @param usePrimaryDescriptionColor 说明文字是否使用与设置组题头一致的主题主色
 */
class VerticalItem(
    name: Int,
    val description: String,
    val isSpanned: Boolean = false,
    override val leadingIconRes: Int = 0,
    val showChevron: Boolean = false,
    val colorHex: String? = null,
    val usePrimaryTitleColor: Boolean = false,
    val usePrimaryDescriptionColor: Boolean = false,
) : SettingsItem(name) {
    override val viewType: Int get() = TYPE_VERTICAL
}

/** 卡片分组：组内条目由 ListItemCardView 状态拼接为一张分段卡。 */
class CategoryItem(val items: List<SettingsItem>) : SettingsItem(R.string.setting_blank) {
    override val viewType: Int get() = TYPE_CATEGORY
}

// viewType 常量
const val TYPE_HEADER = 0
const val TYPE_HORIZONTAL = 1
const val TYPE_NUMBER = 2
const val TYPE_SWITCH = 3
const val TYPE_VERTICAL = 4
const val TYPE_CATEGORY = 5

class SettingsListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val categories = mutableListOf<CategoryItem>()
    private val flat = mutableListOf<Pair<Int, Int>>()

    var onItemClickListener: ((item: SettingsItem, position: Int) -> Unit)? = null
    var onItemLongClickListener: ((item: SettingsItem, position: Int) -> Boolean)? = null
    var onItemCheckListener: ((item: SettingsItem, checked: Boolean) -> Unit)? = null

    fun submit(items: List<CategoryItem>) {
        categories.clear()
        categories.addAll(items)
        flat.clear()
        items.forEachIndexed { categoryIndex, category ->
            category.items.forEachIndexed { itemIndex, item ->
                // 空标题只是旧页面为满足分组结构留下的占位。
                // 如果仍把它加入 RecyclerView，即使 View 设为 GONE，ItemDecoration/预布局
                // 仍可能在顶部留出空白，因此在扁平索引阶段直接排除。
                val isBlankHeader = item is HeaderItem && item.name == R.string.setting_blank
                if (!isBlankHeader) flat.add(categoryIndex to itemIndex)
            }
        }
        // 计算每个可见行在其分组内的相对位置（供 updateAppearance 使用）：
        // 表头与隐藏行不参与分段，组内第一个可见行为 first、末个为 last、仅一个为 single。
        categories.forEach { category ->
            val visibleRows = category.items.filter { it.viewType != TYPE_HEADER && it.visible }
            val count = visibleRows.size.coerceAtLeast(1)
            visibleRows.forEachIndexed { index, item ->
                item.isFirst = index == 0
                item.isLast = index == visibleRows.lastIndex
                item.sectionIndex = index
                item.sectionCount = count
            }
        }
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): SettingsItem {
        val (c, i) = flat[position]
        return categories[c].items[i]
    }

    override fun getItemCount(): Int = flat.size

    override fun getItemViewType(position: Int): Int = itemAt(position).viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                inflater.inflate(
                    R.layout.item_setting_section_header,
                    parent,
                    false
                )
            )
            // 全部内容行共用同一个 Expressive 布局，按类型显隐 trailing 控件
            else -> SettingHolder(inflater.inflate(R.layout.item_setting_expressive, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = itemAt(position)
        val lp = holder.itemView.layoutParams
        if (!item.visible) {
            // 隐藏行高度折叠为 0，保持旧版的动态显隐行为（配套高度动画见 SettingsAppearance）
            lp.height = 0
            lp.width = 0
            holder.itemView.layoutParams = lp
            return
        }
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        holder.itemView.layoutParams = lp

        if (holder is HeaderHolder) {
            val title = holder.itemView as TextView
            title.setText(item.name)
            val nonBlank = title.context.getString(item.name).isNotEmpty()
            // 空白表头仅作历史占位，直接隐藏；真实表头自带上下间距
            holder.itemView.visibility = if (nonBlank) View.VISIBLE else View.GONE
            return
        }
        holder as SettingHolder
        // 页尾占位条（setting_blank + 空白描述）：旧版为透明不可点，保持一致
        val isSpacer =
            item is VerticalItem && item.name == R.string.setting_blank && item.description.isBlank()
        holder.bindSpacer(isSpacer)
        holder.bind(item)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SettingHolder) holder.cancelPendingRestore()
        super.onViewRecycled(holder)
    }

    /**
     * Expressive 设置行 ViewHolder。
     *
     * 绑定时先按行类型组装 trailing 与文本，再把组内相对位置交给组件库
     * [ListItemLayout.updateAppearance] 计算分段形状。
     */
    inner class SettingHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val listItemLayout: ListItemLayout = view as ListItemLayout
        private val card: ListItemCardView = view.findViewById(R.id.setting_card)
        private val icon: ImageView = view.findViewById(R.id.setting_icon)
        private val title: MaterialTextView = view.findViewById(R.id.setting_title)
        private val support: MaterialTextView = view.findViewById(R.id.setting_support)

        /** 布局样式定义的默认标题颜色；绑定普通行时用于清除回收视图上的主题主色。 */
        private val defaultTitleTextColors = title.textColors

        /** 布局样式定义的默认辅助文字颜色；绑定普通行时用于清除回收视图上的主色。 */
        private val defaultSupportTextColors = support.textColors
        private val color: View = view.findViewById(R.id.setting_color)
        private val value: MaterialTextView = view.findViewById(R.id.setting_value)
        private val switch: MaterialSwitch = view.findViewById(R.id.setting_switch)
        private val chevron: ImageView = view.findViewById(R.id.setting_chevron)
        private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var restoreTriggered = false
        private var pendingTouchView: View? = null

        /** 满 1.6 秒后请求页面恢复当前项；移动或抬手会提前取消。 */
        private val restoreRunnable = Runnable {
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                restoreTriggered =
                    onItemLongClickListener?.invoke(itemAt(position), position) == true
            }
        }

        /** 占位条透明化：仅作为页尾留白，不参与分段卡片着色与点击。 */
        fun bindSpacer(isSpacer: Boolean) {
            if (isSpacer) {
                card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                itemView.isClickable = false
                itemView.isLongClickable = false
            }
        }

        fun bind(item: SettingsItem) {
            val resolvedTitle = (item as? SwitchItem)?.titleText
                ?: itemView.context.getString(item.name)
            title.text = resolvedTitle
            // 致谢说明等纯正文条目使用空标题；彻底隐藏空 TextView，避免字体固有高度使正文视觉偏下。
            title.visibility = if (resolvedTitle.isBlank()) View.GONE else View.VISIBLE
            if (item is VerticalItem && item.usePrimaryTitleColor) {
                // 仓库名称与设置组题头共用 colorPrimary，动态取色和明暗主题下会同步变化。
                title.setTextColor(
                    MaterialColors.getColor(
                        itemView.context,
                        androidx.appcompat.R.attr.colorPrimary,
                        "settings item title",
                    ),
                )
            } else {
                // ViewHolder 可能曾绑定主题色标题，普通条目必须恢复布局声明的默认状态色。
                title.setTextColor(defaultTitleTextColors)
            }
            bindLeadingIcon(item)
            when (item) {
                is HorizontalItem -> bindValueRow(item.value, chevronVisible = item.showChevron)
                is NumberItem -> bindNumberRow(item)
                is SwitchItem -> bindSwitchRow(item)
                is VerticalItem -> bindInfoRow(item)
                else -> return
            }

            // trailing 元素显隐完成后统一处理可点击性：开关行整行切换，其余整行点击回调。
            itemView.setOnClickListener {
                if (restoreTriggered) {
                    // 长按已经打开恢复确认框，本次抬手不得再触发普通点击或切换。
                    restoreTriggered = false
                    return@setOnClickListener
                }
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val clicked = itemAt(p)
                    if (clicked is SwitchItem) {
                        switch.performClick()
                    } else {
                        onItemClickListener?.invoke(clicked, p)
                    }
                }
            }
            itemView.isClickable = true
            itemView.setOnLongClickListener(null)
            itemView.isLongClickable = false
            itemView.setOnTouchListener(::handleRestoreTouch)
            card.setOnTouchListener(::handleRestoreTouch)
            // Switch 是可点击子控件，单独接入同一计时器，保证按住开关本体同样能恢复该项。
            switch.setOnTouchListener(::handleRestoreTouch)

            // 分段形状：
            // 1. 组件 updateAppearance 记录 first/middle/last/single 状态（供状态层使用）；
            // 2. 容器圆角按位置用公开 API 显式设置——shape state-list 在当前
            //    AppTheme（非 Expressive 根主题）下不生效，且会被旧主题的
            //    shapeAppearanceMediumComponent=48px 覆盖成全圆角 pill（详见回退登记 F-04）。
            SettingsAppearance.applySegmentedCard(
                listItemLayout,
                card,
                item.sectionIndex,
                item.sectionCount,
            )
        }

        /** leading 图标：模型声明资源时显示，tint 走语义色。 */
        private fun bindLeadingIcon(item: SettingsItem) {
            if (item.leadingIconRes != 0) {
                icon.setImageResource(item.leadingIconRes)
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.GONE
            }
        }

        /** Value 行：值文本 + 可选箭头。 */
        private fun bindValueRow(text: String, chevronVisible: Boolean) {
            color.visibility = View.GONE
            switch.visibility = View.GONE
            if (text.isBlank()) {
                value.visibility = View.GONE
            } else {
                value.visibility = View.VISIBLE
                value.text = text
            }
            chevron.visibility = if (chevronVisible) View.VISIBLE else View.GONE
            support.visibility = View.GONE
        }

        /** Number 行：前缀 + 数值 + 单位拼接为 trailing 文本。 */
        private fun bindNumberRow(item: NumberItem) {
            color.visibility = View.GONE
            switch.visibility = View.GONE
            chevron.visibility = View.GONE
            support.visibility = View.GONE
            if (item.value > item.max || item.value < item.min) {
                val text = item.format?.invoke() ?: ""
                value.visibility = View.VISIBLE
                value.text = text.ifEmpty { itemView.context.getString(R.string.invalid_value) }
            } else {
                value.visibility = View.VISIBLE
                val sb = StringBuilder()
                if (item.prefix.isNotEmpty()) sb.append(item.prefix)
                sb.append(item.value)
                if (item.unit.isNotEmpty()) sb.append(item.unit)
                value.text = sb.toString()
            }
        }

        /** Toggle 行：MaterialSwitch 承载状态，整行点击等价切换。 */
        private fun bindSwitchRow(item: SwitchItem) {
            color.visibility = View.GONE
            value.visibility = View.GONE
            chevron.visibility = View.GONE
            switch.visibility = View.VISIBLE
            // 先解绑监听再设置状态，避免数据绑定触发用户回调
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.checked
            switch.setOnCheckedChangeListener { _, checked ->
                if (checked != item.checked) {
                    SwitchMotion.play(switch)
                    item.checked = checked
                    onItemCheckListener?.invoke(item, checked)
                }
            }
            if (item.description.isEmpty()) {
                support.visibility = View.GONE
            } else {
                support.visibility = View.VISIBLE
                support.text = item.description
            }
        }

        /** 信息行：上标题下说明（支持 Spanned 长文）；颜色行显示圆形色块。 */
        private fun bindInfoRow(item: VerticalItem) {
            value.visibility = View.GONE
            switch.visibility = View.GONE
            if (item.usePrimaryDescriptionColor) {
                // 与 item_setting_section_header 的 ?attr/colorPrimary 保持同一主题语义色。
                support.setTextColor(
                    MaterialColors.getColor(
                        itemView.context,
                        androidx.appcompat.R.attr.colorPrimary,
                        "selected import value",
                    ),
                )
            } else {
                // ViewHolder 可能曾显示过已选值，普通说明与未选择占位必须恢复原状态色。
                support.setTextColor(defaultSupportTextColors)
            }
            val swatchColor = item.colorHex?.let { hex ->
                runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
            }
            if (swatchColor != null) {
                // 颜色行：前景椭圆按色值着色，背景描边环保持 outline 色
                color.foregroundTintList = android.content.res.ColorStateList.valueOf(swatchColor)
                color.visibility = View.VISIBLE
            } else {
                color.visibility = View.GONE
            }
            // 颜色预览本身就是 trailing 操作提示，直接占用箭头槽位，避免同一行重复显示色块和箭头。
            chevron.visibility =
                if (item.showChevron && swatchColor == null) View.VISIBLE else View.GONE
            if (item.description.isEmpty()) {
                support.visibility = View.GONE
            } else {
                support.visibility = View.VISIBLE
                if (item.isSpanned) {
                    support.text = android.text.SpannedString.valueOf(item.description)
                } else {
                    support.text = item.description
                }
                // 纯展示行不限制行数，长标题/说明允许合理换行（2.0x 字体不截断）
                support.maxLines = Int.MAX_VALUE
            }
        }

        /** RecyclerView 回收行时清理尚未触发的延时任务。 */
        fun cancelPendingRestore() {
            pendingTouchView?.removeCallbacks(restoreRunnable)
            pendingTouchView = null
            restoreTriggered = false
        }

        /**
         * 处理设置行、卡片及 Switch 子控件共享的 1.6 秒按住手势。
         *
         * @return 恢复确认框已经弹出时消费抬手，防止随后触发普通点击或切换
         */
        private fun handleRestoreTouch(touchedView: View, event: MotionEvent): Boolean {
            if (onItemLongClickListener == null) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingTouchView?.removeCallbacks(restoreRunnable)
                    pendingTouchView = touchedView
                    downX = event.x
                    downY = event.y
                    restoreTriggered = false
                    touchedView.postDelayed(restoreRunnable, RESTORE_HOLD_DURATION_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > touchSlop ||
                        kotlin.math.abs(event.y - downY) > touchSlop
                    ) {
                        // 用户开始滚动列表时立即取消计时，避免滚动途中误弹确认框。
                        touchedView.removeCallbacks(restoreRunnable)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    touchedView.removeCallbacks(restoreRunnable)
                    pendingTouchView = null
                    if (restoreTriggered) {
                        restoreTriggered = false
                        return true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchedView.removeCallbacks(restoreRunnable)
                    pendingTouchView = null
                    restoreTriggered = false
                }
            }
            return false
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        /** 用户指定的恢复默认长按时长。 */
        const val RESTORE_HOLD_DURATION_MS = 1_600L
    }
}
