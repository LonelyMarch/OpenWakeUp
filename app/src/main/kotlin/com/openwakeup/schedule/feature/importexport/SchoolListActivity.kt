package com.openwakeup.schedule.feature.importexport

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.shape.ShapeAppearanceModel
import com.openwakeup.schedule.R
import com.openwakeup.schedule.core.util.ExternalWebLinkLauncher
import com.openwakeup.schedule.data.school.SchoolRepository
import com.openwakeup.schedule.databinding.ActivitySchoolListBinding
import com.openwakeup.schedule.databinding.ItemSchoolBinding

/**
 * “从教务导入”的学校选择页。
 *
 * 页面按照 OpenWakeUp 的结构展示学校，而不是让用户理解内部教务 type：
 * - 使用“专科及本科 / 研究生 / 通用教务”三个分类；
 * - 学校按拼音首字母分组，并提供右侧字母快速索引；
 * - 顶部搜索直接匹配学校名称；
 * - 点击学校时仍把学校对应的 type 与教务网址传给现有登录抓取流程。
 */
class SchoolListActivity : AppCompatActivity() {

    private val schoolRepository by lazy { SchoolRepository(this) }
    private lateinit var binding: ActivitySchoolListBinding
    private lateinit var layoutManager: LinearLayoutManager
    private val adapter = SchoolAdapter()

    private var selectedCategory = SchoolCategory.UNDERGRADUATE

    /** 完整学校表按分类规则切分，避免每次输入搜索词时重复处理 3570 条原始记录。 */
    private val schoolsByCategory: Map<SchoolCategory, List<SchoolRepository.School>> by lazy {
        buildCategoryLists(schoolRepository.all())
    }

    /**
     * 右侧索引只保留真实学校的 A-Z 首字母。
     *
     * “★”是帮助提示、“通”是通用教务分组，两者都不是学校首字母，因此不参与快速索引。
     */
    private val indexLetters: List<String> by lazy {
        schoolRepository.all()
            .asSequence()
            .map { school -> normalizeSortKey(school.sortKey) }
            .filter { sortKey -> sortKey != COMMON_SORT_KEY && sortKey != FAVORITE_SORT_KEY }
            .distinct()
            .sorted()
            .toList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySchoolListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 输入法按产品要求只遮挡内容，不能改变列表和右侧首字母索引的页面高度；
            // 因此这里只处理系统栏安全区，不再把 IME 高度写入根布局的底部内边距。
            view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        layoutManager = LinearLayoutManager(this)
        binding.recycler.layoutManager = layoutManager
        binding.recycler.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }

