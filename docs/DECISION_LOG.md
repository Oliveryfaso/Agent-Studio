# 决策日志

## ADR-001：产品是配置治理工作流，不是 Markdown 生成器

- 日期：2026-08-03
- 状态：接受
- 决定：以资产发现、有效上下文解释、ChangeSet、验证、事务和回退为主线；生成内容只是其中一步。
- 原因：单纯生成文件不能处理作用域、冲突、执行风险、并发编辑和文件损失。
- 放弃：只提供 `AGENTS.md` / `CLAUDE.md` 模板生成器。

## ADR-002：使用宿主无关 IR 和专属适配器

- 日期：2026-08-03
- 状态：接受
- 决定：IR 区分 Instruction、Skill、Agent、Policy、Hook、Plugin、Prompt，再由注册到 Host Adapter Registry 的宿主 adapter 发现和渲染。
- 原因：同名机制不一定同义；最典型的是 Claude rules 与 Codex `.rules`。
- 放弃：目录名对目录名的直接转换器。

## ADR-003：双宿主共享内容以 `AGENTS.md` 为核心

- 日期：2026-08-03
- 状态：接受
- 决定：根 `AGENTS.md` 保存共享 always-on 内容；根 `CLAUDE.md` 用 `@AGENTS.md` 导入并追加 Claude 差异。
- 原因：Codex 原生读取 `AGENTS.md`；Anthropic 官方明确推荐 Claude 通过 import 复用它。
- 限制：Claude path-scoped rules 与 Codex nested instructions 仍分别原生管理。

## ADR-004：Java 是唯一安全关键文件写入核心

- 日期：2026-08-03
- 状态：接受
- 决定：Vue 只经受控 API 请求 ChangeSet；Java 负责路径、策略、快照、journal、原子写入与回退。Python 不直接写目标配置。
- 原因：减少特权运行时数量和跨语言文件语义差异。
- 复审条件：Java 无法满足某个已证明必要的跨平台能力，且替代方案通过同等安全测试。

## ADR-005：Git 与 hooks 不是回退机制

- 日期：2026-08-03
- 状态：接受
- 决定：每次 apply 使用独立内容快照与 write-ahead journal；Git 只辅助审阅协作，hooks 只作运行期 guardrail。
- 原因：目标可能未跟踪或位于全局目录；hooks 可并发、可有副作用、post hook 无法撤销操作。
- 放弃：自动 stash/reset；用 PreToolUse/PostToolUse 代替事务。

## ADR-006：长度阈值是带来源的启发式

- 日期：2026-08-03
- 状态：接受
- 决定：分别显示官方限制/建议、项目默认与用户策略；不输出虚构遵循率。
- 原因：SFEIR 数字缺少实验链；论文只支持长度、位置、数量和冲突会影响表现，不能给出通用行数公式。
- 后续：使用真实 fixture 和可复现 eval 校准 UX 默认。

## ADR-007：MVP 默认离线、只读和单 AI 调用

- 日期：2026-08-03
- 状态：接受
- 决定：确定性 scanner/analyzer 先行；外部 AI 与 GitHub 均显式启用。需要 AI 时优先一次结构化调用。
- 原因：多代理增加权限、延迟、成本与可观测性负担，目前没有可测必要性。
- 复审条件：基准证明专用 planner/reviewer 分工显著改善安全或正确率。

## ADR-008：MVP 不自动建立跨宿主 skill 符号链接

- 日期：2026-08-03
- 状态：接受
- 决定：分别管理 `.agents/skills` 与 `.claude/skills`，检测语义重复；不自动 symlink。
- 原因：Windows、Git、real path 边界、不同 frontmatter 和 supporting-file 行为会增加不可见风险。
- 后续：验证一个可共享子集后考虑受控 managed-copy 或显式 pair manifest。
- 补充：双向转换在 staging 中生成带 provenance 的目标原生副本，不使用自动 symlink。

## ADR-009：支持 Codex 与 Claude 的双向转换工作台

- 日期：2026-08-03
- 状态：接受
- 决定：保留 Native 模式，并增加 Codex → Claude、Claude → Codex 两种方向；提供单文件 Quick Convert 和批量 Conversion Workbench，所有入口共用统一 IR、版本化 recipe、目标 capability 匹配、loss report 和 semantic round-trip。
- 原因：用户需要迁移已有资产，而不是重新手写；统一 IR 可以复用解析、安全、Diff、验证和回退，同时避免把同名但不同义的文件机械互转。
- 安全限制：映射分为 Exact、Compatible、Assisted、Unsupported；Unsupported 不生成可应用变更，Assisted 不默认选择方案；任何转换都不能静默扩大权限或执行能力。
- 放弃：按目录名/文件名直接复制；用 LLM 自由改写整个配置树；为转换器建立绕过 ChangeSet 的写入通道。
- 验证：每条 recipe 有源/目标版本 fixture，Exact/Compatible 通过目标 schema 与 round-trip，目标已存在时必须提供三方 Diff。

## ADR-010：从双宿主升级为分级多宿主 adapter 生态

- 日期：2026-08-03
- 状态：接受
- 决定：Codex 与 Claude Code 保持 Core；Cursor、GitHub Copilot、Windsurf / Devin Desktop 作为 Wave 1 Beta Adapter；Cline、Roo Code、Gemini CLI、OpenCode、Continue 作为 Wave 2 Preview Adapter；Aider 先提供 Export Only。完整分级与官方证据见 `HOST_SUPPORT_MATRIX.md`。
- 架构：所有宿主实现统一 Host Adapter 契约和 capability matrix；转换仍走 `source adapter → neutral IR → target matcher → recipe → target adapter`，不实现 N×N 成对转换器。
- 晋级规则：adapter 按 `Inventory → Read → Conversion preview → Apply` 逐级开放。没有官方证据、版本 fixture、lossless parse、native validation、round-trip 和权限扩张测试时，不开放写入。
- 原因：主流编码代理的文件路径、加载时机、作用域、mode、权限和工作流语义差异明显；统一契约既能扩展覆盖面，也能避免“改文件名即支持”的虚假兼容。
- 放弃：第一版同时对所有宿主开放写入；每新增宿主手写所有两两转换；把托管平台的 Prompt 导入包装成本地配置管理。

