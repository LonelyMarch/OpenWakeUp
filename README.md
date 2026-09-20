<p align="center">
  <img src="app/src/main/res/drawable-xxhdpi/ic_welcome_center_logo.webp" width="112" alt="OpenWakeUp 课程表图标">
</p>

<h1 align="center">OpenWakeUp</h1>

<p align="center">
  面向 Android 13 及以上版本的本地课程表应用。
</p>

<p align="center">
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white">
  <img alt="License: AGPL v3" src="https://img.shields.io/badge/License-AGPL%20v3-blue.svg">

</p>



> [!NOTE]
> “在线分享课表”目前仅保留界面入口，配套服务端尚未开放。其他课程管理、文件导入导出、备份、提醒和小部件功能均在本地完成。

OpenWakeUp 是独立维护的开源项目，与原 WakeUp 官方应用及其作者、运营团队不存在隶属、授权或商业合作关系。上游项目名称仅用于说明兼容性来源和版权归属。

## 主要功能

- 浏览周课表并快速切换周次；支持单双周、指定日期范围、日期调课和拖动节次范围快速添加课程；
- 管理多张课表、课程详情、学期周数、开学日期、每日节数和多套作息时间；
- 通过 CSV 和 ICS 文件导入课程，并检查星期、周次和节次范围；教务网页与 HTML 导入仍在完善；
- 将当前课表导出为标准 ICS，或通过 `.openwakebak` 文件选择性备份和恢复课表、时间表、全局设置及小部件设置；
- 提供“一周课程”“日视图”“今日课程”和“近日课程”四种桌面小部件，并可统一调整背景、颜色、字号、圆角和透明度；
- 提供横屏课程时钟，显示当前节次、上下课时间和后续课程；
- 通过精确闹钟发送课前提醒，并在开机、日期或时区变化后重新调度；
- 支持浅色、深色、跟随系统、动态取色、中英文界面，以及课表背景、文字、网格、行高和课程卡片样式定制。

## 导入与导出

| 类型            | 当前实现                                                                 |
|---------------|----------------------------------------------------------------------|
| 教务网页          | **TODO：仍在完善。** 计划从内置学校清单选择学校或教务类型，在 WebView 中登录后提取当前页面并解析            |
| HTML          | **TODO：仍在完善。** 计划读取 UTF-8 或 GBK 编码的本地 HTML，并使用所选学校或教务类型对应的解析器        |
| CSV           | 支持 UTF-8、GBK/GB2312；应用内提供模板，周数可填写连续范围、离散周次或单双周                       |
| ICS           | 支持 RFC 5545 常用字段、时区、每周重复、`RDATE` 和 `EXDATE`，同时识别 OpenWakeUp 精确节次扩展字段 |
| ICS 导出        | 为每个课程时间段生成带周次规则的日历事件，并写入学期起点、每日节数和精确节次信息                             |
| OpenWakeUp 备份 | 使用 `.openwakebak` 文件；支持选择性导入导出，并校验格式版本、数据库版本、文件完整性和资源安全性             |
| 分享口令          | **TODO：仍在完善。** 当前仅保留界面入口，配套服务端尚未开放                                   |

浏览器端 [OpenWakeUp ICS Formatter](tools/ics-formatter/README.md) 可直接从受支持的教务课表页面生成带精确节次信息的
ICS 文件。该工具独立于 Android 构建，不会打包进 APK。

## TODO

- [ ] 完善从教务导入。
- [ ] 完善从 HTML 导入。
- [ ] 完善分享口令。

## 技术栈

| 类别         | 选型                                                                      |
|------------|-------------------------------------------------------------------------|
| 语言与字节码     | Kotlin 2.4.20、Java 17 目标字节码                                             |
| Android 平台 | 最低 Android 13（API 33），目标 Android 16（API 36），编译 SDK 36.1                 |
| 界面         | Android View、ViewBinding、Material 3 Expressive                          |
| 数据         | Room 2.8.5、SharedPreferences、JSON/ZIP 备份                                |
| 并发         | Kotlin Coroutines、Flow                                                  |
| 网页与解析      | Android WebView、jsoup、自有 CSV/ICS 解析器                                    |
| 序列化        | kotlinx.serialization                                                   |
| 构建         | Gradle 9.7.1、Android Gradle Plugin 9.3.2、Kotlin DSL、Version Catalog、KSP |

工程包含两个 Gradle 模块：

