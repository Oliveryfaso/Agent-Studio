# 技术演进记录

## 2026-08-03：建立项目基线

### 阶段

项目处于想法 / 架构阶段，尚无运行代码、依赖或发布物。

### 架构决定

- 确立“宿主无关中间模型 + Codex/Claude 适配器 + Effective Context Compiler + 确定性策略 + 安全事务引擎”的主架构。
- MVP 主栈为 Vue 3 + TypeScript 与 Java 21。
- Python 不进入安全关键写入链；后续仅在离线评测或实验分析有可测增益时加入。
- 本地核心负责所有路径授权、解析、Diff、快照、journal、原子写入与回退。
- AI 只生成结构化候选 ChangeSet，不直接拥有写入能力。
- 恢复仓独立于 Git、Codex/Claude checkpoint 和 hooks。

### 安全决定

- 默认只读、离线和最小作用域。
- 扫描阶段绝不执行 repo 中发现的命令、skill、hook、plugin 或 MCP。
- 所有变更实行 scan/plan/validate/approve/snapshot/apply/verify/rollback 闭环。
- 逻辑路径与 real path 同时校验，symlink/junction 越界 fail closed。
- 审计日志脱敏；快照内容进入受保护恢复仓。

### 研究修正

- 将“200 行=92%、模块化=96%、400 行后下降”标为缺少可审计实验的社区/博客说法。
- 采用官方产品预算与来源标记，不使用未证实百分比作为校验门槛。
- 明确区分 Claude Markdown rules 与 Codex command policy `.rules`。

### 尚未选择

- 具体 Java 构建工具和依赖。
- loopback HTTP 或同进程 IPC。
- SQLite/CST/OS keychain 具体库。
- UI 组件库和图可视化实现。
- 模型提供商与 GitHub 认证方式。

以上选项应通过最小 spike 决定，不应在脚手架阶段一次性引入。

## 2026-08-03：加入双向 Conversion Workbench

### 产品变化

- 在原生管理模式之外，正式加入 Codex → Claude 与 Claude → Codex 两个转换方向。
- 提供单文件 Quick Convert 小工具，并与批量 Conversion Workbench 共用同一安全核心。
- 双向转换不表示所有文件等价；每个映射标为 Exact、Compatible、Assisted 或 Unsupported。
- 目标已存在时采用三方审阅，不默认覆盖。

### 架构变化

- 在宿主适配器之后增加 conversion pipeline、版本化 recipe registry、loss report 和 semantic round-trip validator。
- 转换统一经过 `source adapter → neutral IR → target capability matcher → staging renderer`，不按目录或文件名直接复制。
- 转换结果没有独立写入权限，继续使用普通 ChangeSet、快照、事务、验证和回退流程。
- 来源 hash、宿主版本、recipe 版本、未映射字段和权限变化进入 ConversionPlan 与脱敏审计。

### 安全边界

- 权限、Hooks、Plugins、MCP 和完整 settings/config 默认 Unsupported，只生成迁移报告或禁用骨架。
- 转换不得静默扩大工具、网络、模型调用、自动调用或可执行行为。
- Exact/Compatible 映射需要目标 schema 与 round-trip fixture；Assisted 映射必须逐项确认。

## 2026-08-03：从双宿主扩展为多宿主平台

### 产品变化

- 保留 Codex 与 Claude Code 的 Core 深度支持，并把 Cursor、GitHub Copilot、Windsurf / Devin Desktop 设为 Wave 1。
- 将 Cline、Roo Code、Gemini CLI、OpenCode、Continue 纳入 Wave 2 只读/转换预览；Aider 先作为 Export Only。
- Quick Convert 从固定 Codex ↔ Claude 方向改为源宿主自动识别 + 目标宿主选择；批量工作台支持一个源导出多个目标候选。
- 托管式生成产品在缺少稳定本地配置契约时只提供 Prompt/portable instruction 导出，不宣称原生写入支持。

### 架构变化

- 引入 Host Adapter Registry、统一 adapter contract、capability matrix 和成熟度 gate。
- N 个宿主通过 neutral IR 和 capability recipe 互转，不建立 N×N 成对转换代码。
- `HostProfile` 扩展为带官方证据、验证日期、版本范围和支持等级的 `HostDescriptor`。
- adapter 按 Inventory、Read、Conversion preview、Apply 逐级晋升；支持矩阵独立记录在 `HOST_SUPPORT_MATRIX.md`。