## ADR-011：路线等级与运行成熟度必须正交

- 日期：2026-08-03
- 状态：接受
- 决定：用 `RoadmapTier` 表达 Core/Beta/Preview/Export 产品优先级，用 `AdapterMaturity` 表达 Inventory/Read/Conversion/Apply 的已验证能力；两者不得互相推导。
- 当前实施：Phase 1 的 Codex、Claude Code、Cursor、GitHub Copilot、Windsurf / Devin Desktop 均为 `INVENTORY`。`HostAdapter` v1 只返回不可变 manifest，不接收文件系统能力，也不暴露 parse/render/convert/apply API。
- 原因：Core 表示优先深耕，并不证明已经拥有版本 fixture、lossless parser、原生 validator 或写入安全证据。把两者混成一个等级会让 UI 和策略错误开放能力。
- 晋级：只有通过对应版本证据与 conformance fixture，registry 才能独立提升运行成熟度。

## ADR-012：Phase 1 spike 暂用纯 JDK 构建

- 日期：2026-08-03
- 状态：接受，待正式工程化时复审
- 决定：首个 scanner/registry 切片用 Java 21 标准库和仓库脚本编译测试，不引入 Gradle、Maven、JUnit 或运行时依赖。
- 原因：当前测试只需验证安全边界，JDK 已足够；先避免未经证明的供应链与构建复杂度。
- 限制：这不是最终构建选择。跨平台 CI、模块发布、依赖校验或 `jpackage` 需要时，应通过最小 spike 重新选择并更新本 ADR。

## ADR-013：不完整扫描必须成为一等结果

- 日期：2026-08-03
- 状态：接受
- 决定：扫描结果显式记录 `COMPLETE/PARTIAL` 与停止原因；总条目、总读取字节和取消均返回已验证的确定性前缀，不把截断结果伪装成完整 inventory。
- 原因：超大仓库、恶意目录树和用户取消是正常运行状态；仅写日志或抛异常会丢失安全可用的部分结果，也会让 UI 无法准确表达风险。
- 实施：深度边界、总条目、总读取字节和取消均有独立停止原因；只有深度边界确实存在未扫描后代时才返回 partial，空边界目录保持 complete。

## ADR-014：Git 元数据探测默认关闭且不执行 Git

- 日期：2026-08-03
- 状态：接受
- 决定：普通 scan 不检查 `.git`；CLI 只有 `--git-metadata` 才调用最小只读 probe。probe 不启动子进程，不读取 index/object/worktree 内容，不推断 dirty 状态。
- 外部边界：`.git` symlink 永远拒绝；外部 gitfile 默认拒绝。内部 API 只有额外授权且 linked-worktree 的 commondir 布局与 backlink 全部验证时才读取外部 HEAD。
- 原因：Git 状态并不是配置 inventory 的必要条件；把它做成显式、最小、可降级能力可避免隐形扩大读取范围和误报 clean/dirty。

## ADR-015：早期阶段采用最小安全底线并允许功能 gate 并行

- 日期：2026-08-03
- 状态：接受，MVP 发布前复审
- 决定：实验原型保留授权根目录 containment、只读、不执行发现内容、不回显原文、确定性输出与显式 limitations；Windows reparse-point、并发替换、真实三平台 CI、供应链与分发级加固不再作为继续 Phase 2 只读功能的前置阻塞。
- 原因：当前首要问题是验证用户是否能从“实际加载哪些指令、按什么顺序”获得价值。让所有发布级平台证明串行挡住只读纵向切片，会延迟产品反馈，但不会显著降低该切片的本地写入风险，因为它没有写入或执行能力。
- 限制：此决定不降低 Apply 的快照、hash 复核、事务、回退和审批要求，也不允许扩大扫描根、执行配置内容、隐式读取用户目录或把 experimental 子能力宣传为完整 adapter 支持。

## ADR-016：Effective Context 的未知语义必须成为结构化 PARTIAL

- 日期：2026-08-03
- 状态：接受
- 决定：Context schema v2 使用 `COMPLETE/PARTIAL` 和稳定 finding code 表达当前请求范围内的可信度。未提供 target 的 path rule、import 缺失/循环/外部批准未知、symlink 未建模、解析预算和双 Claude project-memory 歧义不能只写说明后返回成功；CLI 对 `PARTIAL` 返回退出码 3。
- 原因：这个工具会被 UI、转换器和后续自动化消费。自由文本 limitation 无法阻止下游把不完整预测误当成宿主真实上下文。
- 补充：`COMPLETE` 只表示已声明的 experimental project-semantics 范围完整，不表示已覆盖用户、managed 或全部宿主运行时层。`orderingModel` 单独说明 Codex 的 root-to-CWD precedence 与 Claude 的拼接/规则顺序近似。

## ADR-017：有效载荷精确重复与指令级启发式分析分层

- 日期：2026-08-03
- 状态：接受
- 决定：新增 Analyze schema v1，由 Codex/Claude Effective Context 投影宿主无关 Instruction IR。Context schema v2 保持原契约；两套 schema 分别版本化，Analyze 报告显式声明其 context schema 版本。
- 确定性边界：只有 active source 的 effective payload hash 与 included length 同时相等，且有效作用域重叠时，才报告 `EXACT_EFFECTIVE_DUPLICATE`。Codex 截断源按实际纳入预算的 byte slice 计算 effective hash，完整文件 hash 只表示 revision。
- 启发式边界：规范化指令重复与中英文直接极性冲突分别报告为 `NORMALIZED_DIRECTIVE_DUPLICATE`、`DIRECT_POLARITY_CONFLICT`，certainty 固定为 heuristic candidate；它们是人工审阅线索，不是自动删除、覆盖或转换依据。
- 隐私边界：默认 Analyze JSON 不输出原文、normalized text 或 `realPath`，只输出稳定 ID、logical path、hash、长度、scope、activation evidence、provenance 与 finding reference。
- 运行边界：分析保持只读、不执行配置内容、不写文件、不生成转换结果；启发式 finding 不单独导致非零退出，只有 context/IR 为 `PARTIAL` 时返回退出码 3。
- 原因：文件字节相等可以确定性证明，但自然语言规范化和否定模式只能给出保守候选。将两者混为“精确冲突/重复”会诱导 UI 或后续自动化采取不可证明的破坏性整理动作。
- 放弃：把归一化文本相等称为 exact duplicate；将正文或 normalized text 暴露在默认 JSON；让多个 import parent 复制 source node；在 analyzer 阶段直接改写或转换目标文件。

