# Agent Config Workbench

GitHub: [Oliveryfaso/Agent-Studio](https://github.com/Oliveryfaso/Agent-Studio)

Agent Config Workbench（智能体配置工作台）的长期目标，是在 Codex、Claude Code 和主流 vibe-coding 工具之间管理、生成、转换并安全应用 instructions、skills、rules 和 agents。当前实施刻意收敛为 Codex-first 的 `Inspect → Draft → Diff/Export → Simple Apply/Rollback` 单资产闭环；其他宿主、通用转换、GitHub、Router 和历史演化在核心用户价值验证前保持冻结。

当前状态：**实验室原型，Codex-first 单资产闭环已有可操作的本地 Vue 页面**。Java 21 治理内核可在用户明确指定的普通项目中创建第一个 Codex project Skill，或更新一个已存在的 Skill；标准模板使用结构化表单，自定义 Skill 可直接编辑受验证的 `SKILL.md` 原文，随后执行 `preview → approve/apply → rollback`。新增的“技能库”会把项目 Skill 按九类确定性整理，无法可靠判断的条目进入人工队列；分类和人工调整都不改文件。同源 Vue 3 页面显示技能分类、真实目标、完整 Diff、批准状态和事务回执。它仍不保证断电恢复、跨进程并发 CAS 或完整 Windows reparse 防护，因此 Gate 6 仍部分开放；Gate 7 已进入“核心单页流程可用”，还不是可分发桌面产品。

## 当前产品焦点

1. Codex 项目 instruction/skill/agent/rule 的可读检查。
2. Codex project Skills 的九类整理、查看与人工校正。
3. 自然语言 persistence triage 与单个 Codex Skill 草案。
4. 正文预览、静态检查、逐行 Diff 与复制/导出。
5. 单文件、项目内、明确批准后的简单写入与原始字节回退。

Claude Code 与其他宿主仍保留在长期路线中；现有 conversion、Git probe 和 conformance 代码作为治理内核与实验合同保留，但当前不继续横向扩建。

最终产品采用**桌面优先、本地 Web UI**：Vue 3 提供界面，Java 21 掌握本地文件与回退能力，发布时连同运行时打包成独立应用。开发阶段先由本地 Java 进程在 loopback 地址提供 Vue 页面；不做需要把项目内容上传到服务器的纯在线网页。

## 当前结论

- 产品不是“长 Markdown 生成器”，而是可审计的配置变更工作流。
- 用户主旅程扩展为 `Create → Organize → Route → Evaluate → Improve → Govern`；“AI 生成”只是其中一个可选步骤。
- 自然语言先被分类为 Prompt、Instruction、Skill、Agent contract 或应由确定性工具执行的规则，用户确认后才持久化。
- 大 Skill 按任务边界、复用、权限和评测结果拆分，不按行数机械切割；Skill Manager 是目录与路由服务，不是始终加载的超级 Skill。
- 历史驱动改进只产生版本化候选，经独立 eval、holdout、Diff 和人工审批后才能进入 ChangeSet；不做 live 自修改。
- 采用 Host Adapter Registry：Codex 与 Claude Code 首批深度支持，Cursor、GitHub Copilot、Windsurf / Devin Desktop 紧随其后，Cline、Roo Code、Gemini CLI、OpenCode、Continue 分级接入。
- 同时支持原生管理、任意已支持源宿主 → 目标宿主的 Conversion Workbench，以及单文件 Quick Convert 小工具。
- 转换先进入宿主无关中间模型；无法等价映射的权限、Hook、Plugin 不自动转换。
- 默认只读；所有写入都必须经过候选变更、可视 Diff、验证、明确批准、快照和事务提交。
- Git 是协作与审阅渠道，不是唯一回退机制。
- Hooks 是可选的运行期保护层或轻量事件传感器，不读取/理解完整历史，不运行 LLM，不修改 Skill，也不承担备份或事务职责。
- 第一版采用 Vue 3 + TypeScript 与 Java 21；Python 仅在后续离线评测确有价值时加入。

## 当前可运行能力

- 在用户明确给出的工作区根目录内，只发现 allowlist 中的配置资产。
- 注册 Codex、Claude Code、Cursor、GitHub Copilot、Windsurf / Devin Desktop 五个宿主 manifest。
- 单独表达产品路线等级与适配器运行成熟度；当前五个宿主均为 `INVENTORY`，不会因“识别到文件名”而宣称已支持解析或转换。
- 输出逻辑路径、真实路径、类型、大小、SHA-256、编码与换行提示；不输出文件原文。
- 不跟随符号链接；越界、断链、循环和特殊文件均产生显式 Finding。
- 扫描内容始终被当作惰性数据，不执行其中的命令、Hook、Skill、Plugin 或脚本。
- 扫描具有总条目、单文件、总读取字节和深度上限，并支持协作式取消；不完整结果显式标为 `PARTIAL` 和停止原因。
- Git 元数据探测默认关闭；只有显式传入 `--git-metadata` 才读取最小 `.git`/`HEAD` 元数据，且不会执行 Git、检查 dirty 状态或读取 index/object database。
- `context codex` 按授权根目录 → CWD 解释 `AGENTS.override.md` / `AGENTS.md` 的选择、优先级与字节预算；可通过显式配置快照启用 `project_doc_fallback_filenames` 和 `project_doc_max_bytes`。
- `context claude-code` 按授权根目录 → CWD 解释 `CLAUDE.md`、`CLAUDE.local.md` 与根 `.claude/CLAUDE.md`，递归解析工作区内 `@imports`（最多四跳），并识别 import 缺失、循环和外部批准未知。
- `.claude/rules/**/*.md` 已支持无条件规则、`paths` 多行/内联列表、常用 glob、brace expansion，以及按 `--target-file` 输出匹配或不匹配状态。
- Context schema v2 输出路径、状态、load order、大小、hash、`COMPLETE/PARTIAL`、稳定 finding code 与 limitation，不回显原文；整体能力标为 `EXPERIMENTAL_PROJECT_SEMANTICS`。
- Context 与 Analyze 输出会携带 `codex-project-semantics-v1` 或 `claude-code-project-semantics-v1`，让下游明确知道报告依据的窄语义版本。
- `analyze codex` / `analyze claude-code` 在同一 Effective Context 上生成宿主无关 Instruction IR、activation evidence 与 import/shadow provenance；Analyze schema v1 与既有 Context schema v2 分开版本化，Context 消费方保持兼容。
- 分析器把“有效载荷字节完全相同”报告为确定性的 `EXACT_EFFECTIVE_DUPLICATE`；规范化指令重复与中英文直接极性冲突仅报告为启发式候选，不把文本相似误称为精确结论。
- Analyze JSON 只包含稳定 ID、逻辑路径、hash、长度、作用域、证据和 finding；不包含指令正文、normalized text 或 `realPath`。分析全程只读，不执行内容、不写入或转换文件。
- `scripts/run-conformance.sh` 独立执行两种宿主语义与分析器对抗性套件，并输出 schema v1 的机器可读 PASS 报告。
- `convert-preview codex claude-code` 与反向命令生成 ConversionPlan schema v2，包含 recipe、mapping grade、provenance、capability delta、loss、未决问题、目标冲突 hash，以及与 candidate hash 绑定的 renderer/validator/round-trip/review profile；`writesPerformed=false`、`applyEligible=false`。
- 转换预览严格拒绝 `PARTIAL` IR。目标探测限定在授权根目录、拒绝 symlink，并且只输出 SHA-256/长度；现有目标不会被读取到报告正文，更不会被覆盖。
- 根级、单一、完整的 `AGENTS.md → CLAUDE.md` canonical wrapper `@AGENTS.md\n` 已通过受限内存 renderer、recipe-specific Claude 结构验证和语义 round-trip；候选字节不进入 JSON。existing target 只比较 hash/size，identical、conflict、unsafe、stale 分开表达，不自动 merge。
- 其他 instruction 结构仍通常为 `ASSISTED/METADATA_ONLY`，policy、hook、plugin、permission、可执行行为等仍为 `UNSUPPORTED`；任何 `NOT_RUN`、`FAILED`、`UNKNOWN` 或 unsafe target 都不能伪装成 fully validated。
- `inspect codex` 将 Context 与 Analyze 投影为中文摘要，默认不输出正文、hash、source ID 或 `realPath`，并固定说明零写入。
- `skill-inventory codex` 只检查根级 `.agents/skills/<name>/SKILL.md`：读取有上限的 UTF-8 frontmatter 与正文内联引用，输出 schema v2 的逻辑路径、hash、大小、最小字段状态、supporting-file 数量、风险和安全引用图。`codex-skill-inline-reference-v1` 支持包内 `[link](relative)` / `![image](relative)`、angle path、query 与 fragment；不宣称 full CommonMark。只有 `RESOLVED` edge 暴露 target logical path；`MISSING/UNKNOWN` 只保留 source、line/column、类型与状态。supporting files 只枚举路径，不读取或执行内容，报告固定 `contentIncluded=false`、`writesPerformed=false`。
- `POST /api/v1/skills/classifications` 使用版本化 `dev-skill-taxonomy-v1`，只根据目录名和 frontmatter `description` 中维护的高精度短语，将 Skill 建议到库/API、产品验证、数据分析、流程自动化、脚手架、质量审查、CI/CD、Runbook、基础运维九类。分数或领先幅度不足时返回 `UNCLASSIFIED`，不读取 supporting files、不回传正文/description、不调用 LLM，也不写入工作区。
- `skill-blueprint-preview codex` 从 stdin 读取不超过 32 KiB 的严格 UTF-8 向导；Java 核心不接收 workspace 路径。便捷脚本只打开用户显式选择的单个普通非符号链接文件。自然语言只进入显式 goal/description 等 Blueprint 字段，分类只使用 recurrence/trigger/success/isolation/enforcement 等向导事实，不使用关键词猜测。输出固定 `workspaceContentIncluded=false`、`userProvidedContentIncluded=true`、`rawRequestIncluded=false`、`llmUsed=false`、`writesPerformed=false`、`applyEligible=false`；未确认、缺字段和高风险自动化退出 3 且不生成 Blueprint。
- `skill-draft-preview codex` 复用同一向导输入，仅接受 `BLUEPRINT_READY` 的 Codex project Skill。`codex-project-skill-template-v1` 生成只有 `name` / `description` frontmatter 的单文件候选；触发与排除被写入 description，正文采用固定 progressive-disclosure 章节并转义用户 Markdown 结构。独立的 `codex-project-skill-static-v1` 对最终 UTF-8/LF 字节、description 安全约束、预算、路径、canonical content 和 hash 绑定做检查。默认 JSON 不含正文；只有 `READY` 候选可用 `--export content|diff|prompt` 显式输出。Diff 自带 `SYNTHETIC_NEW_FILE / NOT_CHECKED` 标记并以 `/dev/null` 为基线，不代表检查过磁盘目标；tools、额外权限、supporting-file proposal 或非 LOW risk 返回 `REVIEW_REQUIRED`，且 raw export 被阻断。
- S3 fixture transaction 只接受 marker 内容精确匹配的临时 workspace，并要求独立 marker state root。manifest v3 同时支持 `PREPARED → COMMIT_INTENT → APPLIED` 与 `APPLIED → ROLLBACK_INTENT → ROLLED_BACK`；`recoverTransaction` 只在 source/result identity、hash、权限、快照、stage 与拓扑组合唯一时推进。`scanPendingTransactions` 只枚举 state root 直接子项，受 direct-entry/manifest 双预算与稳定 cursor 约束，只返回 transaction metadata，不读出候选内容、不写入也不自动恢复。receipt 分开记录本次目标写与状态写。fixture API 本身没有公开 CLI；真实过渡 CLI 使用更窄的 existing-only 合同，暂未继承自动恢复。不提供断电级 directory fsync、OS 级 CAS/防 TOCTOU 或 Windows junction 完整证明。
- `skill-change-preview/apply/rollback` 是首个真实工作区入口，只处理一个 Codex project Skill。更新模式绑定真实 preimage 与 Diff；创建模式绑定目标不存在状态、操作类型和缺失父目录。两者都要求显式 approval token，并把事务状态放入 workspace 外的可信 state root。更新 rollback 恢复 byte-exact snapshot；创建 rollback 只在 identity/hash/权限未变化时删除本次文件，并只清理本事务创建且仍为空的目录。
- Vue 3 + TypeScript 页面分为“技能库”和“编辑与应用”。技能库在导入后显示稳定 3×3 分类桶、轻量投放动画、分类计数、待整理队列、拖拽/原生选择器两种人工归类方式和 Skill 详情；人工覆盖只绑定本次会话的逻辑路径与 source SHA。编辑区覆盖 `更新已有 / 新建 Skill`、中文表单、受验证原文编辑、真实 Diff、明确批准、apply receipt 与 guarded rollback。选择已有 Skill 后，受限 content endpoint 会读取明确选中的 `SKILL.md`：template-v1 只回填可证明的字段，并要求用户补齐运行时文件未保存的 3+3 路由测试例；自定义结构进入原文模式，保留未知正文与结构，经过 `codex-raw-skill-static-v1` 的路径、UTF-8/LF、128 KiB 和最小 frontmatter 检查后整体替换。加载时的 source SHA 是原文 preview 的必填绑定，文件若在读取后发生变化即拒绝继续；切换目标前会提示未应用草稿。空项目直接进入创建模式；创建后列表自动刷新，撤销后恢复为空项目状态。`BLOCKED`、`NO_CHANGE`、`STALE`、`CURRENT_TARGET_CHANGED`、`RECOVERY_REQUIRED` 与网络错误分开呈现。启动 token 通过 URL fragment 交付，页面读取后立刻从地址栏删除并只保留在内存。
- Java 同源静态资源服务只允许构建产物中的 `index.html`、hash asset 与 favicon，带 CSP/no-store/nosniff 等响应头；仍只绑定 loopback，state root 仍由启动参数固定。
- Ubuntu、macOS、Windows 三平台 CI 基线已真实通过并持续复验；当前本地 300 项 Java 测试用例全部通过，其中版本化 conformance 为 27 项。每次变更仍以对应远端 CI run 为合并依据。

需要 JDK 21；本地 UI 构建还需要 Node.js/npm。Java 核心仍不依赖 Gradle、Maven 或第三方库；Vue 3、Vite、TypeScript 与 vue-tsc 只存在于 `ui/`，用于浏览器交互和静态构建：

```bash
scripts/test-core.sh
scripts/run-conformance.sh
scripts/inspect-codex.sh /absolute/path/to/authorized-workspace
scripts/inspect-codex.sh /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-skill-inventory.sh /absolute/path/to/authorized-workspace
scripts/run-skill-blueprint-preview.sh /absolute/path/to/request.intent
scripts/run-skill-draft-preview.sh /absolute/path/to/request.intent
scripts/run-skill-draft-preview.sh /absolute/path/to/request.intent --export content
scripts/run-skill-draft-preview.sh /absolute/path/to/request.intent --export diff
scripts/run-skill-draft-preview.sh /absolute/path/to/request.intent --export prompt
scripts/run-skill-change.sh preview /absolute/path/to/workspace /absolute/path/to/request.intent
scripts/run-skill-change.sh preview /absolute/path/to/workspace /absolute/path/to/request.intent --diff
scripts/run-skill-change.sh apply /absolute/path/to/workspace /absolute/path/to/trusted-state /absolute/path/to/request.intent '<approval-token>'
scripts/run-skill-change.sh rollback /absolute/path/to/workspace /absolute/path/to/trusted-state '<transaction-id>'
scripts/run-local-api.sh /absolute/path/to/trusted-state
scripts/build-ui.sh
scripts/run-local-web.sh /absolute/path/to/trusted-state
scripts/run-cli.sh /absolute/path/to/authorized-workspace
scripts/run-cli.sh /absolute/path/to/authorized-workspace --git-metadata
scripts/run-context.sh codex /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-context.sh codex /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir --codex-config /absolute/path/to/config-snapshot.toml
scripts/run-context.sh claude-code /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-context.sh claude-code /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir --target-file src/api/user.ts
scripts/run-analyze.sh codex /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-analyze.sh codex /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir --codex-config /absolute/path/to/config-snapshot.toml
scripts/run-analyze.sh claude-code /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-analyze.sh claude-code /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir --target-file src/api/user.ts
scripts/run-convert-preview.sh codex claude-code /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
scripts/run-convert-preview.sh claude-code codex /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir --target-file src/api/user.ts
```

若 JDK 21 不在 `PATH`，先把其 `bin` 加入 `PATH`。CLI 的退出码为：`0` 当前命令范围内成功完成、`2` 参数/输入/schema 或启动失败、`3` 已产生可用报告但处于 partial、needs-confirmation、incomplete 或 blocked 状态。

### S1 向导文件示例

向导使用 `key: value`，可重复的键包括 `input`、`output`、`trigger`、`exclusion`、`boundary-example`、`should-trigger`、`should-not-trigger`、`step`、`validation`、`tool`、`permission` 与 `supporting-file`。下面是能产生完整 Blueprint 和 `READY` S2 草案的最小形状；两个命令都不写入 `SKILL.md`：

```text
repeated-workflow: true
clear-trigger: true
success-criteria: true
confirmed-artifact: skill
confirmed-scope: project
name: review-api-change
description: Review API changes when backend contracts are modified.
goal: Produce a bounded API change review.
input: Changed API files
output: Review findings
trigger: Use when an API contract changes.
exclusion: Do not use for UI-only changes.
boundary-example: A documentation typo is outside scope.
should-trigger: Review a backend endpoint
should-trigger: Review an API migration
should-trigger: Review a compatibility change
should-not-trigger: Review CSS colors
should-not-trigger: Draft a marketing page
should-not-trigger: Rename an image
step: Identify changed contracts
step: Check compatibility
completion: Every changed contract has a result.
validation: Every finding cites an input file.
permission: NONE
risk: LOW
```

加入 `supporting-file` 只会记录尚未生成的 package-relative proposal，并令 S2 返回 `REVIEW_REQUIRED`；本阶段不会生成虚假链接或 supporting file 内容。

前两个 draft 命令不写目标工作区。现有 CLI 保持兼容，仍只提供更新已有 Skill；网页额外提供显式创建模式。Apply 必须再次提供同一个向导、preview 返回的 approval token，以及 workspace 外、由当前用户控制的可信 state 目录。网页创建模式会逐层列出并创建缺失的 `.agents/skills/<name>` 目录，目标已存在时拒绝；成功回执中的 transaction ID 可用于显式 rollback。approval token 是计划完整性绑定，不是密码或身份凭证。

`run-local-api.sh` 只启动 typed API，供协议调试。普通本地体验先运行 `scripts/build-ui.sh`，再运行 `scripts/run-local-web.sh <workspace 外的可信 state 目录>`，打开终端输出的 `Open:` 地址。POST API 要求精确 Host、同源 Origin 和 token，不开放 CORS；state root 在进程启动时固定，浏览器请求不能改写。当前入口不是面向局域网或互联网的服务，也不会自动打开浏览器。

其他分类信号为 `duration: one-shot|persistent`、`isolated-context`、`independent-responsibility`、`special-tool-boundary`、`deterministic-enforcement` 和 `executable-automation`。布尔值只接受 `true|false`；executable automation 的优先级最高并默认阻断。

## 文档

- [项目指南](docs/PROJECT_GUIDE.md)
- [Skills 生命周期助手规划](docs/SKILL_LIFECYCLE_SPEC.md)
- [研究与证据备忘录](docs/RESEARCH_NOTES.md)
- [多宿主支持矩阵](docs/HOST_SUPPORT_MATRIX.md)
- [技术演进记录](docs/TECH_EVOLUTION.md)
- [决策日志](docs/DECISION_LOG.md)
- [项目内 Codex 指南](AGENTS.md)

## 下一里程碑

创建、读取、分类和更新 Codex project Skill 的首个 Vue 闭环已经开放，非模板 Skill 也能以受验证原文候选经过精确 Diff 后整体编辑。下一步优先实现系统 workspace 选择，再接可重启恢复入口和 `jpackage` 可下载 alpha；分类的持久化、可配置词表和可选 LLM 建议器留在真实用户反馈之后。不同时扩建其他宿主、Router 或历史演化。发布级事务仍需 interrupted-process recovery、OS 级 CAS/dir-handle-relative 防 TOCTOU、断电级 directory fsync 与 Windows reparse/junction fixture，因此 Gate 6 仍只算部分开放。