### 安全边界

- “发现到路径”不等于“支持该宿主”；未通过 conformance fixture 的 adapter 只能只读。
- 新宿主的 mode、activation、surface、permissions、checkpoint 等专属语义保留为 host extension，不能静默压平。
- 任意目标写入仍复用 ChangeSet、快照、事务和回退，不因 adapter 数量增加而获得旁路。

## 2026-08-03：落地 Phase 1 只读安全核心切片

### 已实现

- 增加零运行时依赖的 Java 21 scanner、Host Adapter Registry、metadata-only JSON CLI 和自包含 fixture runner。
- scanner 仅打开 registry allowlist 命中的普通文件，流式计算 SHA-256，并以有界样本判断编码与换行；原文不进入 CLI JSON。
- 扫描不跟随符号链接；越界、断链、循环和非普通文件形成显式 Finding。
- 在读取前后复核 real path、size、mtime 与 file key，发现并发变化时报告而不把结果当成稳定快照。
- 7 组测试覆盖 registry gate、allowlist、已知 hash、零写入、惰性恶意内容、符号链接逃逸/断链/循环和伪装成配置文件的目录。

### Adapter 边界

- `HostAdapter` v1 只提供不可变 manifest，不拥有文件系统、解析、渲染、转换或写入能力。
- 将产品路线 `RoadmapTier` 与实际运行成熟度 `AdapterMaturity` 分开；Codex/Claude 可属于 Core 路线，但当前仍与 Cursor/Copilot/Windsurf-Devin 一样处于 `INVENTORY`。
- 未验证版本通过 `versionStatus` 明确标识；路径识别不再被表达成 Read 或 Conversion 支持。

### 构建状态

- 当前使用 `scripts/test-core.sh` 与 `scripts/run-cli.sh` 直接调用 JDK 21，避免为第一刀引入构建系统和测试框架依赖。
- Gradle/Maven、多模块发布结构和第三方测试框架仍是待决项；后续应根据跨平台 CI、打包和依赖校验需求做最小 spike 后选择。

## 2026-08-03：Phase 1 扫描控制、Git 探测与跨平台验证

### 扫描可靠性

- `ScanLimits` 增加总读取字节预算；scanner 在遍历和每个流式读取块前检查协作式取消。
- `ScanResult` 显式返回 `COMPLETE/PARTIAL` 与停止原因，深度/条目/字节预算或取消时保留此前已验证的确定性结果；max-depth 只有实际遗漏后代时才标为 partial，空边界目录不会误报。
- 候选文件先按 portable logical path 排序再读取，使字节预算下的部分 inventory 可重复。
- JSON CLI 输出 completion/stop reason，部分结果以退出码 3 表达。

### Git 最小元数据

- 新增零依赖 `GitMetadataProbe`，默认不运行；CLI 只有显式 `--git-metadata` 才调用。
- probe 不执行 Git/子进程，不读取 index、objects、packed refs 或工作区内容，只读取有界、严格 UTF-8 的 `.git` 指针与 `HEAD`。
- dirty 状态固定为 `UNKNOWN_NOT_PROBED`；外部 gitdir 默认拒绝，API 只有额外授权且 linked-worktree 结构与 backlink 均验证通过时才读取。

### 验证与 CI

- 自包含测试扩展到 32 项，覆盖扫描控制、Git 边界、编码/换行、确定性顺序、max-depth 真/假截断、不可读文件、只读 POSIX 工作区和 CLI 显式授权。
- 增加 Ubuntu/macOS/Windows workflow；不使用第三方 action，精确 checkout 当前 workflow commit，并从 runner 已声明的 Java 21 toolchain fail-closed 选择。
- 工作流已静态检查，尚未在真实 GitHub runner 执行；Windows junction/reparse-point fixture 仍是 Phase 1 release gate。

## 2026-08-03：启动 Phase 2 项目级 Effective Instruction Chain

### 可运行能力