## ADR-018：窄宿主语义必须携带稳定 profile ID

- 日期：2026-08-03
- 状态：接受
- 决定：当前 Codex、Claude Code 项目语义分别使用 `codex-project-semantics-v1`、`claude-code-project-semantics-v1`；Context 与 Analyze 输出都携带 profile ID。若语义发生不兼容变化，应新增 profile 或执行明确 schema/profile 迁移，不静默复用旧 ID。
- Conformance：使用独立的 Codex、Claude 和 analyzer 测试 main 固定 profile 行为；`scripts/run-conformance.sh` 只在全部用例通过后输出 schema v1 `PASS` 报告。
- 边界：profile 只证明仓库声明的项目级切片和 fixture，不等于已覆盖用户/managed 层、所有宿主版本、lossless parse、native validation 或完整 Read maturity。
- 原因：稳定 profile 是后续 ConversionPlan、缓存、审计与 UI 解释的输入身份；仅记录 host 名称无法识别行为漂移，也无法安全复现转换依据。

## ADR-019：ConversionPlan 与 renderer/apply 权限分离

- 日期：2026-08-04
- 状态：接受
- 决定：ConversionPlan schema v1 只表达 `CONVERSION_PREVIEW`，并强制 `writesPerformed=false`、`applyEligible=false`。planner 只消费 `COMPLETE` Instruction IR 和目标 metadata inventory；candidate bytes 不进入默认 JSON，目标现有正文也不进入报告。
- 映射边界：`EXACT` 必须同时通过目标校验、semantic round-trip 和安全 capability delta；任何 `NOT_RUN` 都不能被解释为成功。`ASSISTED` 必须有未决问题，`UNSUPPORTED` 必须有 blocking loss 且不能有 candidate。
- 当前实现：Codex ↔ Claude Code 各有一个 recipe v1。根 `AGENTS.md → CLAUDE.md` 的引用包装可在内存渲染，但仍仅标为 `COMPATIBLE`；其他 portable instruction 因 content-free IR 不保留正文而通常为 `METADATA_ONLY/ASSISTED`。policy、hook、plugin、permission 与 executable source 保持 report-only。
- 目标冲突：bounded probe 只在授权根目录内读取普通文件的 hash/长度，不跟随 symlink；现有目标、invalid target 和 outside-scope 都产生显式 conflict/review state，不执行覆盖或 merge。
- 原因：计划的可解释性可以先提供用户价值，但“能描述转换”不等于“已可靠渲染、验证或可写”。把 contract/planner 与 renderer/transaction 分门能防止 capability UI 或后续自动化提前开放 Apply。
- 放弃：把 source 文件直接复制/改名当作转换；把 `NOT_RUN` 当作兼容；为 plan-only 切片建立临时 staging 文件；因 Codex/Claude 已有双向 recipe 就提升整体 adapter maturity。

## ADR-020：Skills 生命周期是主用户旅程，配置治理是确定性内核

- 日期：2026-08-04
- 状态：接受
- 决定：产品前台升级为本地优先的 Skills 生命周期助手，覆盖 Create、Organize、Route、Evaluate、Improve、Govern。现有 scanner、Effective Context、Instruction IR、Host Adapter、conversion、ChangeSet、transaction 与 recovery 不被替换，而是成为确定性治理内核。
- 数据边界：保持 content-free `InstructionIr v1` 稳定；新增独立版本化的 SkillPackage、Blueprint、Route、Eval、Trace、Revision 与 Agent contract schema。
- 原因：用户价值已从“整理/转换文件”扩展到“让重复工作形成可靠能力并持续演进”，但生成内容若绕开现有验证与恢复链会破坏产品最重要的可信性。
- 放弃：把产品缩成 Markdown 生成器；建立始终加载的超级 Skill；把所有正文和生命周期对象塞进现有 Instruction IR。

## ADR-021：Hook 是可选事件传感器，不是历史学习或写入引擎

- 日期：2026-08-04
- 状态：接受
- 决定：Codex/Claude Hook 最多发送有界、脱敏、可丢失的事件 envelope 到本地 queue。完整历史导入、脱敏、聚合、LLM 候选生成、eval、promotion 和写入在显式 CLI/App 与离线 worker 中完成。
- 原因：Codex transcript 格式不是稳定 Hook API、command Hook 不支持真正 async、`SessionEnd` 预算短；Claude transcript 可能异步滞后，async Hook 也不能撤销副作用，并且 Hook 以当前用户权限运行。
- 禁止：Hook 内进行 LLM/API 调用、完整 transcript 解析、Skill/AGENTS/settings 修改、回归测试、Git 操作、项目脚本、网络上传、审批或权限提升。
- 放弃：把 `SessionEnd` 当作可靠的实时自学习事务；用 Hook 替代 app-server/显式 importer、ChangeSet 或恢复仓。

## ADR-022：Skill 改进采用评测门控的不可变候选，不做 live 自修改

- 日期：2026-08-04
- 状态：接受
- 决定：历史、reflection、textual gradient 或 evolutionary search 只能生成带 parent hash、来源 trace 和生成 provenance 的不可变候选。候选必须经过冻结的 baseline、正向/负向触发、paired repeated trials、holdout、安全回归、人工 Diff 审批和普通 ChangeSet 才能晋级；只对后续新会话生效。
- 原因：研究支持在有界 benchmark 上生成并选择候选，但不支持生产环境持续改写 live Skill。近期研究还显示 Skill 存在任务/模型依赖的负迁移、路由失败和 LLM judge 不可靠。
- 放弃：一次失败立即固化为长期规则；让优化器修改 eval；单一 LLM 自评后发布；用单一综合分掩盖成本、安全或回归；跨模型/版本复用未经重测的“全局最佳” Skill。

