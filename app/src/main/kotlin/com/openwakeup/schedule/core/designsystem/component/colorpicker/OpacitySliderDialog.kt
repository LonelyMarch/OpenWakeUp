package com.openwakeup.schedule.core.designsystem.component.colorpicker

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.openwakeup.schedule.R
import kotlin.math.roundToInt

/**
 * 仅调整百分比的不透明度底部面板。
 *
 * 面板复用取色器的 [ArgbAlphaTrackView] 与 Material Slider 规格，但不会改变传入颜色的
 * RGB 通道。调用方只会收到 0 到 100 的不透明度百分比，适合课程格子、辅助文字等
 * 单独保存 alpha 百分比的设置项。
 */
class OpacitySliderDialog : BottomSheetDialogFragment() {

    /** 用户点击保存后返回最终的不透明度百分比。 */
    var onSaved: ((opacity: Int) -> Unit)? = null

    @StringRes
    private var titleRes: Int = R.string.color_opacity
    private var initialOpacity: Int = DEFAULT_OPACITY
    private var trackColor: Int = DEFAULT_TRACK_COLOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleRes = requireArguments().getInt(ARG_TITLE_RES, R.string.color_opacity)
        initialOpacity =
            requireArguments().getInt(ARG_INITIAL_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)
        trackColor = parseColor(
            requireArguments().getString(ARG_TRACK_COLOR).orEmpty(),
            DEFAULT_TRACK_COLOR,
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_opacity_slider, container, false)

    /**
     * 绑定标题、ARGB 轨道和 Material Slider，并只在用户确认时提交数值。
     *
     * 拖动过程中实时刷新百分比文案；取消面板不会修改调用方已经保存的配置。
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val valueLabel = view.findViewById<TextView>(R.id.tv_opacity_value)
        val slider = view.findViewById<Slider>(R.id.slider_opacity)
        val alphaTrack = view.findViewById<ArgbAlphaTrackView>(R.id.opacity_track)

        view.findViewById<TextView>(R.id.tv_opacity_title).setText(titleRes)
        // ARGB 轨道始终使用不透明 RGB；透明度只由 Slider 的横向位置表达。
        alphaTrack.color = trackColor
        slider.value = initialOpacity.toFloat()
        slider.setLabelFormatter { value -> "${value.roundToInt()}%" }

        /** 将当前滑块值按统一资源格式显示，避免不同设置页自行拼接百分号。 */
        fun updateValueLabel(value: Float) {
            valueLabel.text = getString(R.string.color_opacity_percent, value.roundToInt())
        }
        updateValueLabel(slider.value)
        slider.addOnChangeListener { _, value, _ -> updateValueLabel(value) }

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener {
            onSaved?.invoke(slider.value.roundToInt().coerceIn(0, 100))
            dismiss()
        }
    }

    /** 解析 RGB/ARGB 字符串，非法值回退为统一蓝色示例轨道。 */
    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        private const val ARG_TITLE_RES = "title_res"
        private const val ARG_INITIAL_OPACITY = "initial_opacity"
        private const val ARG_TRACK_COLOR = "track_color"
        private const val DEFAULT_OPACITY = 100
        private val DEFAULT_TRACK_COLOR = Color.parseColor("#2979FF")

        /**
         * 创建不透明度面板。
         *
         * @param titleRes 设置项标题资源
         * @param initialOpacity 初始不透明度百分比，会限制到 0..100
         * @param trackColorHex 用于演示透明变化的 RGB/ARGB 色值
         */
        fun newInstance(
            @StringRes titleRes: Int,
            initialOpacity: Int,
            trackColorHex: String,
        ): OpacitySliderDialog = OpacitySliderDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_TITLE_RES, titleRes)
                putInt(ARG_INITIAL_OPACITY, initialOpacity.coerceIn(0, 100))
                putString(ARG_TRACK_COLOR, trackColorHex)
            }
        }
    }
}