- 新增 Java 21 `EffectiveInstructionCompiler` 与 `context` CLI，不引入运行时依赖，也不写入目标工作区。
- Codex 切片按授权项目根到 CWD 逐层选择 `AGENTS.override.md` 或 `AGENTS.md`，显示 shadow/empty/active/truncated/limit 状态、precedence、大小与 SHA-256，并按官方默认值模拟 32 KiB 组合预算。
- Claude Code 切片按授权根到 CWD 显示 `CLAUDE.md`、`CLAUDE.local.md` 和根 `.claude/CLAUDE.md` 的项目级顺序；`.claude/rules/**/*.md` 先只发现并标记为 `NOT_EVALUATED`，不猜测 `paths` activation。
- JSON 不包含原文；用户/global 配置、Codex 自定义 fallback/budget、Claude imports、条件 rules 与按需 descendant memory 进入显式 limitations。
- 新增 `scripts/run-context.sh` 和 8 项 fixture；该初始切片完成时本地总计 40 项测试通过。

### 开发节奏

- 保留授权根 containment、只读、不执行发现内容、不回显原文、确定性输出与显式限制这些最小安全底线。
- Windows reparse-point、确定性并发替换和真实三平台 CI 仍属于发布前 gate，但不再阻塞实验阶段继续实现只读用户价值。
- Host Adapter 整体成熟度仍为 `INVENTORY`；只把已实现的项目默认 context 子能力标为 experimental，避免能力冒进。

## 2026-08-03：Effective Context 从默认文件清单扩展到项目语义

### Codex

- 增加有界、零依赖的 Codex 配置 snapshot parser，支持顶层 `project_doc_fallback_filenames` 与 `project_doc_max_bytes`，包括多行字符串数组、注释、quoted key、严格 UTF-8 和结构化脱敏 diagnostics。
- CLI 新增显式 `--codex-config <snapshot.toml>`；不会自动读取 `CODEX_HOME`。fallback 文件进入同层 override/base/fallback 选择链，自定义 budget 进入 effective byte 计算。

### Claude Code

- 增加 Markdown import lexer：识别正文中的 `@relative/path`，跳过 inline code 与 fenced code，按引用文件目录解析工作区内 import，递归最多四跳，并报告缺失、循环、外部批准未知和解析预算。
- 增加 `.claude/rules` 的 `paths` YAML 子集与 glob evaluator，支持多行/内联列表、`*`、`**`、`?`、bracket 和有界 brace expansion。
- CLI 新增 `--target-file <project-relative-file>`；无条件 rules 始终 active，条件 rules 按目标文件输出 active/no-match，未提供目标时输出未决 finding。

### 结果契约与验证

- Context JSON 升级到 schema v2，增加 `orderingModel`、`resolutionStatus`、结构化 findings 和 `loadOrder`。任何影响该请求语义可信度的未知项返回 `PARTIAL`，CLI 使用退出码 3。
- 大于 1 MiB 的宿主指令仍按宿主加载语义标为 active；工具只省略完整 hash/语义深析，避免把本工具预算误当成宿主行为。
- 新增 9 项 Codex option parser、10 项 Claude semantic parser 和 7 项端到端 context fixture；本地总计 66 项测试通过。
- 独立审查指出并推动修复：CLI 空参数语义、超大文件误排除、无条件 rules 误标、完整性不可机器判断和 symlink 宿主差异静默化。

## 2026-08-03：落地宿主无关 Instruction IR 与只读分析器

### 数据契约

- Codex 与 Claude Code 继续先由 Effective Context Compiler 按各自加载语义求值，再投影到宿主无关 Instruction IR；IR 保存 source identity、逻辑路径、revision/effective hash、有效字节数、scope、load state、load order、activation evidence 与 provenance edge。
- Context JSON 保持 schema v2；新增的 Analyze JSON 独立使用 schema v1，并显式引用其输入的 context schema 版本，避免为分析能力破坏既有 context 消费方。
- 默认 Analyze JSON 不包含指令正文、规范化文本或 `realPath`。正文只在有界内存中参与严格 UTF-8 分析，输出仅保留 hash、长度、稳定 ID 和元数据引用。

### 重复与冲突语义