## ADR-023：大 Skill 按职责和证据分解，Skill Manager 采用 catalog/router 形态

- 日期：2026-08-04
- 状态：接受
- 决定：只有在触发意图、输入输出、成功标准、复用边界、权限边界或实测负迁移可分离时提出拆分；否则使用 references/assets/scripts 做 progressive disclosure。Manager 先按 scope、host、version、trust、capability 确定性过滤，可选 LLM 只重排剩余候选。
- 原因：行数不是能力边界，机械拆分不保证更少 live context 或更好结果；当前证据更支持专用、正确路由、版本匹配的 Skill，而非更大的全能说明书。
- 放弃：按固定行数自动切割；让 Router 自动安装或启用缺失 Skill；构建一个拥有全部权限的常驻“Skills 管理专家”。

## ADR-024：ConversionPlan v2 只为可证明的 canonical wrapper 记录验证证据

- 日期：2026-08-04
- 状态：接受
- 决定：ConversionPlan 升级为 schema v2，Codex/Claude recipe 升级为 v2。`TargetCandidate` 增加 renderer、target validator、semantic round-trip 和 metadata target review profile，并让每项已运行证据绑定 candidate SHA-256。最终 mapping/plan identity 绑定 candidate、existing target、validation 与 capability delta。
- 当前可证明子集：仅单一、完整、根级 Codex `AGENTS.md` 到 canonical Claude `CLAUDE.md` wrapper `@AGENTS.md\n`。候选只存在于受限内存，target validator 要求 canonical 结构，round-trip 证明 root scope、payload hash/length 与 import 关系保持；mapping 仍为 `COMPATIBLE`，automatic invocation 保持 `UNKNOWN`，所以不会升级为 Exact 或 Apply。
- 目标审阅：absent、identical、conflict、invalid、outside-scope、changed-during-probe 分开建模。metadata hash 相同只说明 no-op；不同只说明需要人工内容审阅，不生成 merge。unsafe/stale target 和失败验证使 CLI 返回 3。
- 不变量：metadata-only 不能宣称正文验证通过；target validation 未通过时 round-trip 不能通过；unsafe target 永远不能 `fullyValidated`；candidate bytes、source/target 正文和 `realPath` 不进入默认 JSON。
- 放弃：未经 validator 调用直接写 `PASSED`；用现有磁盘 target 代替 virtual candidate 做 round-trip；为扩大双向范围而把正文塞进 `InstructionIr v1`。

## ADR-025：当前实施收敛为 Codex-first 单资产闭环

- 日期：2026-08-04
- 状态：接受
- 决定：近期只按 `Inspect → Draft → Diff/Export → Simple Apply/Rollback` 完成一个 Codex 项目资产闭环，先做 Skill，再扩展 `AGENTS.md`、Agent TOML 与 Rule/Policy。
- 边界：Claude Code、主流 vibe-coding 宿主、双向转换和 Skills 生命周期长期目标不取消；Host Registry、IR 和现有 conversion contract 保留，但不继续横向扩建。
- 原因：项目已有可靠治理内核，但普通用户尚不能完成创建、审阅、应用和回退。先验证一个完整用户任务，比继续增加 schema、adapter 和研究模块更能降低产品风险。

## ADR-026：初期写入采用单文件 Simple Apply/Rollback

- 日期：2026-08-04
- 状态：接受；S3a fixture 协议已实现，普通项目入口未实现
- 决定：首个写入切片只处理一个项目内明确路径；保留 authorized-root containment、symlink 拒绝、preimage hash 复核、原始字节备份、同目录原子替换、写后校验和 rollback hash guard。
- 暂缓：跨文件事务、SQLite journal、长期 vault、Recovery Center、崩溃补偿编排和自动 Git 操作。
- 原因：用户已允许原型期不采用最严格的发布标准，但文件不丢失和不覆盖并发修改仍是最低产品合同。

## ADR-027：冻结未经用户闭环验证的平台能力

- 日期：2026-08-04
- 状态：接受
- 决定：通用/N→N conversion、Git/GitHub、Wave adapters、Router/catalog/gap、trace/history/evolution、hooks/plugins 和 Python optimizer 进入 frozen 或 research backlog。
- 解冻条件：Codex 核心闭环已可用，有明确用户请求或使用证据，能力不会阻塞核心体验，并且具有一个小而清楚的验收标准。
- 说明：冻结是实施优先级，不是删除长期产品承诺；已有源码和研究记录继续保留。

## ADR-028：最终产品采用桌面优先的本地 Web UI

- 日期：2026-08-04
- 状态：接受
- 决定：Vue 3 作为界面层，Java 21 作为本地核心；开发期由 loopback-only Java 服务提供本地页面，发布时优先用 `jpackage` 携带 Java runtime 与 Vue 静态资源，形成无需用户预装 JDK 的独立应用。
- 原因：纯在线网页无法在不引入高权限浏览器扩展或本地伴随进程的情况下，可靠完成任意项目目录授权、原子替换、原始字节备份、回退和离线隐私合同。Vue 仍保留 Web 技术的开发效率和可测试性。
- 安全边界：本地服务只绑定 loopback，采用启动期随机 token、严格 Origin 校验和无宽泛 CORS；浏览器页面不能绕过 Java 的路径、审批和写入策略。
- 后续复审：完成四页 MVP 后，以安装包体积、启动速度、文件选择器和窗口体验数据决定是否增加 Tauri/其他薄 WebView 壳；不在当前阶段引入 Electron 或 Rust 依赖。

## ADR-029：Skill 引用图采用有限、内容不回显的 inline profile

