# 更新日志

本项目的用户可见变化记录在此文件中，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)
，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.0.2] - 2026-09-21

### Added

- 增加“从教务导入”功能及多种教务系统解析支持。

### Changed

- 统一各导入入口的成功反馈，并在导入完成后返回主课表。
- 分别保存小部件浅色与暗色主题下的纯色背景和标题颜色。

### Fixed

- 修正第一周日期选择偏移问题。
- 修复教务网页首次加载缓慢问题。
- 过滤 GET 请求中的 `X-Requested-With` 包名字段，改善教务网页兼容性。

## [0.0.1] - 2026-09-15

### Added

- 首次公开源码。
