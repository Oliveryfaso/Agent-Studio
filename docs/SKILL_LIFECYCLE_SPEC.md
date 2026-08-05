# Skills 生命周期助手规划

- 版本：0.1（规划稿）
- 日期：2026-08-04
- 状态：长期方向保留；近期收敛为 Codex Project Skill Draft Workbench

## 1. 产品定位

当前产品入口是 **Codex Project Skill Draft Workbench**：先完成 Inspect、persistence triage、单个 Skill 候选、Diff/Export 和简单回退。长期仍升级为本地优先的 Skills 生命周期助手，把自然语言需求、重复工作和经用户确认的失败经验，转化为可路由、可评测、可审阅、可跨宿主管理、可安全回退的 Skill、项目 instruction、Agent contract 或一次性 Prompt。

现有配置治理能力不是旧方向，而是新产品的确定性内核：

```text
Skills Lifecycle Assistant
  ├─ Create：自然语言 / 向导 → Prompt / Instruction / Skill / Agent
  ├─ Organize：拆分、引用、去重、目录和依赖关系
  ├─ Route：按任务选择 Skill，解释未选原因并识别能力缺口
  ├─ Evaluate：触发、不触发、任务完成、成本与安全回归
  ├─ Improve：从经用户选择的 trace 生成候选修订
  └─ Govern：Diff、验证、审批、ChangeSet、快照、Apply、回退
         ↓
Existing Governance Kernel
  ├─ Scanner / path containment
  ├─ Effective Context / Instruction IR
  ├─ Host adapters / conversion
  └─ Transaction / recovery
```

产品不承诺“AI 写出的 Skill 一定更强”。正确承诺是：**帮助用户把经验写成更合适的能力载体，并用任务级证据验证它是否真的有用。**

## 2. Persistence triage：先决定该不该持久化

自然语言输入先分类，再生成文件：

| 输入特征 | 推荐产物 |
|---|---|
| 一次性目标或当前会话要求 | Prompt |
| 每次工作都必须知道的短规则 | `AGENTS.md` / 宿主 instruction |
| 有明确触发意图、重复步骤和成功标准 | Skill |
| 需要隔离上下文、独立职责或专门工具边界 | Agent contract |
| 可由 formatter、test、CI、schema 或权限可靠执行 | Deterministic tool / Policy 建议 |
| Hook、MCP、shell 自动化 | 高风险 executable proposal；默认不生成、不执行 |

分类器必须输出证据、置信度和备选项，并由用户确认。LLM 不得自行把一次性对话变成长期项目规则。

## 3. 创建工作流

```text
自然语言需求
  → 结构化追问或表单
  → PersistenceDecision
  → 用户确认产物类型和作用域
  → SkillBlueprint / AgentContractDraft / GuidanceDraft
  → 确定性模板生成骨架
  → 可选 LLM 生成不可信初稿
  → 静态校验
  → 正向、负向和边界触发用例
  → 候选预览与 Diff
  → 评测
  → 人工批准
  → 进入统一 ChangeSet
```

`SkillBlueprint v1` 至少包含：

- name、goal、scope、inputs、outputs；
- trigger contract、exclusions 和边界例子；
- 核心步骤、完成定义、验证方式；
- 所需工具/权限意图、风险等级；
- supporting files、host extensions 和来源；
- 至少 3 个 should-trigger 与 3 个 should-not-trigger 用例。

没有 LLM 时，向导、模板、静态检查与 Prompt Export 仍应完成完整的草案流程。LLM 只提高起草效率，不是产品可用性的前置依赖。

## 4. 大型 Skill 的分解

不按行数机械拆分。满足以下一个或多个条件时才提出拆分：

- 子任务可以被不同用户请求独立触发；
- 输入、输出或成功标准明显不同；
- 工具、权限或风险边界不同；
- 某部分可被多个工作流复用；
- 单一 description 已无法准确表达 should-trigger / should-not-trigger 边界；
- 评测显示某些模块对一类任务有益、对另一类任务造成负迁移。