- 日期：2026-08-05
- 状态：接受
- 决定：Codex Skill Inventory schema 升级为 v2，以 `codex-skill-inline-reference-v1` 解析 `SKILL.md` 正文内的 inline link/image，并输出 source、line/column、类型和 `RESOLVED/MISSING/UNKNOWN`。只有 `RESOLVED` 输出规范化后的包内 target logical path；`MISSING/UNKNOWN` target 为 null。supporting files 继续只枚举路径，永不打开、执行或递归解析。
- 安全语义：外部 http/https/mailto 和纯 anchor 不属于本地图；绝对路径、Windows/UNC/backslash、未知 scheme、NUL 和 traversal 被阻断且不回显原始 destination。只有完整 supporting inventory 才能断言 `MISSING`；部分 inventory 必须使用 `UNKNOWN`。
- 原因：S0 需要验证 progressive disclosure 的 supporting-file 连接关系，但引入完整 Markdown AST、读取 supporting content 或递归依赖 DAG 会扩大依赖、隐私和路径攻击面，也会提前侵入 S1/S2。
- 放弃：声称 full CommonMark；解析 reference-style/HTML/autolink；percent-decode；读取或 hash supporting content；自动修复链接；跨 package/宿主引用；用枚举不完整的结果断言 missing。

## ADR-030：S1 persistence triage 采用显式向导信号，不采用自由文本关键词分类

- 日期：2026-08-05
- 状态：接受
- 决定：`persistence-triage-v1` 保留用户自然语言 goal/description，但分类只消费一次性/持续性、重复流程、明确触发、成功标准、隔离职责、确定性强制和 executable automation 等显式向导事实。证据不足返回 `UNKNOWN/NEEDS_CONFIRMATION`；高风险自动化返回阻断提案。只有用户确认 `SKILL + PROJECT` 且字段完整时才构造 `SkillBlueprint v1`。
- 内容边界：Blueprint 会包含用户主动提供的结构化内容，因此不能声称 content-free；顶层分别记录 `workspaceContentIncluded=false`、`userProvidedContentIncluded=true` 和 `rawRequestIncluded=false`。S1 不读取其他项目内容、不启动 LLM/网络/子进程、不生成 `SKILL.md` candidate、不写目标文件且不可 Apply。
- 原因：自由文本关键词会被否定、引用、中英混合和“编辑已有 Skill”的一次性请求击穿；显式向导信号虽然需要多一步交互，却能提供稳定证据、保守降级和可测试的 UI/API 合同。
- 放弃：用关键词或单次 LLM 输出直接决定持久化类型；为不完整 Skill 构造伪 Blueprint；把 Prompt/Instruction/Agent/Tool 请求强塞进 `SkillBlueprint`。

## ADR-031：S2 采用确定性单文件 renderer 与显式 stdout 导出

- 日期：2026-08-05
- 状态：接受
- 决定：S2 只消费已确认的 `SkillBlueprint v1`，用版本化确定性模板在内存生成一个 Codex project `SKILL.md`，并让独立静态 validator 检查最终 bytes。默认 JSON 只含 candidate metadata/hash；正文、synthetic new-file Diff 与 Prompt 必须通过显式 stdout export 请求。
- 内容与风险边界：frontmatter 只有 `name` 和包含 trigger/exclusion 的 `description`；3+3 fixture 不进入运行时正文，也不被宣称为真实模型路由 eval。只有路径的 supporting-file proposal、tools、额外 permission 或 elevated risk 进入 `REVIEW_REQUIRED` 并禁止 raw export。本阶段不读取或写入目标、不生成 supporting content、不运行 LLM/网络/进程；便捷 launcher 只在本仓库 `build/` 编译 class cache。
- 原因：先证明 Blueprint 能稳定变成可审阅的原生字节，比同时引入模型起草、分解、多宿主 renderer 或写入事务更直接验证核心用户价值；默认不输出正文也保持与既有 metadata-first 治理合同一致。
- 放弃：S2 引入通用 Skill IR/renderer registry、Markdown AST/YAML 依赖、真实目标三方 Diff、LLM provider、Router/eval runner、自动 supporting file 或 Apply。

## ADR-032：S3a 先用不可误用的 fixture 证明单文件事务

- 日期：2026-08-05
- 状态：接受
- 决定：首个写入实现没有 CLI，只接受 marker 内容精确匹配的临时 fixture workspace，并要求另一个彼此不包含、同样带 marker 的 state root。只处理 S2 `READY` 的单个 `.agents/skills/<name>/SKILL.md`，支持 absent/existing/no-op/blocked 探测、真实完整替换 Diff、plan/root/candidate/preimage/diff 绑定、写前复核、外置原始字节快照、同目录 staging、原子 move、写后验证、故障注入和当前 hash guard rollback。
- 授权语义：当前 approval token 是完整 plan 的确定性完整性绑定，不是密钥签名、用户身份或可独立授予权限的 capability；Apply 会再次计算实际目标 Diff。真实产品仍需在 UI/API 层取得明确目录授权与用户批准。
- 已解决审查项：workspace identity 绑定 canonical path、root 与 marker 的文件身份/时间和 marker 内容 hash，同路径删除重建会令旧 plan 失效；`PREPARED` 事务不能回退后来碰巧具有 candidate bytes 的用户文件；自动恢复 receipt 会如实标记已经发生目标写入。
- 未通过项：`ATOMIC_MOVE` 不是 compare-and-swap，最后一次检查到文件操作之间仍可发生目标内容或父路径组件替换，包括 symlink/junction swap；真实入口需要 OS 级 CAS 或 dir-handle-relative 防 TOCTOU。move 成功到 manifest `APPLIED` 持久化之间也仍有崩溃窗口；尚无启动恢复、durable journal、Windows junction/reparse 完整 fixture 或长期 vault。因此 Gate 5 只算 fixture 子切片，Gate 6 保持未开始。
- 原因：先用强 marker 和无公开入口隔离真实用户文件，可以验证事务 API 与失败语义，同时不把尚未具备 crash/concurrency 证明的原型包装成可用 Apply。

