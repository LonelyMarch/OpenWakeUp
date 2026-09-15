package com.openwakeup.schedule.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import com.openwakeup.schedule.R
import com.openwakeup.schedule.data.schedule.ScheduleRepository
import com.openwakeup.schedule.feature.schedule.ScheduleActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 启动页：LAUNCHER 入口，展示启动画面；首次启动默认建表后进入主界面。
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动页背景同样绘制到状态栏区域，避免进入主页前短暂出现异色顶栏。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_splash)
        // 原生 Activity 无 lifecycleScope，一次性主线程协程
        CoroutineScope(Dispatchers.Main).launch {
            val repo = ScheduleRepository(this@SplashActivity)
            // 首次启动：默认建表（开学日期取本周一）
            if (repo.currentTableId() == 0L) {
                val monday = LocalDate.now().with(DayOfWeek.MONDAY)
                repo.createTable(getString(R.string.default_table_name), monday.toString())
            }
            startActivity(Intent(this@SplashActivity, ScheduleActivity::class.java))
            finish()
        }
    }
}
