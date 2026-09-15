# OpenWakeUp ICS Formatter

这是一个完全独立于 Android 应用构建的浏览器课表导出工具。它会读取当前教务系统页面，将课程导出为标准
`.ics` 文件，同时写入 OpenWakeUp 可识别的精确节次字段。

## 使用方式

项目上传到 `LonelyMarch/OpenWakeUp` 的 `dev`
分支后，打开教务系统的课表页面并等待课表加载完成，然后把下面整行内容复制到浏览器地址栏运行。部分浏览器粘贴后会移除开头的
`javascript:`，此时需要手动补回。

```javascript
javascript:void(function(d,s){s=d.body.appendChild(d.createElement('script'));s.src='https://cdn.jsdelivr.net/gh/LonelyMarch/OpenWakeUp@dev/tools/ics-formatter/openwakeup-course-table-ics.js?ts='+Date.now();s.charset='UTF-8'}(document))
```

运行后依次填写：

1. 学期第一周周一；
2. 学期总周数；
3. 确认识别出的课程时间段与每日节数，然后下载 ICS。

## 实现说明

- 研究生系统：从实际 HTML 表格构建包含 `rowspan`/`colspan` 的逻辑网格，每天节数取页面真实行数，不固定为
  13。
- 本科 EAMS：优先读取页面已有的 `table0.activities` 结构化数据。
- ICS 标准字段：生成 `DTSTART`、`DTEND`、`RRULE`、`SUMMARY`、`DESCRIPTION`、`LOCATION` 和提前 10 分钟提醒。
- 课程名称：自动移除名称末尾形如 `(G2700635.01)` 的教务课程序号，保留课程名称自身的说明括号。
- OpenWakeUp 扩展字段：生成 `X-OPENWAKEUP-NODE-COUNT`、`X-OPENWAKEUP-SEMESTER-START`、
  `X-OPENWAKEUP-DAY`、`X-OPENWAKEUP-START-NODE`、`X-OPENWAKEUP-STEP` 与 `X-OPENWAKEUP-WEEKS`
  。其他日历软件会安全忽略这些非标准字段。
- 时间按 `Asia/Shanghai` 转换为 UTC 写入，文本使用无 BOM UTF-8、RFC 5545 转义与 75 字节折行。

脚本源文件为 [`openwakeup-course-table-ics.js`](./openwakeup-course-table-ics.js)，不在
`settings.gradle.kts` 中注册，也不会参与应用编译或打包。