## ADR-033：S3b1 以 COMMIT_INTENT 恢复中断的 fixture apply

- 日期：2026-08-05
- 状态：接受
- 决定：仍保持 fixture-only、无 CLI。manifest v2 在字段语义外增加完整性 hash，并持久化 candidate、preimage hash/identity、目标权限和事务状态。Apply 在目标 move 前先原子写入 `COMMIT_INTENT`；stage 名只由 transaction UUID 推导。显式 `recoverTransaction` 对 `PREPARED` 做无目标写入的 abort，对 `COMMIT_INTENT + exact preimage + candidate stage` 完成 apply，对 `COMMIT_INTENT + candidate target + absent stage` 只补记 `APPLIED`；其他组合 fail closed。
- 审计语义：恢复 receipt 分开记录本次 target write 与 state write。`rollbackAvailable` 只有在 candidate、权限和 existing snapshot 均验证后才为 true。恢复前仍检查 preimage identity，不能因“字节相同”覆盖已被重建、改时间或改权限的文件。
- 故障语义：新增 intent 后、move 与 APPLIED 之间的独立注入点；一旦 intent 已持久化，move 异常必须保留 stage 并返回 `RECOVERY_REQUIRED`，不能降级为普通 pre-write failure。`PREPARED/COMMIT_INTENT` 的 rollback 要求先 recover。
- 能力边界：这里只证明 service process-crash reconciliation，不宣称 power-loss durability。Java 21 `SecureDirectoryStream` 是 provider 可选能力，在当前 macOS/Windows 基线上不可作为统一后端，而且不能提供 leaf CAS；父路径 swap、conditional replace/delete、rollback intent、批量启动恢复和 directory fsync 仍阻断 Gate 6。

## ADR-034：S3b2 采用对称 rollback WAL 与显式 pending discovery

- 日期：2026-08-05
- 状态：接受
- 决定：manifest v3 增加 `ROLLBACK_INTENT`、rollback source identity 与 result identity。existing target 先构造并校验确定性 rollback stage，再持久化 intent、复核 candidate 与 stage 后原子替换；原本 absent 的目标先持久化 intent，再做 guarded delete。`recoverTransaction` 仅接受“source 仍匹配且 stage 完整，完成 rollback”或“result 已匹配且 stage 已消失，只补记 ROLLED_BACK”两种唯一组合，其他组合 fail closed。
- Discovery：新增显式、只读的 `scanPendingTransactions`，只查看 state root 直接子项，严格接受 canonical v4 transaction UUID，使用 direct-entry 与 manifest 双预算、稳定排序和 cursor；只报告 PREPARED、COMMIT_INTENT、ROLLBACK_INTENT 及恢复方向，不输出候选/快照内容，不遍历子树，也不自动调用恢复。
- 审计与边界：rollback receipt 分开记录本次确认发生的 target/state write。同名预存 rollback stage 不归本事务所有且不得被清理；intent 结果不确定时保守保留 stage；move 前再次校验 stage identity。fixture manifest 不提供跨 schema 迁移承诺。本切片仍只证明 service process-crash reconciliation，不证明 power-loss durability、OS 级 conditional replace/delete、父路径 CAS 或 Windows junction/reparse 安全，因此 Gate 6 保持关闭。

## ADR-035：Gate 6 先开放 existing Codex Skill 的受控真实入口

- 日期：2026-08-05
- 状态：接受（过渡切片）
- 决定：公开 `preview → approve/apply → transaction rollback` CLI，但只允许替换用户明确授权 workspace 中一个已存在、严格 UTF-8/LF 且有结尾换行的 `.agents/skills/<name>/SKILL.md`。不会创建目标、父目录、supporting files 或多文件 ChangeSet。
- 授权与审计：Preview 的真实 Diff 必须显式导出；approval token 绑定 root、logical path、candidate、preimage identity/hash/权限与 Diff，但不是身份凭证。Apply 要求 workspace 外的可信本地 state root，保存 byte-exact snapshot 与状态 manifest。回执分别报告 target/state write、rollback availability 与 recoveryRequired，任何 move 后故障不得伪装成零写入。
- 最低保护：拒绝链接/reparse-like 路径、stale preimage、非法 state 拓扑和非 canonical 目标文本；同名预存 stage 不归事务所有且不得清理；正常 APPLIED rollback 绑定 apply 后 identity；manifest 仍处于 apply intent 且目标已是候选时允许显式恢复性 rollback。
- 限制：真实入口尚未复用 fixture v3 的完整 crash-recovery scanner，不提供跨进程锁/OS CAS、dir-handle-relative 操作、directory fsync、长期 vault 或完整 Windows junction 证明。它是 Gate 6 的可用性验证，不是发布级文件事务声明。

## ADR-036：Vue 之前先固定 loopback typed transport

- 日期：2026-08-06
- 状态：接受
- 决定：第一刀 UI 基础设施只实现 Java 21 loopback API，不同时搭建 Vue 页面。HTTP controller 直接调用现有确定性 service，使用 typed response；禁止启动 CLI 子进程或把 stdout Diff 当结构化协议。state root 是服务启动参数，不能由每次浏览器 Apply 请求任意指定。
- 接口与边界：只提供 runtime、existing Codex Skill preview/apply/rollback。服务精确绑定 `127.0.0.1` 随机端口；mutation 同时要求精确 Host、同源 Origin 和进程期随机 bearer token，无 CORS。请求有严格 UTF-8、flat JSON 和 48 KiB 预算，错误不回显异常、raw request、Diff、token 或日志路径。
- 原因：先稳定 transport 和状态映射，Vue 才能把 BLOCKED、STALE、RECOVERY_REQUIRED 与网络错误正确分开；同时避免前端通过 shell 或任意 state path 绕过 Java 权限边界。
- 暂缓：Vue 静态资源与 token bootstrap、文件选择器、路由、桌面壳、TLS/局域网、WebSocket、数据库、自动恢复、多宿主和安装包。Gate 7 只前进到 `UI transport started`。

## ADR-037：首个真实 UI 采用同源 Vue 单页和 fragment bootstrap

