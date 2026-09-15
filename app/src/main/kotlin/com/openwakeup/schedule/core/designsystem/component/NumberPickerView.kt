/*
 * 本文件基于 Carbs0126/NumberPickerView 的 Apache-2.0 代码修改。
 * 原始版权：Copyright 2016 Carbs.Wang。
 * OpenWakeUp 于 2026 年完成 Kotlin 化、样式属性适配及交互调整；完整来源见仓库 NOTICE.md。
 */
package com.openwakeup.schedule.core.designsystem.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Scroller
import com.openwakeup.schedule.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 滚轮选择器（基于 cn.carbswang/NumberPickerView 的交互语义实现）：
 * 所有滚动与数据刷新均在主线程完成，松手或惯性结束后自动吸附到最近项目；同时支持循环
 * （wrap）/线性模式、点击行选中、fling 惯性、选中行文字放大变色、上下分隔线与右侧 hint。
 *
 * 注意：[initAttr] 的属性索引按 R.styleable.NumberPickerView 的声明序硬编码，
 * 调整 attrs.xml 中属性声明顺序会破坏属性解析。
 */
class NumberPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnValueChangeListener {
        fun onValueChanged(picker: NumberPickerView, oldVal: Int, newVal: Int)
    }

    /** 供 xml 布局的 npv_TextArray/MaxValue/MinValue 组合初始化 */
    private var mAlterHint: String? = null
    private var mAlterTextArrayWithMeasureHint: Array<CharSequence>? = null
    private var mAlterTextArrayWithoutMeasureHint: Array<CharSequence>? = null
    private var mCurrDrawFirstItemIndex = 0
    private var mCurrDrawFirstItemY = 0
    private var mCurrDrawGlobalY = 0
    private var mDisplayedValues: Array<String> = arrayOf("0")
    private var mDividerColor = DEFAULT_DIVIDER_COLOR
    private var mDividerHeight = DEFAULT_DIVIDER_HEIGHT
    private var mDividerMarginL = 0
    private var mDividerMarginR = 0
    private var mEmptyItemHint: String? = null
    private var flagMayPress = false
    private var handlerInNewThread: Handler? = null
    private var mHasInit = false
    private var mHintText: String? = null
    private var mItemHeight = 0
    private var mItemPaddingHorizontal = 0
    private var mItemPaddingVertical = 0
    private var mMarginEndOfHint = 0
    private var mMarginStartOfHint = 0
    private var mMaxHeightOfDisplayedValues = 0
    private var mMaxShowIndex = -1
    private var mMaxValue = 0
    private var mMaxWidthOfAlterArrayWithMeasureHint = 0
    private var mMaxWidthOfAlterArrayWithoutMeasureHint = 0
    private var mMaxWidthOfDisplayedValues = 0
    private var mMinShowIndex = -1
    private var mMinValue = 0
    private var mMiniVelocityFling = 150
    private var mNotWrapLimitYBottom = 0
    private var mNotWrapLimitYTop = 0
    private var onValueChangeListener: OnValueChangeListener? = null
    private val mPaintDivider = Paint()
    private val mPaintHint = Paint()
    private val mPaintText = TextPaint()
    private var pendingWrapToLinear = false
    private var mPrevPickedIndex = 0
    private var respondChangeOnDetach = false
    private var mScaledTouchSlop = 8
    private var scrollState = SCROLL_STATE_IDLE
    private val mScroller = Scroller(context)
    private var showDivider = true
    private var mShownCount = DEFAULT_SHOWN_COUNT
    private var mTextColorHint = DEFAULT_DIVIDER_COLOR
    private var mTextColorNormal = DEFAULT_TEXT_COLOR_NORMAL
    private var mTextColorSelected = DEFAULT_DIVIDER_COLOR
    private var mTextEllipsize: String? = null
    private var mTextSizeHint = 0
    private var mTextSizeHintCenterYOffset = 0f
    private var mTextSizeNormal = 0
    private var mTextSizeNormalCenterYOffset = 0f
    private var mTextSizeSelected = 0
    private var mTextSizeSelectedCenterYOffset = 0f
    private val mTextWidthCache = ConcurrentHashMap<String, Int>()
    private var mVelocityTracker: VelocityTracker? = null
    private var mViewCenterX = 0f
    private var mViewHeight = 0
    private var mViewWidth = 0
    private var mWidthOfAlterHint = 0
    private var mWidthOfHintText = 0
    private var wrapSelectorWheel = true
    private var wrapSelectorWheelCheck = true
    private var downY = 0f
    private var downYGlobal = 0f
    private var currY = 0f
    private var dividerY0 = 0f
    private var dividerY1 = 0f

    init {
        mMiniVelocityFling = ViewConfiguration.get(getContext()).scaledMinimumFlingVelocity
        mScaledTouchSlop = ViewConfiguration.get(getContext()).scaledTouchSlop
        initAttr(context, attrs)
        mTextSizeNormal = sp2px(context, 14f)
        mTextSizeSelected = sp2px(context, 16f)
        mTextSizeHint = sp2px(context, 14f)
        mMarginStartOfHint = dp2px(context, 8f)
        mMarginEndOfHint = dp2px(context, 8f)
        mItemPaddingHorizontal = dp2px(context, 5f)
        mItemPaddingVertical = dp2px(context, 2f)
        initAttr(context, attrs)
        if (mTextSizeNormal == 0) mTextSizeNormal = sp2px(context, 14f)
        if (mTextSizeSelected == 0) mTextSizeSelected = sp2px(context, 16f)
        if (mTextSizeHint == 0) mTextSizeHint = sp2px(context, 14f)
        if (mMarginStartOfHint == 0) mMarginStartOfHint = dp2px(context, 8f)
        if (mMarginEndOfHint == 0) mMarginEndOfHint = dp2px(context, 8f)
        mPaintDivider.color = mDividerColor
        mPaintDivider.isAntiAlias = true
        mPaintDivider.style = Paint.Style.STROKE
        mPaintDivider.strokeWidth = mDividerHeight.toFloat()
        mPaintText.color = mTextColorNormal
        mPaintText.isAntiAlias = true
        mPaintText.textAlign = Paint.Align.CENTER
        // 多列滚轮的普通项和选中项都使用常规字重，仅通过颜色和字号体现选中状态。
        mPaintText.typeface = Typeface.DEFAULT
        mPaintHint.color = mTextColorHint
        mPaintHint.isAntiAlias = true
        mPaintHint.textAlign = Paint.Align.CENTER
        mPaintHint.textSize = mTextSizeHint.toFloat()
        if (mShownCount % 2 == 0) {
            mShownCount += 1
        }
        if (mMinShowIndex == -1 || mMaxShowIndex == -1) {
            updateValueForInit()
        }
        initHandler()
        mHasInit = true
    }

    private fun initAttr(context: Context, attrs: AttributeSet?) {
        if (attrs == null) return
        val a = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerView)
        val n = a.indexCount
        for (i in 0 until n) {
            when (val index = a.getIndex(i)) {
                0 -> mAlterHint = a.getString(index)
                1 -> mAlterTextArrayWithMeasureHint = a.getTextArray(index)
                2 -> mAlterTextArrayWithoutMeasureHint = a.getTextArray(index)
                3 -> mDividerColor = a.getColor(index, DEFAULT_DIVIDER_COLOR)
                4 -> mDividerHeight = a.getDimensionPixelSize(index, 2)
                5 -> mDividerMarginL = a.getDimensionPixelSize(index, 0)
                6 -> mDividerMarginR = a.getDimensionPixelSize(index, 0)
                7 -> mEmptyItemHint = a.getString(index)
                8 -> mHintText = a.getString(index)
                9 -> mItemPaddingHorizontal = a.getDimensionPixelSize(index, dp2px(context, 5f))
                10 -> mItemPaddingVertical = a.getDimensionPixelSize(index, dp2px(context, 2f))
                11 -> mMarginEndOfHint = a.getDimensionPixelSize(index, dp2px(context, 8f))
                12 -> mMarginStartOfHint = a.getDimensionPixelSize(index, dp2px(context, 8f))
                13 -> mMaxShowIndex = a.getInteger(index, 0)
                14 -> mMinShowIndex = a.getInteger(index, 0)
                16 -> respondChangeOnDetach = a.getBoolean(index, false)
                17 -> showDivider = a.getBoolean(index, true)
                18 -> mShownCount = a.getInt(index, 3)
                19 -> mDisplayedValues = (a.getTextArray(index) ?: arrayOf<CharSequence>("0"))
                    .map { it.toString() }.toTypedArray()

                20 -> mTextColorHint = a.getColor(index, DEFAULT_DIVIDER_COLOR)
                21 -> mTextColorNormal = a.getColor(index, DEFAULT_TEXT_COLOR_NORMAL)
                22 -> mTextColorSelected = a.getColor(index, DEFAULT_DIVIDER_COLOR)
                23 -> mTextEllipsize = a.getString(index)
                24 -> mTextSizeHint = a.getDimensionPixelSize(index, sp2px(context, 14f))
                25 -> mTextSizeNormal = a.getDimensionPixelSize(index, sp2px(context, 14f))
                26 -> mTextSizeSelected = a.getDimensionPixelSize(index, sp2px(context, 16f))
                27 -> wrapSelectorWheel = a.getBoolean(index, true)
            }
        }
        a.recycle()
    }

    /**
     * 初始化滚动调度器。
     *
     * 此前实现使用后台 `HandlerThread` 读取 `Scroller`，同时由主线程刷新日期滚轮的数据源。
     * 当月份变化导致日期数量从 31 天变为 30 天时，两个线程可能分别持有新旧索引范围，最终
     * 触发越界并使应用退出。View、Scroller 与其数据源都属于 UI 状态，因此这里将完整滚动
     * 状态收敛到主线程串行处理，从根源上消除数据刷新期间的竞争条件。
     */
    private fun initHandler() {
        handlerInNewThread = RefreshHandler(Looper.getMainLooper())
    }

    private inner class RefreshHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (message.what != HANDLER_WHAT_REFRESH) return

            // Scroller 的推进、绘制位置更新和数据回调全部在主线程内串行完成。
            if (!mScroller.isFinished) {
                if (scrollState == SCROLL_STATE_IDLE) onScrollStateChange(SCROLL_STATE_SCROLLING)
                if (mScroller.computeScrollOffset()) {
                    mCurrDrawGlobalY = limitY(mScroller.currY)
                    calculateFirstItemParameterByGlobalY()
                    postInvalidateOnAnimation()
                }
                scheduleRefresh(message.obj)
                return
            }

            // 手指松开或惯性停止时，将未对齐的偏移平滑吸附到距离最近的完整选项。
            if (mCurrDrawFirstItemY != 0) {
                if (scrollState == SCROLL_STATE_IDLE) onScrollStateChange(SCROLL_STATE_SCROLLING)
                val correction = if (mCurrDrawFirstItemY < -mItemHeight / 2) {
                    mItemHeight + mCurrDrawFirstItemY
                } else {
                    mCurrDrawFirstItemY
                }
                val targetY = limitY(mCurrDrawGlobalY + correction)
                val distance = targetY - mCurrDrawGlobalY
                if (distance != 0) {
                    val duration = (abs(distance) * SNAP_DURATION_MS / mItemHeight.coerceAtLeast(1))
                        .coerceIn(MIN_SNAP_DURATION_MS, SNAP_DURATION_MS)
                    mScroller.startScroll(0, mCurrDrawGlobalY, 0, distance, duration)
                    postInvalidateOnAnimation()
                    scheduleRefresh(message.obj)
                    return
                }
            }

            // 只有真正完成吸附后才通知业务层；日期列此时刷新天数不会打断尚未结束的动画。
            val willPick = getWillPickIndexByGlobalY(mCurrDrawGlobalY + mCurrDrawFirstItemY)
            respondPickedValueChanged(mPrevPickedIndex, willPick, message.obj)
        }
    }

    /** 以固定帧间隔安排下一次滚动或吸附计算，并去除同类重复消息。 */
    private fun scheduleRefresh(token: Any? = null) {
        handlerInNewThread?.removeMessages(HANDLER_WHAT_REFRESH)
        handlerInNewThread?.sendMessageDelayed(
            getMsg(HANDLER_WHAT_REFRESH, 0, 0, token),
            HANDLER_INTERVAL_REFRESH.toLong(),
        )
    }

    private fun getMsg(what: Int, arg1: Int, arg2: Int, obj: Any?): Message =
        Message.obtain().apply {
            this.what = what
            this.arg1 = arg1
            this.arg2 = arg2
            this.obj = obj
        }

    // ================= 对外 API =================

    fun setDisplayedValues(values: Array<String>) {
        if (values.isEmpty()) return
        stopMotionForDataChange()
        mDisplayedValues = values
        updateValueForInit()
        if (mHasInit) {
            mTextWidthCache.clear()
        }
    }

    fun setMaxValue(max: Int) {
        stopMotionForDataChange()
        mMaxValue = max.coerceAtLeast(mMinValue + 1)
        mMaxShowIndex = (mMaxValue - mMinValue - 1)
            .coerceIn(0, mDisplayedValues.lastIndex.coerceAtLeast(0))
        if (mMinShowIndex == -1 || mMaxShowIndex == -1) {
            updateValueForInit()
        } else if (mHasInit) {
            updateNonWrapLimits()
            assignValue(value.coerceIn(mMinValue, mMaxValue - 1))
        }
    }

    fun setMinValue(min: Int) {
        stopMotionForDataChange()
        mMinValue = min
        mMinShowIndex = 0
        if (mMaxValue <= mMinValue) {
            mMaxValue = mMinValue + mDisplayedValues.size
        }
        mMaxShowIndex = (mMaxValue - mMinValue - 1)
            .coerceIn(0, mDisplayedValues.lastIndex.coerceAtLeast(0))
        if (mMaxShowIndex == -1) {
            updateValueForInit()
        } else if (mHasInit) {
            updateNonWrapLimits()
            assignValue(mMinValue)
        }
    }

    fun setWrapSelectorWheel(wrap: Boolean) {
        if (wrapSelectorWheel == wrap) return
        val selectedValue = currentValue()
        stopMotionForDataChange()
        wrapSelectorWheel = wrap
        wrapSelectorWheelCheck = true
        if (!mHasInit) return

        // 切换循环方式只改变边界策略，不应改变用户当前看到的选中项。
        correctPositionByDefaultValue(
            (selectedValue - mMinValue).coerceIn(0, (getOneRecycleSize() - 1).coerceAtLeast(0)),
            wrap,
        )
        updateNonWrapLimits()
        postInvalidateOnAnimation()
    }

    /** 当前选中值（Kotlin 属性入口） */
    var value: Int
        get() = currentValue()
        set(v) = assignValue(v)

    private fun currentValue(): Int {
        val ret = getPickedIndexRelativeToRaw() + mMinValue
        return refineValueByLimit(
            ret,
            mMinValue,
            mMaxValue - 1,
            wrapSelectorWheel && wrapSelectorWheelCheck
        )
    }

    private fun assignValue(value: Int) {
        if (mDisplayedValues.isEmpty()) return
        stopMotionForDataChange()
        val safeValue = value.coerceIn(mMinValue, (mMaxValue - 1).coerceAtLeast(mMinValue))
        val index = safeValue - mMinValue
        val showCount = mShownCount / 2
        mCurrDrawFirstItemIndex = index - showCount
        mCurrDrawGlobalY = (mMinShowIndex + index - showCount) * mItemHeight
        correctPositionByDefaultValue(index, wrapSelectorWheel && wrapSelectorWheelCheck)
        mPrevPickedIndex = index
        postInvalidateOnAnimation()
    }

    fun setOnValueChangeListener(listener: OnValueChangeListener?) {
        onValueChangeListener = listener
    }

    // ================= 内部逻辑 =================

    private fun getOneRecycleSize(): Int = mMaxShowIndex - mMinShowIndex + 1

    private fun getPickedIndexRelativeToRaw(): Int {
        if (mItemHeight == 0) return 0
        val willPick = getWillPickIndexByGlobalY(mCurrDrawGlobalY + mCurrDrawFirstItemY)
        return willPick - mMinShowIndex
    }

    private fun getWillPickIndexByGlobalY(globalY: Int): Int {
        if (mItemHeight == 0) return 0
        val raw = globalY / mItemHeight + mShownCount / 2
        val indexByRaw = getIndexByRawIndex(
            raw,
            getOneRecycleSize(),
            wrapSelectorWheel && wrapSelectorWheelCheck
        )
        // 非循环滚轮处于数据刷新边界时也必须返回合法项目，不能让一次越界计算导致整个应用崩溃。
        return indexByRaw.coerceIn(0, (getOneRecycleSize() - 1).coerceAtLeast(0)) + mMinShowIndex
    }

    private fun getIndexByRawIndex(index: Int, size: Int, wrap: Boolean): Int {
        if (size <= 0) return 0
        if (!wrap) return index
        val ret = index % size
        return if (ret < 0) ret + size else ret
    }

    private fun updateValueForInit() {
        if (mDisplayedValues.isEmpty()) {
            mDisplayedValues = arrayOf("0")
        }
        mMinShowIndex = 0
        mMaxShowIndex = mDisplayedValues.size - 1
        mMaxValue = mMinValue + mDisplayedValues.size

        // 数据源改变后必须重新测量文本，但不能把已经由控件高度均分出的真实行高覆盖掉。
        mTextWidthCache.clear()
        mMaxWidthOfDisplayedValues = 0
        mMaxHeightOfDisplayedValues = 0
        measureTextWidths()
        mItemHeight = resolvedItemHeight()
        updateTextCenterOffsets()
        correctPositionByDefaultValue(mMinShowIndex, wrapSelectorWheel && wrapSelectorWheelCheck)
        updateNonWrapLimits()
        if (mHasInit) {
            requestLayout()
            postInvalidateOnAnimation()
        }
    }

    /**
     * 返回当前布局条件下应使用的稳定行高。
     *
     * 已完成布局时，行高始终由实际控件高度与可见行数决定；首次测量前才使用文字高度作为
     * `wrap_content` 的尺寸依据。日期列刷新 28/29/30/31 天数据时因此不会再突然变矮。
     */
    private fun resolvedItemHeight(): Int = if (mViewHeight > 0) {
        (mViewHeight / mShownCount.coerceAtLeast(1)).coerceAtLeast(1)
    } else {
        (mMaxHeightOfDisplayedValues + mItemPaddingVertical * 2).coerceAtLeast(1)
    }

    /** 数据源或数值范围变化前停止旧动画，防止旧范围的滚动回调污染新数据。 */
    private fun stopMotionForDataChange() {
        handlerInNewThread?.removeCallbacksAndMessages(null)
        if (!mScroller.isFinished) {
            mScroller.abortAnimation()
        }
        mCurrDrawFirstItemY = 0
        onScrollStateChange(SCROLL_STATE_IDLE)
    }

    /** 根据当前数据数量和行高同步非循环滚轮的可滚动边界。 */
    private fun updateNonWrapLimits() {
        val halfVisibleCount = mShownCount / 2

        // 第一个项目位于中心行时，首个绘制项目的索引为 -halfVisibleCount。
        mNotWrapLimitYBottom = -halfVisibleCount * mItemHeight

        // 最后一个项目位于中心行时，首个绘制项目索引为 itemCount-halfVisibleCount-1。
        mNotWrapLimitYTop = (getOneRecycleSize() - halfVisibleCount - 1)
            .coerceAtLeast(-halfVisibleCount) * mItemHeight
    }

    private fun measureTextWidths() {
        var max = 0
        var maxHeight = 0
        val paint = TextPaint(mPaintText)
        paint.textSize = mTextSizeSelected.toFloat()
        for (value in mDisplayedValues) {
            max = max(max, getTextWidth(value, paint))
            val metrics = paint.fontMetrics
            maxHeight = max(maxHeight, (abs(metrics.top) + abs(metrics.bottom)).toInt())
        }
        mMaxWidthOfDisplayedValues = max
        mMaxHeightOfDisplayedValues = maxHeight
        mMaxWidthOfAlterArrayWithMeasureHint =
            mAlterTextArrayWithMeasureHint?.let { getMaxWidthOfTextArray(it, paint) } ?: 0
        mMaxWidthOfAlterArrayWithoutMeasureHint =
            mAlterTextArrayWithoutMeasureHint?.let { getMaxWidthOfTextArray(it, paint) } ?: 0
        mWidthOfHintText = mHintText?.let { getTextWidth(it, mPaintHint) } ?: 0
        mWidthOfAlterHint = mAlterHint?.let { getTextWidth(it, mPaintHint) } ?: 0
    }

    private fun correctPositionByDefaultValue(position: Int, wrap: Boolean) {
        var firstIndex = position - (mShownCount - 1) / 2
        firstIndex = getIndexByRawIndex(firstIndex, getOneRecycleSize(), wrap)
        mCurrDrawFirstItemIndex = firstIndex
        if (mItemHeight == 0) {
            return
        }
        mCurrDrawGlobalY = mItemHeight * firstIndex
        calculateFirstItemParameterByGlobalY()
    }

    private fun calculateFirstItemParameterByGlobalY() {
        val index = floor(mCurrDrawGlobalY / mItemHeight.toDouble()).toInt()
        mCurrDrawFirstItemIndex = index
        mCurrDrawFirstItemY = -(mCurrDrawGlobalY - index * mItemHeight)
    }

    private fun respondPickedValueChanged(oldIndex: Int, newIndex: Int, obj: Any?) {
        onScrollStateChange(SCROLL_STATE_IDLE)
        val notify = oldIndex != newIndex && (obj !is Boolean || obj)
        if (notify) {
            onValueChangeListener?.onValueChanged(this, mMinValue + oldIndex, mMinValue + newIndex)
        }
        mPrevPickedIndex = newIndex
        if (pendingWrapToLinear) {
            pendingWrapToLinear = false
            internalSetWrapToLinear()
        }
    }

    private fun internalSetWrapToLinear() {
        correctPositionByDefaultValue(getPickedIndexRelativeToRaw() - mMinShowIndex, false)
        wrapSelectorWheel = false
        postInvalidate()
    }

    private fun onScrollStateChange(state: Int) {
        if (scrollState == state) return
        scrollState = state
    }

    private fun limitY(y: Int): Int {
        if (wrapSelectorWheel && wrapSelectorWheelCheck) return y
        val bottom = mNotWrapLimitYBottom
        val top = mNotWrapLimitYTop
        return if (y in bottom..top) y else if (y < bottom) bottom else top
    }

    private fun refineValueByLimit(value: Int, min: Int, max: Int, wrap: Boolean): Int {
        if (wrap) {
            if (value > max) {
                return (value - max) % getOneRecycleSize() + min - 1
            }
            return if (value < min) (value - min) % getOneRecycleSize() + max + 1 else value
        }
        if (value > max) return max
        return if (value < min) min else value
    }

    // ================= 测量/绘制/触摸 =================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mDisplayedValues.isEmpty()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (mMaxWidthOfDisplayedValues == 0) {
            measureTextWidths()
            mItemHeight = mMaxHeightOfDisplayedValues + mItemPaddingVertical * 2
        }
        setMeasuredDimension(measureWidth(widthMeasureSpec), measureHeight(heightMeasureSpec))
    }

    /**
     * 在控件获得最终尺寸后，按可见行数重新计算每一行的真实高度。
     *
     * `NumberPickerView` 在 XML 中通常会被指定一个明确高度，例如添加课程的时间滚轮为
     * 200dp。测量阶段得到的文字高度只适合处理 `wrap_content`，不能继续作为绘制与滚动时的
     * 行高；否则所有候选文字都会挤在控件顶部，而下半部分留下大片无效空白。因此在
     * `onSizeChanged` 中执行“控件高度 ÷ 可见行数”，并在行高变化后恢复
     * 当前选中值，避免设备旋转或弹窗重新布局时选择位置发生跳动。
     *
     * @param w 控件的新宽度，单位为像素。
     * @param h 控件的新高度，单位为像素。
     * @param oldw 控件变化前的宽度，单位为像素。
     * @param oldh 控件变化前的高度，单位为像素。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 必须在替换旧行高之前保存当前值，否则用新行高解释旧滚动偏移会选中错误的项目。
        val selectedValue = if (mItemHeight > 0 && getOneRecycleSize() > 0) {
            currentValue()
        } else {
            mMinValue
        }

        mViewWidth = w
        mViewHeight = h
        mViewCenterX = (w - paddingLeft - paddingRight) / 2f + paddingLeft

        // 固定高度的滚轮应让所有可见行均分整个纵向空间。
        // 这样 200dp、5 行的时间选择器会获得约 40dp 行高，日期选择器的 3 行则约为 66dp。
        mItemHeight = resolvedItemHeight()

        computeDividerY()
        updateNonWrapLimits()

        // 行高改变后重新定位到原选中项，确保分割线、文字与触摸吸附区域保持完全一致。
        val selectedIndex = (selectedValue - mMinValue)
            .coerceIn(0, (getOneRecycleSize() - 1).coerceAtLeast(0))
        correctPositionByDefaultValue(
            selectedIndex,
            wrapSelectorWheel && wrapSelectorWheelCheck
        )
    }

    private fun computeDividerY() {
        dividerY0 = (mShownCount / 2).toFloat() * mItemHeight + paddingTop
        dividerY1 = dividerY0 + mItemHeight
    }

    private fun measureWidth(spec: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        if (mode == MeasureSpec.EXACTLY) return size
        val padding = paddingLeft + paddingRight
        val hintWidth = max(mWidthOfHintText, mWidthOfAlterHint)
        val hintTotal = (if (hintWidth != 0) mMarginStartOfHint else 0) + hintWidth +
                (if (hintWidth == 0) 0 else mMarginEndOfHint) + mItemPaddingHorizontal * 2
        val content = max(
            mMaxWidthOfDisplayedValues,
            maxOf(
                mMaxWidthOfAlterArrayWithMeasureHint,
                mMaxWidthOfAlterArrayWithoutMeasureHint
            ) + hintTotal * 2
        )
        val ret = padding + content
        return if (mode == MeasureSpec.AT_MOST) min(ret, size) else ret
    }

    private fun measureHeight(spec: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        if (mode == MeasureSpec.EXACTLY) return size
        val content =
            paddingTop + paddingBottom + mShownCount * (mMaxHeightOfDisplayedValues + mItemPaddingVertical * 2)
        return if (mode == MeasureSpec.AT_MOST) min(content, size) else content
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawContent(canvas)
        drawLine(canvas)
        drawHint(canvas)
    }

    /**
     * 绘制滚轮中的所有可见项目。
     *
     * 每一行的选中比例只由该行中心到选区中心的距离决定：完全居中为 1，相距一行及以上为
     * 0。滚动到两行中间时，两行各获得约 0.5 的比例，因此颜色和字号会连续交接，不会因为
     * 插值系数越界而出现 ARGB 通道溢出、字体闪白或字号突变。
     */
    private fun drawContent(canvas: Canvas) {
        val selectedCenterY = (dividerY0 + dividerY1) / 2f
        for (i in 0 until mShownCount + 1) {
            val y = mCurrDrawFirstItemY + mItemHeight * i
            val indexByRaw = getIndexByRawIndex(
                mCurrDrawFirstItemIndex + i,
                getOneRecycleSize(),
                wrapSelectorWheel && wrapSelectorWheelCheck
            )
            val itemCenterY = y + mItemHeight / 2f
            val selectedFraction = (
                    1f - abs(itemCenterY - selectedCenterY) / mItemHeight.coerceAtLeast(1)
                    ).coerceIn(0f, 1f)

            mPaintText.color = getEvaluateColor(
                selectedFraction,
                mTextColorNormal,
                mTextColorSelected,
            )
            mPaintText.textSize = getEvaluateSize(
                selectedFraction,
                mTextSizeNormal.toFloat(),
                mTextSizeSelected.toFloat(),
            )
            val centerYOffset = getEvaluateSize(
                selectedFraction,
                mTextSizeNormalCenterYOffset,
                mTextSizeSelectedCenterYOffset,
            )

            val displayedIndex = indexByRaw + mMinShowIndex
            val rawText = mDisplayedValues.getOrNull(displayedIndex) ?: mEmptyItemHint
            if (!rawText.isNullOrEmpty()) {
                val text = if (mTextEllipsize != null) {
                    TextUtils.ellipsize(
                        rawText,
                        mPaintText,
                        (width - mItemPaddingHorizontal * 2).toFloat(),
                        getEllipsizeType(),
                    ).toString()
                } else {
                    rawText
                }
                canvas.drawText(text, mViewCenterX, itemCenterY + centerYOffset, mPaintText)
            }
        }
    }

    private fun drawLine(canvas: Canvas) {
        if (!showDivider) return
        canvas.drawLine(
            paddingLeft + mDividerMarginL.toFloat(),
            dividerY0,
            mViewWidth - paddingRight - mDividerMarginR.toFloat(),
            dividerY0,
            mPaintDivider
        )
        canvas.drawLine(
            paddingLeft + mDividerMarginL.toFloat(),
            dividerY1,
            mViewWidth - paddingRight - mDividerMarginR.toFloat(),
            dividerY1,
            mPaintDivider
        )
    }

    private fun drawHint(canvas: Canvas) {
        if (TextUtils.isEmpty(mHintText)) return
        val hintWidth = max(mWidthOfHintText, mWidthOfAlterHint)
        canvas.drawText(
            mHintText!!,
            mViewCenterX + (mMaxWidthOfDisplayedValues + hintWidth) / 2f + mMarginStartOfHint,
            (dividerY0 + dividerY1) / 2f + mTextSizeHintCenterYOffset,
            mPaintHint
        )
    }

    private fun getEllipsizeType(): TextUtils.TruncateAt = when (mTextEllipsize) {
        "middle" -> TextUtils.TruncateAt.MIDDLE
        "end" -> TextUtils.TruncateAt.END
        "start" -> TextUtils.TruncateAt.START
        else -> throw IllegalArgumentException("Illegal text ellipsize type.")
    }

    private fun getEvaluateColor(f: Float, from: Int, to: Int): Int {
        // 即使未来调用方传入异常比例，也不能允许颜色通道发生越界回绕。
        val fraction = f.coerceIn(0f, 1f)
        val a = (from ushr 24) + (((to ushr 24) - (from ushr 24)) * fraction).toInt()
        val r =
            ((from shr 16) and 0xFF) + ((((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * fraction).toInt()
        val g =
            ((from shr 8) and 0xFF) + ((((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * fraction).toInt()
        val b = (from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * fraction).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 在正常与选中尺寸之间执行有界线性插值。 */
    private fun getEvaluateSize(f: Float, from: Float, to: Float): Float =
        from + (to - from) * f.coerceIn(0f, 1f)

    /** 分别使用正常、选中字号计算基线偏移，避免字号过渡时文字上下跳动。 */
    private fun updateTextCenterOffsets() {
        mPaintText.textSize = mTextSizeNormal.toFloat()
        mTextSizeNormalCenterYOffset = getTextCenterYOffset(mPaintText.fontMetrics)
        mPaintText.textSize = mTextSizeSelected.toFloat()
        mTextSizeSelectedCenterYOffset = getTextCenterYOffset(mPaintText.fontMetrics)
        mPaintText.textSize = mTextSizeNormal.toFloat()

        mPaintHint.textSize = mTextSizeHint.toFloat()
        mTextSizeHintCenterYOffset = getTextCenterYOffset(mPaintHint.fontMetrics)
    }

    private fun getTextCenterYOffset(metrics: Paint.FontMetrics): Float =
        abs(metrics.top + metrics.bottom) / 2f

    private fun getTextWidth(text: CharSequence, paint: Paint): Int {
        if (TextUtils.isEmpty(text)) return 0
        val s = text.toString()
        mTextWidthCache[s]?.let { return it }
        val width = (paint.measureText(s) + 0.5f).toInt()
        mTextWidthCache[s] = width
        return width
    }

    private fun getMaxWidthOfTextArray(array: Array<CharSequence>, paint: Paint): Int {
        var max = 0
        for (c in array) {
            max = max(max, getTextWidth(c, paint))
        }
        return max
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain()
        }
        mVelocityTracker?.addMovement(event)
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!mScroller.isFinished) {
                    mScroller.abortAnimation()
                }
                downY = event.y
                currY = downY
                downYGlobal = mCurrDrawGlobalY.toFloat()
                flagMayPress = true
                handlerInNewThread?.removeMessages(HANDLER_WHAT_REFRESH)
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = downY - event.y
                if (abs(delta) >= mScaledTouchSlop) {
                    flagMayPress = false
                }
                currY = event.y
                mCurrDrawGlobalY = limitY((downYGlobal + delta).roundToInt())
                calculateFirstItemParameterByGlobalY()
                onScrollStateChange(SCROLL_STATE_SCROLLING)
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_UP -> {
                mVelocityTracker?.computeCurrentVelocity(1000, 8000f)
                val velocityY = mVelocityTracker?.yVelocity ?: 0f
                if (abs(velocityY) > mMiniVelocityFling) {
                    mScroller.fling(
                        0,
                        mCurrDrawGlobalY,
                        0,
                        (-velocityY).roundToInt(),
                        0,
                        0,
                        if (wrapSelectorWheel && wrapSelectorWheelCheck) Int.MIN_VALUE else mNotWrapLimitYBottom,
                        if (wrapSelectorWheel && wrapSelectorWheelCheck) Int.MAX_VALUE else mNotWrapLimitYTop
                    )
                    // 启动逐帧刷新；不能再用零距离 startScroll 覆盖刚创建的惯性动画。
                    postInvalidateOnAnimation()
                    handlerInNewThread?.sendEmptyMessageDelayed(
                        HANDLER_WHAT_REFRESH,
                        HANDLER_INTERVAL_REFRESH.toLong()
                    )
                } else if (flagMayPress) {
                    performClick()
                    click(event)
                } else {
                    scheduleRefresh()
                }
                releaseVelocityTracker()
            }

            MotionEvent.ACTION_CANCEL -> {
                flagMayPress = false
                scheduleRefresh()
                releaseVelocityTracker()
            }
        }
        return true
    }

    /** 向无障碍服务报告滚轮的一次轻点；具体选项仍由 [click] 根据纵坐标计算。 */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun click(event: MotionEvent) {
        val y = event.y
        for (i in 0 until mShownCount) {
            if (mItemHeight * i <= y && y < mItemHeight * (i + 1)) {
                clickItem(i)
                return
            }
        }
    }

    private fun clickItem(position: Int) {
        if (position < 0 || position >= mShownCount) return
        scrollByIndexSmoothly(position - mShownCount / 2)
    }

    private fun scrollByIndexSmoothly(indexDelta: Int) {
        val newGlobalY = limitY(mCurrDrawGlobalY + mItemHeight * indexDelta)
        mScroller.startScroll(0, mCurrDrawGlobalY, 0, newGlobalY - mCurrDrawGlobalY, 300)
        postInvalidateOnAnimation()
        scheduleRefresh()
    }

    private fun releaseVelocityTracker() {
        mVelocityTracker?.clear()
        mVelocityTracker?.recycle()
        mVelocityTracker = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (respondChangeOnDetach) {
            val willPick = getWillPickIndexByGlobalY(mCurrDrawGlobalY + mCurrDrawFirstItemY)
            if (willPick != mPrevPickedIndex) {
                onValueChangeListener?.onValueChanged(
                    this,
                    mMinValue + mPrevPickedIndex,
                    mMinValue + willPick
                )
            }
        }
        handlerInNewThread?.removeCallbacksAndMessages(null)
        if (!mScroller.isFinished) {
            mScroller.abortAnimation()
        }
        releaseVelocityTracker()
    }

    private fun dp2px(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 按当前字体缩放配置把 sp 转换为像素，并兼容 Android 14 起的非线性字体缩放。 */
    private fun sp2px(context: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        v,
        context.resources.displayMetrics,
    ).roundToInt()

    companion object {
        private const val DEFAULT_DIVIDER_COLOR = -0x6A005D // #FF9597A3 分隔线默认色
        private const val DEFAULT_TEXT_COLOR_NORMAL = -13421773
        private const val DEFAULT_SHOWN_COUNT = 3
        private const val DEFAULT_DIVIDER_HEIGHT = 2
        private const val HANDLER_WHAT_REFRESH = 1
        private const val HANDLER_INTERVAL_REFRESH = 16
        private const val MIN_SNAP_DURATION_MS = 90
        private const val SNAP_DURATION_MS = 180
        private const val SCROLL_STATE_IDLE = 0
        private const val SCROLL_STATE_SCROLLING = 1
    }
}