若目标、输入输出、权限和验证仍相同，则保留一个 Skill，采用 progressive disclosure：

```text
skill-name/
├── SKILL.md        # 触发、边界、核心流程、验证、引用地图
├── references/     # 深层知识与平台差异
├── assets/         # 模板和静态资源
└── scripts/        # 仅确定性且单独审阅的辅助程序
```

分解器输出 `DecompositionPlan`，包含 keep/split/move-to-reference 决策、共享资源、依赖 DAG、触发冲突、上下文预算和未决问题。原 Skill 与拆分候选必须在同一组 eval 上对比；拆分本身不是成功指标。

## 5. Skill Manager 与 Router（研究 backlog，当前冻结）

Skill Manager 是目录、路由和评测服务，不是一个始终加载、可以调用所有能力的“超级 Skill”。

```text
任务
  → scope / host / version / trust / permission 确定性过滤
  → capability tree 与词法检索
  → top-K 候选
  → 冲突、依赖和上下文预算检查
  → 可选 LLM 仅对模糊候选重排
  → 单 Skill 或 DAG 路由
  → 保存选中与拒绝证据
```

路由必须使用结构化结果：

- `NO_ROUTE`
- `SINGLE_ROUTE`
- `AMBIGUOUS_ROUTE`
- `BLOCKED_BY_CAPABILITY`
- `INVALID_SKILL`
- `HOST_UNSUPPORTED`

Router 不自动安装缺失能力。Gap Detector 先把问题分类为 `NO_ROUTE`、`FALSE_NEGATIVE_TRIGGER`、`FALSE_POSITIVE_TRIGGER`、`AMBIGUOUS_ROUTE`、`WORKFLOW_STEP_FAILURE`、`MISSING_REFERENCE`、`STALE_INSTRUCTION`、`HOST_CAPABILITY_GAP`、`ENVIRONMENT_FAILURE` 或 `USER_GOAL_CHANGED`。只有用户确认的 gap 才进入创建或改进提案。

## 6. 基于历史的改进与演化（研究 backlog，当前冻结）

产品实现的是**评测门控的候选演化**，不是实时自修改：

```text
用户选择会话或反馈
  → 脱敏、去重、关联 Skill 版本
  → 人工确认 task outcome / gap
  → 冻结 baseline 与独立 eval suite
  → 生成不可变候选
  → 静态检查
  → paired repeated trials
  → holdout 与安全回归
  → Pareto 候选
  → 人工 Diff 审批
  → ChangeSet / snapshot / canary
  → 后续新会话生效
```

候选生成器可逐步加入：

1. 最小 critic patch：基于具体失败生成一个小修订；
2. OPRO / APE 风格：向模型提供历史候选及分数，生成新候选；
3. ProTeGi / TextGrad 风格：保存“批评 → 补丁”的可追踪文本梯度；
4. GEPA / EvoPrompt / Promptbreeder 风格：在离线预算内做种群、变异、交叉和 Pareto 选择；
5. ASSAY 风格：按任务/模型估计单个 Skill 的正负作用，优先抑制不合适路由而不是全局删除。

适应度不使用单一“能力分”：

```text
任务成功率
+ instruction / route compliance
+ positive trigger recall
+ negative avoidance
- token 与模型成本
- 延迟
- 安全违规
- 既有回归数
```

训练/开发用例与 holdout 必须隔离；优化器不能修改自己的评测集。第二个 LLM judge 仍是不可信证据，不能代替确定性验证和人工批准。

## 7. Hook 的正确角色

Codex/Claude Hook 只作为可选事件传感器，不作为历史数据库、LLM worker 或写入引擎。

