package com.openwakeup.schedule.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openwakeup.schedule.R

/** 设置项恢复默认值时共用的居中确认弹窗。 */
object SettingsRestoreDialog {

    /**
     * 展示恢复确认，只有用户点击“恢复默认”后才执行回调。
     *
     * @param context 当前设置页上下文
     * @param itemNameRes 设置项标题资源
     * @param onConfirm 用户确认后的恢复操作
     */
    fun show(context: Context, @StringRes itemNameRes: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.restore_default_title)
            .setMessage(
                context.getString(
                    R.string.restore_default_message,
                    context.getString(itemNameRes)
                )
            )
            .setPositiveButton(R.string.restore_default) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