- 日期：2026-08-06
- 状态：接受（实验入口）
- 决定：首个页面只完成 `workspace + guided request → preview/diff → confirm/apply → receipt/rollback` 的纵向闭环，不同时建设路由、全资产 dashboard、文件编辑器或桌面壳。Vue 3 + TypeScript 负责内存状态机，Java 21 继续独占文件系统与事务能力。
- 会话边界：Java 生成 256-bit 进程期 token，并只通过同源启动 URL 的 fragment 交给页面；fragment 不会进入 HTTP 请求，页面读取后立即 `replaceState` 清除，token 不进入 localStorage/sessionStorage。输入变化会废弃旧 preview 和 approval；rollback 绑定 apply 当时的 workspace，而不是用户后来编辑的输入框。单页同一时刻只保留一个活动回退锚点；用户必须先回退，或明确保留当前变更并关闭页面回退入口，才能批准下一笔写入。
- 静态边界：Java 只从启动时固定的 canonical `ui/dist` 读取 `index.html`、hash asset 和 favicon，不提供目录浏览或 SPA 任意 fallback；HTML no-store，hash asset immutable，并发送 CSP、nosniff、DENY frame 与 no-referrer。UI 不引用外部字体或 CDN。
- 依赖与暂缓：Vue/Vite/TypeScript/vue-tsc 只用于 `ui/` 构建，Java 核心保持零第三方依赖。当前仍要求用户粘贴绝对路径和结构化 guided request；文件选择器、自然语言表单助手、自动打开浏览器、安装包与恢复中心留到后续 gate。
- 产品语言：界面不使用宣传式 AI 口号，也不把 `READY_REPLACE`、`STALE_PREIMAGE`、`RECOVERY_REQUIRED` 等协议值当作主文案。用户操作使用“预览变更、应用更改、恢复原文件”等直白语言；原始状态和结果码保留在可展开的技术详情中，确保可诊断性不因简化文案而丢失。

## ADR-038：普通入口采用确定性 Skill 表单，高级入口保留原协议

- 日期：2026-08-06
- 状态：接受
- 决定：existing Codex Skill 页面默认显示分区中文表单，覆盖名称、用途、目标、输入输出、适用与排除范围、边界、3+3 判断例、步骤、完成标准和检查方法；固定注入 Skill/project 分类、`NONE` permission 与 `LOW` risk，并按稳定顺序序列化成既有 guided request。Java parser、HTTP schema、preview/apply/rollback 状态机均不改变。
- 校验与兼容：浏览器在提交前检查名称、必填项、3+3 数量、重复与交叉、每类最多 16 项、单项长度、160 行和 32 KiB；Java 仍是最终权威。高级配置保留原始 `key: value` 输入，首次进入时可取用当前表单生成内容，两种输入分别保存，避免无提示地覆盖高级字段。
- 原因：旧页面公开了内部协议，却只提示三个字段，而核心要求十余类字段，普通用户无法仅凭页面完成第一次预览。表单修复激活路径且不复制后端事务能力；完整示例可用于熟悉流程，也用于确定性浏览器验收。
- 下一步：把现有 Codex Skill inventory 通过只读接口接入页面，让用户先看到项目中的 Skill 并选择目标；随后再处理创建新 Skill、安装包和其他资产类型。

## ADR-039：Inventory 只投影目标选择元数据，并绑定最终请求名称

- 日期：2026-08-06
- 状态：接受
- 决定：新增 token/origin 保护的 `POST /api/v1/skills/inventory`，直接调用已有 `CodexSkillInventoryService`。HTTP envelope v1 只返回整体 COMPLETE/PARTIAL、目录名、逻辑路径、包状态、是否可进入预览、配套文件数量、风险枚举和 finding 数量；不返回正文、declared name、hash、reference 或 finding 详情，也不产生写入。
- 目标一致性：用户从项目列表选择目录名后，普通表单名称由选择结果填写并只读；高级配置的唯一 `name:` 必须与所选目标一致，否则禁止预览。workspace 改动立即废弃旧 inventory 与选择；请求序号阻止旧响应覆盖新路径。
- 语义边界：`availableForPreview` 只表示 inventory 信息足以让用户选择并尝试真实预览，不承诺目标一定可替换；编码、换行、当前状态和最终 Apply eligibility 仍由已有 preview service 判断。INVALID 包可进入预览以便用完整候选修复，PARTIAL 包不可选择。
- 原因：目标选择必须参与实际 path 推导，不能成为装饰；同时复用现有 content-free inventory 比另建目录列表扫描器更小，也保留稳定排序和 partial-result 语义。

## ADR-040：真实单 Skill 入口以显式操作支持创建

- 日期：2026-08-06
- 状态：接受（过渡切片）
- 决定：网页 preview/apply 必须显式携带 `CREATE` 或 `UPDATE`；旧 CLI 默认 `UPDATE` 保持兼容。`CREATE` 只接受 absent target，`UPDATE` 只接受 existing target，二者不能根据一次磁盘探测互相降级。创建计划使用 `READY_CREATE`、`--- /dev/null` Diff，并把操作、candidate、目标和缺失父目录拓扑绑定到 approval token。
- 目录与回退：preview 不创建目录。Apply 在外置事务目录建立后逐层 `createDirectory` 并复验 containment/link 状态，manifest 记录本事务实际创建的目录；rollback 必须先匹配 apply 后的 identity/hash/权限，再删除候选，并只逆序删除记录中仍为空的目录。预先存在、被替换、成为链接或新增了内容的目录都不删除。
- 产品取舍：创建和更新复用同一 Blueprint、renderer、Diff、批准、receipt 与 rollback 页面，不增加第二套向导。该能力仍只覆盖一个 Codex project `SKILL.md`；supporting files、多文件、Claude、LLM 与桌面打包不进入本切片。
- 剩余边界：真实 manifest v2 仍没有接入 fixture v3 的完整 pending recovery 状态机，也不提供 OS conditional create/delete、directory fsync 或完整 Windows reparse 证明。因此它提升核心可用性，但不关闭 Gate 6。

