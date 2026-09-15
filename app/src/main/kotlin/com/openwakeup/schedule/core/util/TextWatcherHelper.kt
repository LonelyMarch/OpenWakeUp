package com.openwakeup.schedule.core.util

import android.text.Editable
import android.text.TextWatcher

/**
 * 简化 TextWatcher：仅回调文本变化。
 */
class TextWatcherHelper(private val onChanged: (String?) -> Unit) : TextWatcher {

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) = onChanged(s?.toString())
}