- `:app`：Android 应用，负责界面、数据存储、导入导出、提醒和桌面小部件；
- `:parser`：不依赖 Android API 的 JVM 解析器库，负责 CSV、ICS 和各类教务网页解析。

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── assets/                         # 离线学校及教务类型清单
│       ├── kotlin/com/openwakeup/schedule/
│       │   ├── app/                        # 应用入口
│       │   ├── core/                       # 数据库、设计系统、格式化与通用能力
│       │   ├── data/                       # 课表、学校、分享与备份仓库
│       │   ├── feature/                    # 课表、编辑、导入导出与设置页面
│       │   └── platform/                   # 提醒、小部件与 ContentProvider
│       └── res/                            # Android 资源
├── parser/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/openwakeup/parser/
│       ├── csv/                            # CSV 解析
│       ├── ics/                            # ICS 解析
│       └── web/                            # 教务网页家族与通用表格解析
├── tools/
│   └── ics-formatter/                      # 独立的浏览器端 ICS 生成工具
├── gradle/                                 # Wrapper 与依赖版本目录
├── build.gradle.kts
├── settings.gradle.kts
└── LICENSE
```

## 开发环境

- JDK 21（Gradle 工具链使用 JDK 21，应用和解析器输出 Java 17 字节码）；
- Android Studio 或兼容的 Android 命令行工具；
- Android SDK 36.1；
- 仓库自带的 Gradle Wrapper 9.7.1。

首次导入工程时，让 Android Studio 自动生成 `local.properties`，或在该文件中配置本机 Android SDK。
`local.properties`、签名文件、IDE 配置和构建产物均已被 Git 忽略。

### 构建 Debug APK

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

APK 默认生成在 `app/build/outputs/apk/debug/`。

### 编译解析器模块

Windows：

```powershell
.\gradlew.bat :parser:compileKotlin
```

macOS / Linux：

```bash
./gradlew :parser:compileKotlin
```

### 运行静态检查

Windows：

```powershell
.\gradlew.bat :app:lintDebug :parser:compileKotlin
```

macOS / Linux：

```bash
./gradlew :app:lintDebug :parser:compileKotlin
```

## 数据与权限

课程、课表、作息和偏好默认保存在设备本地。教务网页导入需要网络连接；学校清单中的部分教务地址仅能在对应校园网或
VPN 环境访问，也可能随学校系统升级而失效。

应用会根据启用的功能使用以下权限或系统能力：

- 网络访问：加载教务页面和外部帮助页面；
- 通知与振动：发送课程提醒；
- 精确闹钟、开机广播与电池优化例外：调度提醒并刷新小部件；
- 系统文件与图片选择器：导入课程文件以及选择背景图片，无需申请全盘存储权限。

为兼容仍使用 HTTP 的旧教务系统，应用当前允许明文网络流量；调试构建还会信任用户安装的证书。内置浏览器会承载学校教务登录页面，请核对网址和网络环境后再提交账号或密码。不要在
Issue、日志或示例文件中上传真实凭据、Cookie、课表、学号等个人数据。

## 参与贡献

欢迎通过 Issue 报告问题或提交 Pull Request。完整流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。外部 Pull
Request 必须以 `dev` 为目标，标题使用 `<type>(<scope>): <subject>`。提交前请确认：

1. 改动范围清晰，解析规则、日期计算和数据库迁移等关键逻辑有相应验证；
2. 已运行 `:app:lintDebug`、`:app:assembleDebug` 和 `:parser:compileKotlin`；
3. 未提交 `local.properties`、签名密钥、账号凭据、真实课表或其他个人信息；
4. 新增依赖、字体、图片和数据具有兼容的开源许可证；
5. 注释解释约束、边界条件和设计原因，不包含内部任务编号、临时实施记录或个人身份信息。

## 致谢

本项目的兼容格式、交互和解析实现受以下开源项目及社区工作启发：

- [YZune/WakeupSchedule_Kotlin 的可审计镜像](https://github.com/ThaiCao/WakeupSchedule_Kotlin)；
- [YZune/WakeUpSchedule](https://github.com/YZune/WakeUpSchedule)；
- [xianfei/WakeupSchedule_BUPT](https://github.com/xianfei/WakeupSchedule_BUPT)；
- [VenomBat/CourseAdapter](https://github.com/VenomBat/CourseAdapter)；
- [wtlyu/Course-Table-ICS-Formatter](https://github.com/wtlyu/Course-Table-ICS-Formatter)。

上游项目的代码、名称、图标及商标权利归各自权利人所有。第三方代码、资源、固定审计版本和许可证详见 [NOTICE.md](NOTICE.md)。

## 开源许可

本项目依据 [GNU Affero General Public License v3.0](LICENSE) 开放源码。分发、修改或通过网络提供衍生服务前，请阅读并遵守许可证条款。