## ADR-041：自定义 Skill 采用受验证原文候选，不经模板往返

- 日期：2026-08-07
- 状态：接受（Codex-first 过渡切片）
- 决定：非 template-v1 的现有 Codex Skill 使用独立 `RAW_SKILL_MD` candidate mode。页面保留并编辑读取到的完整文本；preview/apply 都重新运行 `codex-raw-skill-static-v1`，随后把最终字节交给与 guided draft 相同的单文件事务边界。raw 与 guided 字段互斥，raw 只允许 UPDATE，不提供原文创建。
- 验证边界：候选必须是 `.agents/skills/<name>/SKILL.md`、名称与目录一致、UTF-8/LF、有末尾换行、最多 128 KiB，并包含简单有界的 `name` 和 `description` frontmatter。validator 不尝试实现完整 YAML，不展开 alias/tag/block scalar，不解析或执行正文；无法静态确认的 frontmatter 保守拒绝。
- 并发与回退：raw preview 强制提供内容读取返回的 source SHA；不匹配时在批准计划生成前返回 stale。preview 与 apply 重新构造同一候选，approval token 继续绑定 candidate/preimage/Diff；成功应用生成现有事务记录，rollback 恢复原始字节。切换目标前的草稿提示是防误操作 UX，不替代服务端绑定。
- 原因：把任意自定义 Skill 投影到 Studio 模板会丢失未知字段和正文结构；直接绕过 validator 又会扩大事务可写面。独立最终字节候选既保留未知内容，也维持可测试的最小格式与目标约束。

## ADR-042：Skill Catalog 采用九类固定 taxonomy 与保守人工回退

- 日期：2026-08-07
- 状态：接受（Codex-first 第一版）
- 决定：技能库使用九个稳定类别：库和 API 参考、产品验证、数据查询分析、业务流程自动化、代码脚手架、代码质量与审查、CI/CD 与部署、Runbook 排障手册、基础设施运维。“待整理”是人工处理队列，不是第十类。`dev-skill-taxonomy-v1` 只读取已有 inventory 中单个 Skill 的目录名与 frontmatter `description`，使用维护的高精度短语、分数和领先幅度；证据不足或含混时返回 `UNCLASSIFIED`，不猜测。
- 数据边界：HTTP 分类结果不含正文或 description，只含逻辑路径、source SHA、建议类别、置信度、分数和证据来源；不读取 supporting files、不执行 Skill、不调用 LLM、不写工作区。网页人工覆盖绑定 `logicalPath + sourceSha256`，第一版只在当前会话生效，不伪装成已经修改文件。
- 交互：桌面端提供 3×3 固定桶与待整理拖拽；触屏、键盘和无拖拽环境使用原生选择器完成相同操作。分类投放和详情出现采用短动画，并尊重 `prefers-reduced-motion`。Claude Code 转换入口在 adapter 未达到对应 maturity 前保持禁用，不用假结果填充流程。
- LLM 边界：未来只有在规则无法覆盖且用户主动允许时，才可把 name/description 发送给可选建议器；输出必须通过同一 taxonomy schema，且仍进入人工确认，不得直接修改 Skill 或训练 taxonomy。是否需要该能力由真实未分类率与人工校正数据决定。
- 原因：固定分类提供可记忆的信息架构，规则优先让常见结果可解释、可测试、离线可用；人工队列比低阈值强行分类更适合管理工具。九类是当前产品 taxonomy，不宣称是 Anthropic 官方通用标准。

## ADR-043：产品改为纯桌面工作台布局，并冻结额外安全扩建

- 日期：2026-08-07
- 状态：接受（覆盖 ADR-042 的手机端交互要求）
- 界面决定：工作台切换、项目路径、读取动作和当前状态统一放入固定左侧栏。技能库主体使用左侧正方形 3×3 分类区与右侧待整理区，待整理列表独立滚动；详情仍在当前工作区内打开。这样分类和人工处理可以在同一视线范围内完成，不再让横向空间被顶部控制条和下方长队列切碎。
- 平台决定：第一版只面向桌面窗口，设计基线为宽度至少 1080px。删除手机专用导航、单列重排与小屏隐藏策略；较窄桌面窗口只保证结构完整和可横向查看，不将“手机可用”列为发布指标。
- 安全取舍：保留当前已经进入核心链的项目范围检查、惰性读取、零写入预览、Diff、明确批准、stale 检查和回退。断电级 fsync、跨进程 CAS、完整 junction/reparse 证明与其他推演性加固停止占用近期产品路线，除非真实用户故障或分发要求重新触发。
- 下一步：优先把左栏项目路径替换为桌面壳的系统文件夹选择，再验证 `jpackage` 安装 alpha；不在浏览器中伪造无法得到绝对路径的文件夹选择体验。

## 待决问题

- [ ] 为正式工程选择 Java 构建工具与最小模块骨架；当前纯 JDK 脚本仅服务 Phase 1 spike。
- [ ] 选择 lossless TOML/JSONC/YAML/Markdown 编辑方案。
- [ ] 选择 SQLite 与跨平台安全存储方案。
- [ ] 验证 `jpackage` 分发尺寸和自动更新策略。
- [x] 第一实现选择 loopback HTTP；同进程 IPC 仅在桌面壳 spike 中复审。
- [ ] 设计 host-version compatibility fixture。
- [x] 定义 ConversionPlan schema v1、mapping grade 与 recipe versioning 规则。
- [x] 将 canonical wrapper 升级为 ConversionPlan/recipe v2，并绑定 renderer/validator/round-trip/review 证据。
- [ ] 在已建立的 Codex ↔ Claude 双向 plan fixture 上增加 renderer/target-validation/round-trip；Wave 1 adapter conformance suite 仍待定义。
- [x] 定义 inert Host Adapter API、capability vocabulary、路线等级、maturity gate 与 registry manifest v1。
- [x] 定义宿主无关 Instruction IR、provenance 与确定性/启发式分析边界。
- [ ] 定义 Prompt Export 的 JSON plan 附件格式。