- 只有 active source 的“实际有效载荷”hash 与 included length 都相等时，才生成确定性的 `EXACT_EFFECTIVE_DUPLICATE`。Codex 达到字节预算时比较截断后的 effective slice，而不是完整文件 revision。
- Markdown 列表指令的规范化重复生成 `NORMALIZED_DIRECTIVE_DUPLICATE` 启发式候选；中英文直接肯定/否定模式生成 `DIRECT_POLARITY_CONFLICT` 启发式候选。代码围栏、inline code、HTML comment 等示例区域不参与指令抽取。
- import 与 shadow 关系进入 provenance；同一 Claude import 被多个父节点引用不会因此复制 source node，也不会被误报为正文重复。

### 运行与边界

- 新增 `analyze codex`、`analyze claude-code` 与 `scripts/run-analyze.sh`；宿主参数与 `context` 对应：Codex 可显式提供配置 snapshot，Claude 可提供项目相对 target file。
- 分析器仍为本地、只读、零运行时依赖能力：不执行发现内容，不修改或转换任何配置文件，也不支持 Cursor、Copilot、Windsurf 等其他宿主的 Effective Context 语义。
- 启发式 finding 本身不改变成功退出码；只有 context/IR 不完整时沿用退出码 3。当前本地 95 项 fixture 全部通过，后续以完整测试脚本当次输出为准。

## 2026-08-03：建立版本化项目语义 conformance 基线

### Profile 与报告

- Codex 与 Claude Code 的窄项目语义分别固定为 `codex-project-semantics-v1`、`claude-code-project-semantics-v1`；Context schema v2 与 Analyze schema v1 都输出对应 `semanticProfile`，避免宿主行为变化时静默改变旧结果含义。
- 新增 `scripts/run-conformance.sh`，只运行版本化宿主语义和分析器对抗性套件；测试日志写 stderr，stdout 输出机器可读 conformance schema v1。

### 覆盖与修正

- Codex 9 项覆盖 root→CWD、override、empty fallback、自定义 fallback、预算边界、截断/后续跳过、revision/effective hash 与稳定逻辑身份。
- Claude Code 10 项覆盖双 project memory、local/nested 顺序、rule activation、递归/多父/cycle/external import 与 provenance 稳定性。
- 分析器 8 项覆盖 inactive scope 排除、规范化、相似非相等、确定性/启发式分层与误报场景；据此把中文“不得不”识别为肯定要求，并抑制成对引号内示例措辞的极性触发。
- 全量回归现为 122 项通过，其中独立 conformance 27 项；仍未把这些窄 profile 宣称为完整 Read adapter 或特定厂商版本的全面兼容证明。

## 2026-08-04：落地 Gate 4 ConversionPlan 只读预览切片

### 转换契约与 planner

- 新增 ConversionPlan schema v1，固定 operation、零写入和不可 Apply 状态，并记录 source/target semantic profile、recipe ID/version、mapping grade、IR provenance、loss、未决问题与 capability delta。
- 新增 Codex → Claude Code、Claude Code → Codex 两个 recipe v1。planner 只接受 `COMPLETE` IR，稳定排序并用内容无关 fingerprint 产生可重复 ID；空输入、部分支持、完全阻断和需审阅状态彼此分开。
- `EXACT` 现在必须有 rendered candidate、target validation、semantic round-trip、three-way review 与安全 capability delta 的完整证据。当前 planner 保守地不产生 Exact；根 `AGENTS.md` 的 Claude import wrapper 标为 Compatible，其余多数 instruction 计划为 Assisted/Metadata-only。

### 目标探测与 CLI

- 新增 `convert-preview` 与 `scripts/run-convert-preview.sh`，支持 Codex ↔ Claude Code 双向计划 JSON。报告不含原始 source/candidate/target 正文或 `realPath`，并明确输出所有 `NOT_RUN/NOT_APPLICABLE` 验证状态。
- bounded target probe 只在授权根内处理 portable logical path，普通文件读取上限为 4 MiB，只保留 SHA-256 和长度；symlink/outside-scope、目录和超限目标 fail closed。probe 后 planner 第二次运行，把目标 absent/existing/identical/conflict/invalid 状态写入计划。
- CLI 对合法但需人工处理的 Assisted/Unsupported 计划仍返回 0，方便 UI 消费结构化结果；参数/启动错误返回 2，source IR partial 或 unsafe target 返回 3。命令不会创建 staging 或目标文件。