        configureSearch()
        configureCategories()
        binding.schoolIndex.setLetters(indexLetters, ::scrollToLetter)
        refreshVisibleSchools()
    }

    /**
     * 配置内嵌学校搜索框。
     *
     * 输入内容不会过滤或替换列表，只把现有列表滚动到第一个名称匹配项，因此键盘弹出时
     * 分类、学校列表和右侧索引仍保持在同一页面中。
     */
    private fun configureSearch() {
        binding.searchInput.addTextChangedListener {
            scrollToSearchResult()
        }
    }

    /** 配置 M3E 三项单选按钮组，并在切换分类时保留当前搜索关键字。 */
    private fun configureCategories() {
        binding.categoryGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            // ToggleGroup 在切换时会先取消旧按钮；只响应新按钮进入选中态，避免列表刷新两次。
            if (isChecked) {
                selectedCategory = when (checkedId) {
                    R.id.schoolCategoryGraduate -> SchoolCategory.GRADUATE
                    R.id.schoolCategoryCommon -> SchoolCategory.COMMON
                    else -> SchoolCategory.UNDERGRADUATE
                }
                refreshVisibleSchools()
                binding.recycler.scrollToPosition(0)
            }
        }
    }

    /**
     * 按当前分类刷新完整列表；搜索只改变滚动位置，不改变这里提交的数据。
     */
    private fun refreshVisibleSchools() {
        val categorySchools = schoolsByCategory[selectedCategory].orEmpty()
        val source = if (selectedCategory == SchoolCategory.COMMON) {
            // “如何选择教务类型”提示仅属于通用教务入口，避免在本科/研究生学校列表重复占位。
            listOf(createCommonHelpItem()) + categorySchools
        } else {
            categorySchools
        }
        adapter.submit(source)
        binding.tvEmpty.isVisible = source.isEmpty()
        // 通用教务没有 A-Z 学校分组，隐藏右侧索引；本科与研究生分类继续保留快速定位。
        binding.schoolIndex.isVisible = source.isNotEmpty() &&
                selectedCategory != SchoolCategory.COMMON
        // 分类切换后保留输入内容，并在新分类的完整列表中重新定位。
        binding.recycler.post { scrollToSearchResult() }
    }

    /** 根据输入内容把列表滚动到第一个名称匹配项；空输入保持当前位置。 */
    private fun scrollToSearchResult() {
        val keyword = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) return
        val position = adapter.positionForName(keyword)
        if (position != RecyclerView.NO_POSITION) {
            layoutManager.scrollToPositionWithOffset(position, 0)
        }
    }

    /**
     * 把学校表分为本科、研究生和通用教务三组。
     *
     * 原数据用 sortKey=`0` 标记通用类型，用学校名中的“研究生”标记研究生入口；展示时
     * 分别改为“通”分组和不带研究生后缀的学校名。
     */
    private fun buildCategoryLists(
        schools: List<SchoolRepository.School>,
    ): Map<SchoolCategory, List<SchoolRepository.School>> {
        val result = SchoolCategory.entries.associateWith {
            mutableListOf<SchoolRepository.School>()
        }
        schools.forEach { school ->
            when {
                school.sortKey == RAW_COMMON_SORT_KEY -> {
                    result.getValue(SchoolCategory.COMMON).add(
                        school.copy(sortKey = COMMON_SORT_KEY),
                    )
                }

                school.name.contains(GRADUATE_MARKER) -> {
                    result.getValue(SchoolCategory.GRADUATE).add(
                        school.copy(
                            name = graduateSchoolDisplayName(school.name),
                            sortKey = normalizeSortKey(school.sortKey),
                        ),
                    )
                }

                else -> {
                    result.getValue(SchoolCategory.UNDERGRADUATE).add(
                        school.copy(sortKey = normalizeSortKey(school.sortKey)),
                    )
                }
            }
        }

        return result.mapValues { (_, entries) ->
            entries.sortedWith(compareBy<SchoolRepository.School>({ it.sortKey }, { it.name }))
        }
    }

    /** 创建仅在“通用教务”分类顶部展示的星号帮助项。 */
    private fun createCommonHelpItem(): SchoolRepository.School =
        SchoolRepository.School(
            sortKey = FAVORITE_SORT_KEY,
            name = getString(R.string.school_common_help),
            url = SCHOOL_HELP_URL,
            type = HELP_TYPE,
        )

    /** 响应右侧字母索引；当前分类没有该字母时定位到之前最近的有效分组。 */
    private fun scrollToLetter(letter: String) {
        val position = adapter.positionForSortKey(letter, indexLetters)
        if (position >= 0) {
            layoutManager.scrollToPositionWithOffset(position, 0)
        }
    }

    /** 打开被选择学校对应的导入入口。 */
    private fun openSchool(school: SchoolRepository.School) {
        if (school.type == HELP_TYPE) {
            ExternalWebLinkLauncher.open(this, school.url) {
                Toast.makeText(this, R.string.school_help_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (intent.getBooleanExtra(EXTRA_SELECTION_ONLY, false)) {
            // HTML 文件导入只需要学校名和解析器类型，不进入 WebView 登录页。
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(RESULT_SCHOOL_NAME, school.name)
                    .putExtra(RESULT_PARSER_TYPE, school.type)
                    .putExtra(
                        RESULT_SELECTION_LABEL,
                        schoolTypeLabel(school).takeIf { it.isNotBlank() }
                            ?.let { typeLabel -> "${school.name} · $typeLabel" }
                            ?: school.name,
                    ),
            )
            finish()
            return
        }

        startActivity(
            Intent(this, WebLoginActivity::class.java)
                .putExtra(WebLoginActivity.EXTRA_TYPE, school.type)
                .putExtra(WebLoginActivity.EXTRA_URL, school.url)
                .putExtra(WebLoginActivity.EXTRA_NAME, school.name),
        )
    }

    /** 把缺失或数字形式的分组键归入通用教务分组。 */
    private fun normalizeSortKey(sortKey: String): String = when {
        sortKey.isBlank() || sortKey == RAW_COMMON_SORT_KEY -> COMMON_SORT_KEY
        else -> sortKey.uppercase()
    }

    /**
     * 移除学校表中不同年代使用过的研究生后缀，只在“研究生”标签中展示学校主体名称。
     */
    private fun graduateSchoolDisplayName(name: String): String = name
        .replace(" - 研究生", "")
        .replace("-研究生", "")
        .replace("（研究生）", "")
        .trim()

    /** RecyclerView 适配器：负责分组标题、学校名和辅助教务名称的显示。 */
    private inner class SchoolAdapter : RecyclerView.Adapter<SchoolViewHolder>() {
        private val items = mutableListOf<SchoolRepository.School>()

        /** 替换当前可见列表；学校搜索结果规模有限，整表刷新可避免错位动画。 */
        fun submit(schools: List<SchoolRepository.School>) {
            items.clear()
            items.addAll(schools)
            notifyDataSetChanged()
        }

        /**
         * 查询字母对应的位置；若当前分类缺少该字母，则向前寻找最近存在的分组。
         */
        fun positionForSortKey(letter: String, allLetters: List<String>): Int {
            val exactPosition = items.indexOfFirst { school -> school.sortKey == letter }
            if (exactPosition >= 0) return exactPosition

            val requestedIndex = allLetters.indexOf(letter)
            if (requestedIndex < 0) return RecyclerView.NO_POSITION
            for (index in requestedIndex - 1 downTo 0) {
                val fallbackPosition = items.indexOfFirst { school ->
                    school.sortKey == allLetters[index]
                }
                if (fallbackPosition >= 0) return fallbackPosition
            }
            return if (items.isEmpty()) RecyclerView.NO_POSITION else 0
        }

        /** 返回第一个包含搜索文字的学校位置；找不到时不移动当前列表。 */
        fun positionForName(keyword: String): Int = items.indexOfFirst { school ->
            school.name.contains(keyword, ignoreCase = true)
        }.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SchoolViewHolder {
            val binding = ItemSchoolBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return SchoolViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: SchoolViewHolder, position: Int) {
            val school = items[position]
            val isFirstOfGroup = position == 0 || items[position - 1].sortKey != school.sortKey
            val isLastOfGroup =
                position == items.lastIndex || items[position + 1].sortKey != school.sortKey
            holder.binding.tvHeader.isVisible = isFirstOfGroup
            holder.binding.tvHeader.text = school.sortKey
            holder.binding.tvName.text = school.name
            val typeLabel = schoolTypeLabel(school)
            holder.binding.tvType.text = typeLabel
            holder.binding.tvType.isVisible = typeLabel.isNotBlank()
            bindSegmentedAppearance(holder, isFirstOfGroup, isLastOfGroup)
            holder.binding.content.setOnClickListener { openSchool(school) }
        }

        /**
         * 把同一首字母下的学校连接成 M3E 分段卡，并同步首项、末项与单项圆角。
         *
         * @param holder 当前学校条目的视图持有者
         * @param isFirstOfGroup 是否为当前字母分组的首项
         * @param isLastOfGroup 是否为当前字母分组的末项
         */
        private fun bindSegmentedAppearance(
            holder: SchoolViewHolder,
            isFirstOfGroup: Boolean,
            isLastOfGroup: Boolean,
        ) {
            val position = when {
                isFirstOfGroup && isLastOfGroup -> ListItemLayout.POSITION_SINGLE
                isFirstOfGroup -> ListItemLayout.POSITION_FIRST
                isLastOfGroup -> ListItemLayout.POSITION_LAST
                else -> ListItemLayout.POSITION_MIDDLE
            }
            // 组件状态机负责按压/拖动状态；显式形状确保当前 AppTheme 下仍能稳定呈现分段圆角。
            holder.binding.schoolListItem.updateAppearance(position)
            val radius = holder.itemView.resources.displayMetrics.density * SEGMENT_CORNER_RADIUS_DP
            val shape = ShapeAppearanceModel.builder()
            when {
                isFirstOfGroup && isLastOfGroup -> shape.setAllCornerSizes(radius)
                isFirstOfGroup -> shape.setTopLeftCornerSize(radius).setTopRightCornerSize(radius)
                isLastOfGroup -> shape.setBottomLeftCornerSize(radius)
                    .setBottomRightCornerSize(radius)

                else -> shape.setAllCornerSizes(0f)
            }
            holder.binding.content.shapeAppearanceModel = shape.build()
        }
    }

    /** 返回学校条目右侧显示的辅助教务名称。 */
    private fun schoolTypeLabel(school: SchoolRepository.School): String {
        if (school.sortKey == COMMON_SORT_KEY || school.type == HELP_TYPE) return ""
        if (school.type == LOGIN_TYPE) {
            return LOGIN_CONTRIBUTORS[school.name]?.let { contributor -> "by $contributor" }
                .orEmpty()
        }
        return TYPE_LABELS[school.type] ?: when {
            school.type.startsWith("zf") -> "正方教务"
            school.type.startsWith("qz") -> "强智教务"
            school.type.startsWith("jz") -> "金智教务"
            school.type.startsWith("shuwei") -> "树维教务"
            else -> ""
        }
    }

    private class SchoolViewHolder(
        val binding: ItemSchoolBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    /** 分类顺序与布局中的三个单选按钮保持一致。 */
    private enum class SchoolCategory {
        UNDERGRADUATE,
        GRADUATE,
        COMMON,
    }

    companion object {
        /** 仅选择学校/教务类型并把结果返回调用页。 */
        const val EXTRA_SELECTION_ONLY = "selection_only"

        /** 仅选择模式返回的学校展示名。 */
        const val RESULT_SCHOOL_NAME = "selected_school_name"

        /** 仅选择模式返回的 ParserFactory 类型。 */
        const val RESULT_PARSER_TYPE = "selected_parser_type"

        /** 仅选择模式在 HTML 导入页显示的“学校 · 教务类型”文案。 */
        const val RESULT_SELECTION_LABEL = "selected_school_label"

        private const val RAW_COMMON_SORT_KEY = "0"
        private const val COMMON_SORT_KEY = "通"
        private const val FAVORITE_SORT_KEY = "★"
        private const val GRADUATE_MARKER = "研究生"
        private const val HELP_TYPE = "help"
        private const val LOGIN_TYPE = "login"
        private const val SEGMENT_CORNER_RADIUS_DP = 16f
        private const val SCHOOL_HELP_URL = "https://openwakeup.fun/doc/import_from_eas.html"

        /**
         * 教务名称映射；未配置的学校保持右侧留空。
         */
        private val TYPE_LABELS = mapOf(
            "zf" to "正方教务",
            "zf_1" to "正方教务 1",
            "zf_new" to "新正方教务",
            "urp" to "URP 系统",
            "urp_new" to "新 URP 系统 1",
            "urp_new_ajax" to "新 URP 系统 2",
            "qz" to "强智教务 1",
            "qz_br" to "强智教务 2",
            "qz_with_node" to "强智教务 3",
            "qz_crazy" to "强智教务 4",
            "qz_2017" to "强智教务 5",
            "qz_2024" to "强智教务 6",
            "qz_old" to "旧强智教务",
            "cf" to "乘方教务",
            "vatuu" to "为途教务",
            "jz" to "金智教务",
            "jz_1" to "金智教务",
            "jz_x" to "金智教务",
            "umooc" to "优慕课在线",
            "maintain" to "维护中",
            "ecjtu" to "by Preciously",
            "jnu" to "by Jiuh-star",
            "hunnu_shuwei" to "by fearc",
            "ahnu" to "by Rocinante",
            "shu" to "by Deep Sea",
            "sit" to "by Zhangzqs",
            "sysu" to "by Dango, Moluer",
            "ccsu_qz_old" to "by magma213",
            "gzhuyjs" to "by Chaney1024",
            "gdbyxy" to "by 风潇子轩",
            "nfu" to "by Mori",
            "ecupl" to "by stevenlele",
            "ztvtit" to "by haijialiu",
            "whu_post" to "by 吉羽X",
            "thu" to "by stevenlele",
            "bjtu" to "by MooRoakee",
            "kg_zx" to "by icepie",
            "hust" to "by Xeu, GoForceX",
            "hrbeu_post" to "by liheji",
            "nau" to "by XFY9326",
            "nyist" to "by DefiedParty",
            "huat" to "by NekoRectifier",
            "jxau" to "by mrwoowoo",
            "shuwei_m" to "by 符号",
            "shuwei_json" to "树维教务",
            "xatu_shuwei" to "树维教务",
            "south_soft" to "南软教务",
            "yl" to "奕联教务",
            "cqupt" to "by YenalyLiew",
            "login_chaoxing" to "by 归客入故里",
            "jxnu" to "by realZnS",
            "ccibe" to "by eucaly",
            "shtu_post" to "by mhk",
            "shtu_post_2024" to "by trace1729",
            "hnjm" to "by fanyy0418",
            "ruc" to "by Holara",
            "xjtu_post" to "by Zorua",
            "sues" to "by a1375625918",
            "zptc" to "by shuTwT",
            "simc" to "by a1375625918",
            "cnu" to "by dxxupup",
            "swjtu_post" to "by Zorua",
            "gdei" to "by Ctanhuawu",
            "xauat_post" to "by akhzz",
            "nwpu_post" to "by ludoux",
            "jlu_post" to "by 符号",
            "bfa_post" to "by SalamanderEYE",
            "ustc_post" to "by foresee-io",
            "buaa" to "by PandZz",
            "qz_fspt" to "by MrXiaoM",
            "ygu" to "by gouzil",
            "shu2024" to "by Jonathan523",
            "fstvc" to "by lgc2333",
            "gxic" to "by JiuXia2025",
            "lngd" to "by gzy",
            "wist" to "by Qing90bing",
            "nju" to "by AritxOnly",
            "javtc" to "by paditianxiu",
            "yzzy" to "by zebinyang2",
        )

        /** 少数登录型学校按学校名标注适配贡献者。 */
        private val LOGIN_CONTRIBUTORS = mapOf(
            "华中科技大学" to "Lyt99, Mochi-Li",
            "清华大学（网络学堂）" to "RikaSugisawa",
            "上海大学" to "Deep Sea",
            "吉林大学" to "颩欥殘膤, IceSpite",
            "西北工业大学" to "ludoux, Pinming",
            "南京审计大学" to "XFY9326",
            "苏州大学" to "Y.",
            "合肥工业大学" to "Renton",
            "安徽师范大学" to "Rocinante",
            "九江职业大学" to "kuzwlu",
            "南方科技大学" to "GGAutomaton",
            "西安建筑科技大学" to "akhzz",
            "江西农业大学" to "mrwoowoo",
            "安徽科技学院（可直接登录）" to "Winter-is-comingXK",
        )
    }
}
