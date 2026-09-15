# 第三方声明

OpenWakeUp 以 GNU Affero General Public License v3.0
only（AGPL-3.0-only）发布。以下项目或资源为本项目提供了代码基础、兼容性参考或可再分发资源；各自版权仍归原权利人所有。

OpenWakeUp 项目代码及项目原创内容：Copyright (C) 2026 LonelyMarch and contributors。

## WakeupSchedule Kotlin

- 原项目：`YZune/WakeupSchedule_Kotlin`（当前原地址不可访问）。
- 可审计镜像：<https://github.com/ThaiCao/WakeupSchedule_Kotlin>
- 审计提交：`a69fb7555e0917e0ba8a0dadbf3248364666b089`
- 许可证：Apache License 2.0。
- 版权所有：Copyright 2019 YZune。
- 使用范围：课程表领域模型、部分 Android 交互组件及 CSV、URP、正方教务解析实现的早期代码基础；OpenWakeUp
  已进行包结构、数据契约、功能和界面修改。直接派生的 Kotlin 文件带有显著修改声明。

## NumberPickerView

- 项目：<https://github.com/Carbs0126/NumberPickerView>
- 审计提交：`642c376f25fb074d97b6277fb8bdd1807b251334`
- 许可证：Apache License 2.0。
- 版权所有：Copyright 2016 Carbs.Wang (NumberPickerView)。
- 使用范围：滚轮选择器的 API 与交互实现，经 Kotlin 化和项目适配。

## ColorPicker

- 项目：<https://github.com/jaredrummler/ColorPicker>
- 审计提交：`eb76c92f53087cebff5521e217015ba95e49ad39`
- 许可证：Apache License 2.0。
- 版权所有：Copyright (C) 2017 Jared Rummler。
- 使用范围：`ColorPickerView.kt` 与 `AlphaPatternDrawable.kt` 的取色、透明度和棋盘格绘制基础；OpenWakeUp
  已进行 Kotlin 化、属性和主题适配。

## WakeupSchedule BUPT

- 项目：<https://github.com/xianfei/WakeupSchedule_BUPT>
- 审计提交：`88d5b2ff93b946b40e8ade6851d4440188443a7f`
- 许可证：Apache License 2.0。
- 使用范围：教务系统兼容性与解析行为参考。

## Course Table ICS Formatter

- 项目：<https://github.com/wtlyu/Course-Table-ICS-Formatter>
- 审计提交：`a335e983f760bbbc0503200ca8661aac4b08f413`
- 许可证：Apache License 2.0。
- 使用范围：浏览器端课表识别和 ICS 生成工具的实现参考；OpenWakeUp 使用独立命名和扩展字段。

## Material Symbols

- 项目：<https://github.com/google/material-design-icons>
- 审计提交：`40a7a292a79d9394157e1ea24f83d52d5e17c556`
- 许可证：Apache License 2.0。
- 使用范围：`app/src/main/res/drawable/ms_*.xml` 图标路径。

上述 Apache-2.0 内容的许可证全文见 [
`third_party/licenses/Apache-2.0.txt`](third_party/licenses/Apache-2.0.txt)。依赖管理器下载的
AndroidX、Material Components、Kotlin、Room、jsoup 等库仍分别遵循其自身许可证；它们不因本项目使用
AGPL-3.0-only 而改变许可证。