### 验证与成熟度

- 新增 28 项转换测试：schema 12 项、planner 10 项、CLI 6 项；全量本地回归由 122 增至 150 项，原有 27 项版本化 conformance 保持通过。
- Codex/Claude 整体 adapter maturity 仍是 Inventory。新能力单独标记为 `EXPERIMENTAL_PLAN_ONLY`；Gate 4 只有 contract/planner/CLI 子门完成，通用 renderer、原生目标校验、semantic round-trip、three-way review 和 permissions/version conformance 仍未完成。

## 2026-08-04：规划 Skills 生命周期助手与评测门控演化

### 产品目标

- 用户主旅程扩展为 `Create → Organize → Route → Evaluate → Improve → Govern`。
- 自然语言生成前先做 persistence triage，区分一次性 Prompt、always-on Instruction、可复用 Skill、隔离 Agent contract 和确定性工具/Policy。
- 新增大 Skill 分解、Skill Catalog/Router、Gap Detector、Eval Lab 与 Improvement Inbox 的规划；现有配置治理工作台继续作为安全内核。

### 架构边界

- 保持 content-free `InstructionIr v1` 不变，规划独立的 `SkillBlueprint`、`SkillPackageIr`、Route、Eval、Trace、Revision 与 Optimization schema。
- 确定性模板、静态验证、基础路由和 ChangeSet 是无 LLM baseline；LLM 仅可选参与模糊分类、起草、候选重排和不可变修订提案。
- Skill Manager 采用 catalog/router 服务而非常驻超级 Skill；大 Skill 按触发、结果、复用、权限和评测证据拆分，不按长度机械切割。

### 历史学习与 Hook

- 确立“用户选择 trace → 脱敏 → gap review → 候选 → baseline/holdout/regression → 人工审批 → ChangeSet”的离线改进闭环。
- evolutionary/genetic、textual-gradient 和 reflection 方法只作为离线候选生成策略；优化器不接触 live Skill，不修改自己的 eval。
- Codex/Claude Hook 只可发送有界 event envelope；不在 Hook 内调用 LLM、解析完整 transcript、运行 eval、改文件或执行 Git。

### 阶段状态

- 本轮只修改项目规划与研究文档，没有修改 Java 运行代码或测试基线。
- Skills lane S0/S1 尚未实现。近期仍先完成现有 Gate 4 bounded renderer/validator，再实现 Codex Project Skill Draft Studio 的 inventory 与 Blueprint Preview。

## 2026-08-04：完成 Gate 4 canonical wrapper 验证纵向切片

### ConversionPlan v2

- ConversionPlan schema 与 Codex/Claude recipe 升级到 v2；Context v2、Analyze v1、Instruction IR v1、宿主 semantic profile 和 conformance suite ID 保持不变。
- `TargetCandidate` 记录 renderer、target validator、semantic round-trip 和 target review profile，并让已运行证据绑定 candidate hash。
- mapping identity 现在覆盖 candidate path/hash/size、existing target state/hash/size、验证状态/profile/subject hash 和 capability delta；目标或验证证据变化会产生不同 plan ID。

### 可证明的 portable subset

- 只为单一、完整、根级 Codex `AGENTS.md` 生成 canonical `CLAUDE.md` wrapper `@AGENTS.md\n`。
- 新增 64 KiB 上限的纯内存 renderer、defensive byte copy、严格 canonical target validator，以及 recipe-specific root scope/hash/import round-trip。
- 不完整 active payload、nested instruction、Claude memory/rule 正文和其他 content-dependent 映射继续保持 `ASSISTED/METADATA_ONLY`；Claude imported `AGENTS.md` 用 `REUSED_SOURCE` 表达，不再伪装成 freshly rendered。

### 目标与安全不变量

- target probe 增加 32 路径上限和 read 前后 size/mtime/file-key 复核，显式表达 `CHANGED_DURING_PROBE`。
- existing target 只进入 hash/size 元数据：identical 是 no-op，conflict 需要人工审阅，unsafe/stale 使 CLI 返回 3；不自动 merge、不写 staging。
- 修复 `fullyValidated()` 可在 unsafe target 上被伪造 `PASSED` 的领域漏洞；metadata-only 不能宣称正文验证通过，失败 target validation 不能搭配成功 round-trip。

