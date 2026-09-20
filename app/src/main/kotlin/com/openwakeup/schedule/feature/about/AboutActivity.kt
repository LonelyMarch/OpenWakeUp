package com.openwakeup.schedule.feature.about

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.util.ExternalWebLinkLauncher
import com.openwakeup.schedule.databinding.ActivityAboutBinding
import com.openwakeup.schedule.feature.settings.CategoryItem
import com.openwakeup.schedule.feature.settings.HeaderItem
import com.openwakeup.schedule.feature.settings.HorizontalItem
import com.openwakeup.schedule.feature.settings.SettingsItem
import com.openwakeup.schedule.feature.settings.SettingsListAdapter
import com.openwakeup.schedule.feature.settings.VerticalItem

/**
 * 关于页。
 *
 * 应用标识保留在顶部，版本、GitHub 入口、致谢与开源许可全部改用和全局设置一致的
 * [SettingsListAdapter] 分组卡片样式。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = SettingsListAdapter().apply {
            onItemClickListener = ::onItemClicked
            submit(buildItems())
        }
    }

    /** 构建版本、GitHub 入口、致谢和开源许可分组。 */
    private fun buildItems(): List<CategoryItem> = listOf(
        CategoryItem(
            listOf(
                HeaderItem(R.string.setting_blank),
                HorizontalItem(R.string.version, packageInfo.versionName.orEmpty()),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.contact_us),
                HorizontalItem(
                    R.string.contact_github,
                    "LonelyMarch/OpenWakeUp",
                    leadingIconRes = R.drawable.ms_code_24,
                    showChevron = true,
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.acknowledgements),
                VerticalItem(
                    R.string.setting_blank,
                    getString(R.string.acknowledgements_intro),
                ),
                VerticalItem(
                    R.string.credit_wakeup_kotlin,
                    getString(R.string.credit_wakeup_kotlin_desc),
                    showChevron = true,
                    usePrimaryTitleColor = true,
                ),
                VerticalItem(
                    R.string.credit_wakeup_java,
                    getString(R.string.credit_wakeup_java_desc),
                    showChevron = true,
                    usePrimaryTitleColor = true,
                ),
                VerticalItem(
                    R.string.credit_wakeup_bupt,
                    getString(R.string.credit_wakeup_bupt_desc),
                    showChevron = true,
                    usePrimaryTitleColor = true,
                ),
                VerticalItem(
                    R.string.credit_course_table_ics_formatter,
                    getString(R.string.credit_course_table_ics_formatter_desc),
                    showChevron = true,
                    usePrimaryTitleColor = true,
                ),
                VerticalItem(
                    R.string.credit_course_adapter,
                    getString(R.string.credit_course_adapter_desc),
                    showChevron = true,
                    usePrimaryTitleColor = true,
                ),
                VerticalItem(
                    R.string.credit_original_author,
                    getString(R.string.credit_original_author_desc),
                ),
                VerticalItem(
                    R.string.credit_community,
                    getString(R.string.credit_community_desc),
                ),
            ),
        ),
        CategoryItem(
            listOf(
                HeaderItem(R.string.open_source_license),
                VerticalItem(
                    R.string.open_source_license_name,
                    getString(R.string.open_source_license_body),
                    showChevron = true,
                ),
            ),
        ),
    )

    /**
     * 处理关于页可点击条目。
     *
     * @param item 用户点击的设置项
     * @param ignoredPosition 适配器位置，本页无需使用
     */
    private fun onItemClicked(item: SettingsItem, ignoredPosition: Int) {
        val url = when (item.name) {
            R.string.contact_github -> GITHUB_REPOSITORY_URL
            R.string.credit_wakeup_kotlin -> WAKEUP_KOTLIN_URL
            R.string.credit_wakeup_java -> WAKEUP_JAVA_URL
            R.string.credit_wakeup_bupt -> WAKEUP_BUPT_URL
            R.string.credit_course_table_ics_formatter -> COURSE_TABLE_ICS_FORMATTER_URL
            R.string.credit_course_adapter -> COURSE_ADAPTER_URL
            R.string.open_source_license_name -> LICENSE_URL
            else -> return
        }
        ExternalWebLinkLauncher.open(this, url) {
            Toast.makeText(this, R.string.github_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private val packageInfo get() = packageManager.getPackageInfo(packageName, 0)

    private companion object {
        /** 关于页 GitHub 条目指向当前 Kotlin 重构项目。 */
        const val GITHUB_REPOSITORY_URL = "https://github.com/LonelyMarch/OpenWakeUp"

        /** README 致谢部分列出的 WakeUp Kotlin 开源项目。 */
        const val WAKEUP_KOTLIN_URL = "https://github.com/ThaiCao/WakeupSchedule_Kotlin"

        /** README 致谢部分列出的早期 WakeUp Java 开源项目。 */
        const val WAKEUP_JAVA_URL = "https://github.com/YZune/WakeUpSchedule"

        /** README 致谢部分列出的北邮教务适配项目。 */
        const val WAKEUP_BUPT_URL = "https://github.com/xianfei/WakeupSchedule_BUPT"

        /** 为浏览器端 ICS 课表生成方案提供参考的开源项目。 */
        const val COURSE_TABLE_ICS_FORMATTER_URL =
            "https://github.com/wtlyu/Course-Table-ICS-Formatter"

        /** CourseAdapter 原生 Parser 迁移参考仓库。 */
        const val COURSE_ADAPTER_URL = "https://github.com/VenomBat/CourseAdapter"

        /** 当前项目仓库中的 GNU AGPL v3 许可证文件。 */
        const val LICENSE_URL = "$GITHUB_REPOSITORY_URL/blob/dev/LICENSE"
    }
}
