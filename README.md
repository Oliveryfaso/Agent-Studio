# Agent Config Workbench

GitHub: [Oliveryfaso/Agent-Studio](https://github.com/Oliveryfaso/Agent-Studio)

Agent Config Workbench（智能体配置工作台）的长期目标，是在 Codex、Claude Code 和主流 vibe-coding 工具之间管理、生成、转换并安全应用 instructions、skills、rules 和 agents。当前实施刻意收敛为 Codex-first 的 `Inspect → Draft → Diff/Export → Simple Apply/Rollback` 单资产闭环；其他宿主、通用转换、GitHub、Router 和历史演化在核心用户价值验证前保持冻结。

当前状态：**实验室原型，Codex-first 的第一个用户入口已可运行**。仓库已有零依赖的 Java 21 只读扫描器、Codex / Claude Code 项目级 Effective Instruction Chain、Instruction IR 分析和窄转换实验；新增的 `inspect` 会把 Codex 当前目录真正生效的项目指令、加载状态、确定问题和启发式建议整理为人类可读摘要。Vue、Skill Draft、Diff/Export、Simple Apply/Rollback 和 AI 起草仍未实现。

## 当前产品焦点

1. Codex 项目 instruction/skill/agent/rule 的可读检查。
2. 自然语言 persistence triage 与单个 Codex Skill 草案。
3. 正文预览、静态检查、逐行 Diff 与复制/导出。
4. 单文件、项目内、明确批准后的简单写入与原始字节回退。

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
- 已定义 Ubuntu、macOS、Windows 三平台安全 CI；当前本地 165 项 fixture 全部通过，其中版本化 conformance 为 27 项，工作流尚待真实 GitHub runner 首次验证。

需要 JDK 21。当前 spike 不依赖 Gradle、Maven 或第三方库：

```bash
scripts/test-core.sh
scripts/run-conformance.sh
scripts/inspect-codex.sh /absolute/path/to/authorized-workspace
scripts/inspect-codex.sh /absolute/path/to/authorized-workspace /absolute/path/to/authorized-workspace/subdir
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

若 JDK 21 不在 `PATH`，先把其 `bin` 加入 `PATH`。CLI 的退出码为：`0` 当前命令范围内结果完整、`2` 参数或启动失败、`3` 扫描/解析为 `PARTIAL` 或存在阻断性 finding。

## 文档

- [项目指南](docs/PROJECT_GUIDE.md)
- [Skills 生命周期助手规划](docs/SKILL_LIFECYCLE_SPEC.md)
- [研究与证据备忘录](docs/RESEARCH_NOTES.md)
- [多宿主支持矩阵](docs/HOST_SUPPORT_MATRIX.md)
- [技术演进记录](docs/TECH_EVOLUTION.md)
- [决策日志](docs/DECISION_LOG.md)
- [项目内 Codex 指南](AGENTS.md)

## 下一里程碑

`inspect` 纵向切片完成后，下一步进入 Codex Project Skill Draft：先做 Codex project Skill inventory，再实现 persistence triage、`SkillBlueprint v1`、内存候选、静态检查和 Diff/Export，全程零目标写入。完成真实用户验证后再实现单文件 Simple Apply/Rollback；不并行扩展通用转换、更多宿主、GitHub、Router 或历史演化。