原因包括：Codex transcript 格式不是稳定 Hook 接口，command Hook 当前不支持真正 async，`SessionEnd` 时间预算很短；Claude transcript 写入也可能落后于当前事件，async Hook 不能撤销已发生动作，并以当前系统用户权限运行。详见 [Codex Hooks](https://learn.chatgpt.com/docs/hooks) 与 [Claude Hooks reference](https://code.claude.com/docs/en/hooks)。

推荐链路：

```text
Codex / Claude event
  → 可选 hook：校验并发送有界 event envelope
  → 本地 append-only queue
  → 显式历史导入器
  → 脱敏、去重、session/turn 关联
  → 离线 eval worker
  → SkillUpdateProposal
```

Hook 中不得运行：LLM/API 调用、完整 transcript 解析、向量化、长测试、Skill/AGENTS/settings 修改、Git 操作、包安装、项目脚本、网络上传、审批或权限提升。Hook 必须 fail-open、幂等、严格超时，最多发送 event ID、脱敏 session/turn ID、event kind 与受控 source pointer。

Codex 侧优先使用文档化的 [app-server thread API](https://learn.chatgpt.com/docs/app-server#threads) 进行用户授权后的历史读取；跨宿主不存在统一稳定的完整会话接口，因此 importer 必须按宿主和版本实现。

## 8. 信任边界

LLM 可以分类模糊意图、起草正文、提出拆分图、对路由候选重排、聚类失败、生成候选修订和开放式质量解释。

LLM 不可以决定路径授权、扩大权限、选择要读取的私人会话、批准秘密出机、启用 Hook/Plugin/MCP、执行 Skill 脚本、修改 eval、宣称回归通过、批准自己的补丁或写入 live 配置。

所有 LLM 输出必须符合版本化 schema，并记录 provider、model、prompt version、输入摘要 hash 和 provenance。生成内容始终是 proposal。

## 9. 新领域对象

保留现有 content-free `InstructionIr v1`，不要把 Skill 正文硬塞进去。新增独立的版本化对象：

| 对象 | 用途 |
|---|---|
| `IntentCapture` / `PersistenceDecision` | 保存用户目标及 Prompt/Instruction/Skill/Agent 分类 |
| `SkillBlueprint` / `TriggerContract` | 定义 Skill 目标、边界、流程和触发用例 |
| `SkillPackageIr` | package、支持文件、hash、host extension 与 provenance |
| `DecompositionPlan` | 拆分/保留/引用移动和依赖图 |
| `RouteIndex` / `RouteDecision` | 能力目录、候选、拒绝理由与选择证据 |
| `ConversationTraceRef` | 脱敏 trace、宿主/模型/版本、结果和受保护内容引用 |
| `GapCandidate` | gap 类型、证据、影响用例和建议动作 |
| `EvalCase` / `EvalSuite` / `EvalRun` | 正向、负向、回归、安全与运行结果 |
| `SkillRevision` / `ImprovementProposal` | 版本化候选、父版本、来源 trace 与 eval delta |
| `OptimizationCampaign` / `MutationOperator` | 离线搜索预算、策略和 lineage |
| `SkillChangePlan` / `ApprovalRecord` | 进入现有 ChangeSet 之前的审批边界 |

正文通过受控 content reference 访问；默认元数据 JSON 继续只暴露路径、hash、大小、结构与脱敏摘要。

## 10. 模块边界

```text
skill-lifecycle/
  intent-triage
  persistence-classifier
  blueprint
  package-ir
  decomposition
  catalog
  router
  gap-detector
  trace-ingestion
  improvement-proposer
  skill-eval
  static-validator
  native-renderer

governance-core/
  scanner
  context-compiler
  instruction-ir
  analyzer
  host-registry
  adapters
  conversion
  changeset
  transaction
  recovery
```

Python 仅适合无写权限的离线 eval/优化 worker；Java 继续掌握路径、校验、ChangeSet、事务和 Apply。Vue 负责隐私预览、证据、Diff、批准、canary 和回退交互。

## 11. Skill lane gates

Skill lane 现在按一个用户闭环顺序推进，不再与平台 Gate 并行扩张：

| Skill Gate | 目标 | 写入能力 |
|---|---|---|
| S0 Inventory | Codex Skill package 只读发现、引用图、重复名、可执行风险 | 无 |
| S1 Blueprint Preview | 自然语言/向导 → persistence classification → `SkillBlueprint v1` | 无 |
| S2 Validated Draft | 确定性单文件模板、最终字节静态校验、stdout content/synthetic-diff/prompt export | 无；已完成 |
| S3 Single-file Apply/Rollback | fixture 事务闭合进程崩溃窗口；过渡 CLI 支持一个已存在 Codex Skill 的 preview/apply/rollback | 真实入口尚无自动中断恢复、创建/多文件与跨进程 CAS |
| S4 Native asset expansion | 将同一闭环扩展到 AGENTS、Agent TOML 和 Rule/Policy | 逐类型晋级 |
| S5 Claude lifecycle | Claude Skill 与 instruction 的同等闭环 | 逐类型晋级 |
| S6 Advanced lifecycle | 其他宿主、Router、eval、history improvement 与跨宿主转换 | 依据用户证据解冻 |

当前 `inspect` 用户入口与 Skill S0–S2 已完成最小闭环。S3 fixture API 用 manifest v3、确定性 stage 和显式 recover/scan 验证单文件事务。真实过渡 CLI 进一步允许用户对普通项目中的一个已存在 Codex Skill 执行真实 Diff、批准替换和 guarded rollback；其 state root 必须在 workspace 外且受本地用户信任。真实入口尚未接入 fixture 的自动 interrupted-process recovery，也不承诺断电级目录持久性、OS 级 CAS/防 TOCTOU 或完整 Windows reparse 防护。

## 12. 最小产品切片

canonical Gate 4 bounded renderer/validator 纵向切片完成后，开发 **Codex Project Skill Draft Studio**：

1. 用户描述一个重复任务；
2. 确定性向导补齐目标、触发、排除、输入、输出、步骤和验证；
3. 推荐 Prompt / AGENTS / Skill / Agent，用户确认；
4. 输出 `SkillBlueprint v1`；
5. 确定性模板生成 `.agents/skills/<name>/SKILL.md` 内存候选；
6. 对最终候选字节检查名称、description 触发信息、路径、frontmatter、章节、风险和预算；
7. 保留至少 3 个 should-trigger 与 3 个 should-not-trigger 静态 fixture，但明确 `routingEvalPerformed=false`；
8. 默认输出不含正文的 metadata，可显式导出正文、synthetic new-file Diff 或 Prompt；
9. supporting-file 只有路径提案时进入人工审阅，不生成虚假链接或文件；
10. 停止于 `writesPerformed=false`、`applyEligible=false`。LLM、分解与真实 route eval 延后到有用户证据时再解冻。

这条切片先只支持 Codex project skill，不同时实现跨宿主渲染、历史学习、遗传优化和真实写入。

## 13. 指标与晋级规则

- 未批准目标写入、扫描/评测中执行发现脚本、默认日志保存原始对话/Skill 正文：均为 0。
- 生成：classification acceptance、blueprint completion、first-pass validation、draft rejection、人工 edit distance。
- 路由：positive trigger recall、negative avoidance、top-1 accuracy、top-3 recall、no-route precision、ambiguous rate。
- 改进：gap acceptance precision、fixed-case rate、candidate win rate、holdout delta、regression count、token/cost/latency delta。
- 发布：所有安全 fixture 必须通过；critical regression 不得由 pass 变 fail；缺失 evaluator/model/version 的 `EvalRun` 不得标为通过。
- 自动优化只有在 paired repeated trials 的 holdout 改进稳定、无安全回归、成本在预算内时，才进入人工 promotion review。

## 14. 研究边界

APE、ProTeGi、EvoPrompt、Promptbreeder、MIPRO、TextGrad、Reflexion、ExpeL、GEPA 等研究支持“生成多个候选并用任务级反馈选择”；ASSAY、SkillsBench、SWE-Skills-Bench 和 SkillLens 提醒我们 Skill 的作用依赖任务、模型、版本与路由，甚至可能负迁移。它们都不构成“自动修改 live Skill 文件”的证据。

论文、结果、限制和工程推论集中维护在 [RESEARCH_NOTES.md](RESEARCH_NOTES.md)。