### 验证

- 全量 fixture 从 150 增至 158，全部通过。
- 版本化 conformance 仍为 27/27：Codex 9、Claude Code 10、Instruction Analysis 8。
- 使用真实 CLI 输出通过 JSON parse smoke，确认 schema v2、recipe v2、target validation/round-trip `PASSED`，同时保持 `writesPerformed=false`、`applyEligible=false`。

## 2026-08-04：从平台架构探索收敛为 Codex-first 用户闭环

### 产品与路线

- 长期多宿主 Skills 生命周期目标保持不变，当前执行顺序改为 `Inspect → Draft → Diff/Export → Simple Apply/Rollback`。
- scanner、Effective Context、Instruction IR、analyzer、Host Registry 和 conversion contract 继续作为治理内核；canonical wrapper 重新定位为 adapter 合同样例。
- 通用 conversion、Git/GitHub、Wave hosts、Router、history/evolution 和完整事务平台冻结，等待 Codex 闭环的真实用户验证。

### 首个用户入口

- 新增 `inspect codex` 与 `scripts/inspect-codex.sh`，复用现有 Effective Context 和 Instruction Analysis，不新增解析器或领域 schema。
- 默认输出中文人类摘要，区分当前生效、未加载、确定问题、启发式建议、检查限制和下一步；不输出指令正文、hash、source ID 或 `realPath`。
- `COMPLETE` 返回 0，`PARTIAL` 返回 3，参数或启动错误返回 2；任何结果都明确说明零项目写入和零内容执行。

### 验证

- 新增 7 项 inspect CLI fixture，覆盖正常、空目录、启发式建议、精确重复、partial、零工作区写入和错误宿主。
- 全量本地 fixture 从 158 增至 165；版本化 conformance 仍为 27 项。

## 2026-08-04：确定桌面优先形态并建立 GitHub 发布基线

- 产品选择桌面优先、本地 Web UI：Vue 3 负责交互，Java 21 负责文件、策略和回退；第一实现使用 loopback-only HTTP，发布时优先通过 `jpackage` 携带运行时。
- 纯在线网页不进入主路线，因为它不能独立满足本地目录授权、原子写入、备份回退和默认离线要求。
- GitHub 正式仓库确定为 `Oliveryfaso/Agent-Studio`；发布前忽略 build、AppleDouble、IDE、编译器参数、日志和本地 workbench 状态，并执行敏感文件名、凭据模式和大文件审计。
- 首次真实 CI 暴露 macOS Java toolchain 环境变量差异和 Windows Git Bash 绝对路径 argfile 不兼容；改用固定提交的 `actions/setup-java`，并让 javac source argfile 使用仓库相对路径后，Linux、macOS、Windows 三个平台全部通过。

## 2026-08-05：完成 Codex Project Skill Inventory 第一子门

- 新增 `skill-inventory codex <authorized-workspace>` 与 `scripts/run-skill-inventory.sh`，只处理根级 `.agents/skills/<name>/SKILL.md`，输出 schema v1 元数据报告并固定 `contentIncluded=false`、`writesPerformed=false`。
- inventory 对 `SKILL.md` 使用 128 KiB 上限和严格 UTF-8，检查最小 `name` / `description` frontmatter、目录名一致性与重复声明名；supporting files 只枚举路径和数量，不读取正文、不执行脚本。
- `.agents`、`skills`、package、`SKILL.md` 与 supporting path 的符号链接均不跟随；scripts 目录、常见可执行扩展名和 POSIX execute bit 只形成风险标记，不触发执行。
- 新增 12 项 fixture，覆盖空目录、合法 package、元数据错误与无效/错位声明值脱敏、重复名、祖先/Skill/support symlink、entry budget、零写入与 CLI 隐私合同；全量本地 fixture 从 165 增至 177，conformance 保持 27 项。
- 本轮只完成 S0 的 package/metadata/risk 子门；安全引用图尚未实现，S1 persistence triage 与 `SkillBlueprint v1` 仍未开始。
