package com.openwakeup.schedule.platform.appwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 桌面小组件实例注册表。
 *
 * 四类 Provider 的类型、实际实例 ID 以及“是否随课程结束时间变化”等元数据统一在这里维护，
 * 业务层无需重复声明 Provider 列表，也不会在没有真实桌面实例时误触发数据库读取和图片解码。
 */
object WidgetInstanceRegistry {

    /** 小组件类型及其刷新能力。 */
    enum class Kind(
        val providerClass: Class<out BaseScheduleWidgetProvider>,
        val followsCourseProgress: Boolean,
    ) {
        /** 整周课表；内容不会因为某节课结束而变化。 */
        SCHEDULE(ScheduleWidgetProvider::class.java, false),

        /** 经典日视图；底部剩余课程数会随课程结束而变化。 */
        TODAY(TodayWidgetProvider::class.java, true),

        /** 紧凑今日课程；已结束课程需要从列表中移除。 */
        TODAY_COURSE(TodayCourseWidgetProvider::class.java, true),

        /** 今日与明日双栏；今日列需要移除已结束课程。 */
        RECENT_COURSE(RecentCourseWidgetProvider::class.java, true),
    }

    /**
     * 一次性取得当前桌面上的全部应用小组件实例。
     *
     * @param context 任意 Context，内部只使用应用 Context
     * @return 按小组件类型组织的不可变实例快照
     */
    fun snapshot(context: Context): Snapshot {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val instances = Kind.entries.associateWith { kind ->
            manager.getAppWidgetIds(ComponentName(appContext, kind.providerClass))
        }
        return Snapshot(instances)
    }

    /** 当前小组件实例的不可变快照。 */
    class Snapshot internal constructor(
        private val instances: Map<Kind, IntArray>,
    ) {
        /** 桌面是否至少存在一个本应用小组件。 */
        val hasAny: Boolean
            get() = instances.values.any { ids -> ids.isNotEmpty() }

        /** 是否存在需要在课程结束节点刷新的小组件。 */
        val hasCourseProgressWidgets: Boolean
            get() = Kind.entries.any { kind ->
                kind.followsCourseProgress && ids(kind).isNotEmpty()
            }

        /** 返回指定类型的真实实例 ID。 */
        fun ids(kind: Kind): IntArray = instances[kind] ?: intArrayOf()

        /** 返回当前存在真实实例的全部类型。 */
        fun installedKinds(): List<Kind> = Kind.entries.filter { kind -> ids(kind).isNotEmpty() }

        /** 返回当前存在真实实例、且需要课程节点刷新的类型。 */
        fun courseProgressKinds(): List<Kind> = Kind.entries.filter { kind ->
            kind.followsCourseProgress && ids(kind).isNotEmpty()
        }
    }
}
