# 参与贡献

感谢你参与 OpenWakeUp。提交代码、资源或数据前，请先阅读本文件。

## 分支模型

- `dev` 是默认开发分支。项目维护者直接在 `dev` 上开发和提交。
- 外部贡献者从最新 `dev` 创建分支，并向 `dev` 提交 Pull Request。
- `master` 是稳定分支，只接受同仓库的 `dev -> master` 发布 Pull Request。
- `dev -> master` 必须选择 **Create a merge commit**，对应 `git merge --no-ff`。
- 不接受直接面向 `master` 的功能、修复或依赖更新 Pull Request。

维护者开始开发前应执行：

```powershell
git switch dev
git pull --ff-only origin dev
```

已推送到 `dev` 的提交不得通过 rebase 或 force-push 改写。CI 失败时应追加修复提交。

## 提交信息

提交信息采用以下格式：

```text
<type>(<scope>): <subject>

<body>

<footer>
```

- `type` 必填：`feat`、`fix`、`docs`、`refactor`、`build`、`ci`、`chore`、`perf`、`style` 或 `revert`。
- `scope` 必填：使用稳定的小写模块或领域名，例如 `app`、`parser`、`backup`、`widget`、`database` 或
  `release`。
- `subject` 必填：使用简洁中文描述结果，末尾不加句号。
- `body` 可选：解释修改原因、关键设计和行为差异。
- `footer` 可选：填写 `Closes #123`、`Refs #123` 或 `BREAKING CHANGE: ...`。
- 破坏性变更可以使用 `feat(scope)!: ...`，并在 footer 中说明迁移方式。

示例：

```text
feat(backup): 增加备份归档完整性校验

导出时记录每个条目的 SHA-256，导入写入数据库前统一验证摘要和引用关系。

Closes #42
```

Pull Request 最终使用 Squash Merge 时，其标题将成为 `dev` 上的提交首行，因此 PR 标题也必须符合上述格式。

## 代码规范

- Kotlin 和 Java 遵循 JetBrains 代码风格。
- 类型、函数及重要公开成员应提供详细中文 KDoc 或 JavaDoc。
- 复杂算法、兼容性分支、权限约束和数据迁移应提供中文行内注释。
- 注释解释原因、约束和边界，不记录临时任务编号或个人信息。
- 不提交无关格式化、生成目录、IDE 配置或本机配置。
- 一个提交只处理一个逻辑主题，并保持可编译。

## 本地验证

项目不要求自动化测试。提交前至少运行与改动范围相称的编译、Lint 或构建任务；涉及多个模块时运行完整门禁：

```powershell
.\gradlew.bat :parser:compileKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

UI、小部件、导入导出、备份和数据库迁移等变更还应完成人工验证，并在 Pull Request 中记录设备版本、操作路径和结果。

## 来源与隐私

- 你必须拥有所提交代码、资源和数据的修改与再分发权。
- 引入第三方内容时，必须提供来源仓库、固定版本、许可证及修改说明。
- 不接受来源为闭源应用反编译结果、未授权素材或许可证不明确的代码。
- 不得提交账号、密码、Cookie、Token、真实学号、真实课表或其他个人信息。
- 教务页面样本必须最小化并使用虚构内容。
- 提交贡献即表示你同意按本仓库的 AGPL-3.0-only 许可证提供该贡献。

## Pull Request 清单

- 目标分支为 `dev`。
- 标题符合提交信息格式。
- 改动范围单一且说明清楚。
- 已记录执行过的构建和人工验证。
- UI 变化附截图或录屏。
- 已说明兼容性、数据格式和数据库迁移影响。
- 新增第三方内容已声明来源与许可证。
- 不含秘密信息或个人数据。
