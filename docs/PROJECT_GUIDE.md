# Agent Config Workbench 项目指南

- 文档版本：1.2
- 日期：2026-08-04
- 项目类型：生产型 AI 应用闭环 + Agent 配置治理 + 评测与可观测性
- 项目阶段：实验室原型 / Codex-first Inspect 纵向切片已可运行
- 当前执行范围：`Inspect → Draft → Diff/Export → Simple Apply/Rollback`；其他宿主、通用转换、GitHub、Router 与历史演化暂时冻结

## 1. 一句话目标

长期让开发者在 Codex、Claude Code 和主流 vibe-coding 工具之间管理、生成、转换并安全应用 instructions、skills、rules 和 agents；当前先让普通 Codex 用户完成“看清配置、生成一个原生候选、审阅并安全回退”的最小闭环。

### 1.1 当前执行合同

```text
Codex-first
  Inspect
    → Draft one native asset
    → Diff / Export
    → Simple Apply
    → Simple Rollback
```

- 当前顺序支持 Codex Project Skill、`AGENTS.md`、Agent TOML、Rule/Policy；先完成 Skill 全闭环，再扩展后三类。
- `inspect codex` 已完成第一刀：人类可读、零写入、只显示逻辑路径和结论，不显示正文、hash 或物理路径。
- Claude Code、主流 vibe-coding 宿主、双向转换、GitHub、Skill Router、评测和历史改进仍是长期目标，不取消，但不与核心闭环并行开发。
- 后文的多宿主平台、研究优化和完整事务设计是长期参考；若与本节的近期顺序冲突，以本节为准。

| 能力 | 当前状态 |
|---|---|
| Codex inspect | Active / 第一刀完成 |
| Codex Skill inventory、triage、draft、diff/export | Next |
| 单文件 Simple Apply/Rollback | Queued |
| Claude 完整闭环 | Queued after Codex validation |
| 通用 conversion、Git/GitHub、Wave hosts | Frozen |
| Router、gap、history/evolution、hooks | Research backlog |
| 多文件事务与 Recovery Center | Deferred |

## 2. 核心判断

本项目不应被做成“生成一个更长的 `AGENTS.md` 或 `CLAUDE.md`”的工具。正确产品形态是：

```text
宿主无关中间模型
  + Host Adapter Registry 与 capability matrix
  + Codex / Claude Core adapters
  + Cursor / Copilot / Windsurf-Devin Wave 1 adapters
  + Cline / Roo / Gemini CLI / OpenCode / Continue Wave 2 adapters
  + Conversion Workbench（任意已支持 source → target）
  + Effective Context Compiler（实际加载上下文编译器）
  + 确定性校验与策略引擎
  + 安全事务文件引擎与独立恢复仓
  + 可选 AI 辅助
  + Git / GitHub 审阅和发布能力
```

产品闭环：

```text
选择工作区
  → 只读发现
  → 解释实际作用域与加载顺序
  → 检测冲突、重复、风险与预算
  → 选择原生整理、单目标转换或多目标导出
  → 生成候选 ChangeSet
  → 用户审阅 Diff
  → 静态验证与 fixture/eval
  → 明确批准
  → 快照与事务写入
  → 原生工具验证
  → 审计记录与一键回退
```

面向 Skill 的主用户旅程是：

```text
Create → Organize → Route → Evaluate → Improve → Govern
```

详细的数据契约、工作流、Hook 边界、Skill lane 和最小产品切片见 [SKILL_LIFECYCLE_SPEC.md](SKILL_LIFECYCLE_SPEC.md)。

## 3. 用户与 Jobs to Be Done

### 3.1 目标用户

- 同时使用 Codex、Claude Code、Cursor、GitHub Copilot、Windsurf 或其他本地编码代理的个人开发者。
- 维护 monorepo、多个项目或多语言工程的技术负责人。
- 希望把团队经验沉淀为可执行工作流，而不是不断复制提示词的团队。
- 需要审计本地 agent 配置、第三方 skill/plugin/hook 风险的安全敏感用户。

### 3.2 核心任务

1. “告诉我当前目录实际会加载哪些指令，按什么顺序，哪里冲突。”
2. “把过长、重复、范围错误的规则整理到正确机制中，但先让我看到完整 Diff。”
3. “把一个编码代理的配置安全转换为另一个代理的原生文件，并明确告诉我哪些字段不能等价迁移。”
4. “把重复任务整理成 skill，并验证它应该触发和不应该触发的场景。”
5. “在任何写入前创建可靠恢复点，失败时恢复原始字节。”
6. “如果我不授权工具写文件，给我一份可复制给代理的安全操作 Prompt。”
7. “在 Git/GitHub 上审阅、共享和发布配置包，同时保留来源与版本证据。”
8. “把一段自然语言需求正确变成 Prompt、Instruction、Skill 或 Agent contract，而不是默认生成大文件。”
9. “把职责混杂的大 Skill 拆成可独立触发、可组合、可单独评测的能力包，同时保留共享 references/scripts。”
10. “为当前任务选择合适的 Skill，说明为什么选或不选，并发现缺少、冲突或过期的能力。”
11. “从我明确选择的历史记录中提议改进，并用 baseline/holdout/regression 证明候选更好后再让我批准。”

### 3.3 非 AI 基线

手工查找配置文件、阅读厂商文档、填写结构化模板、手工编写正向/负向触发例、运行原生 CLI 验证、手动 `git diff`、手工备份。产品在没有 LLM 时也必须覆盖这条路径；LLM 只能作为可选的起草、重排和候选改进器。

## 4. MVP 范围与非目标

### 4.1 当前验证版必须有

- 一个 Codex-only 的人类可读 `inspect` 入口，解释实际生效链、加载状态、确定问题、启发式建议和不完整状态。
- Codex project Skill inventory 与 persistence triage。
- 单个 `.agents/skills/<name>/SKILL.md` 内存候选、静态检查、3 个正向和 3 个负向触发例。
- 正文预览、逐行 Diff、复制/下载与 Prompt Export，保持零目标写入。
- 下一阶段只增加项目内单文件 Simple Apply/Rollback，并遵守 ADR-026 的最低安全合同。

### 4.2 长期完整 MVP 能力地图（当前冻结参考）

- 选择一个用户明确授权的项目目录。
- 只读发现 Codex 与 Claude Code 的项目级文件，并通过同一 registry 检测 Wave 1 宿主资产。
- 展示逻辑路径、real path、作用域、来源层级、加载条件、字节/行/token 估算。
- 构建“对指定 CWD 有效的上下文图”，标出条件加载与不会自动加载的文件。
- 检测解析错误、重复名称、循环导入、路径越界、无效 glob、冲突规则、过期路径、敏感内容和高风险可执行配置。
- 先提供 Codex → Claude 与 Claude → Codex 的 Core 转换预览；Host Adapter API 必须允许 Wave 1/Wave 2 在不改安全核心的前提下接入。
- Wave 1 的首个发布门槛是只读发现、原生格式解释与转换预览；通过宿主 conformance suite 前不开放 Apply。
- 对转换结果执行目标 schema 校验、语义 Diff、round-trip 检查和明确损失报告。
- 生成候选变更集、文本 Diff、语义 Diff、风险分级与验证清单。
- 在 fixture 中完成快照、原子写入、故障注入和 byte-identical 回退。
- 在真实目录中只对用户批准的精确文件应用已验证 ChangeSet。
- 保存脱敏审计记录与独立于 Git 的恢复点。
- 导出“只读审计 Prompt”和“建议变更 Prompt”。
- 提供 Skill lane S0/S1：只读 inventory，以及自然语言/向导到 `SkillBlueprint v1` 的 preview-only 流程。
- 在 Skill Draft Studio 中先支持 Codex project skill 的内存候选、静态检查和至少 3 个 should-trigger / 3 个 should-not-trigger 用例；不写目标。

### 4.3 当前验证版不做

- 不自动删除文件。
- 不自动安装、启用或执行 Hook、Plugin、MCP server、skill 脚本或项目脚本。
- 不自动 `git commit`、`push`、创建 PR 或切换分支。
- 不在未授权情况下扫描整个用户主目录。
- 不把用户仓库内容默认上传给任何模型。
- 不承诺所有配置都能无损互转；不支持等价转换的权限、Hook、Plugin、MCP 和宿主设置只生成迁移报告或安全骨架。
- 不在第一版同时对所有列出的宿主开放写入；支持等级必须在 UI 和导出报告中明确显示。
- 不以单一“健康分数”掩盖具体风险。
- 不以多代理架构作为卖点；MVP 的 AI 辅助优先采用一次结构化调用。
- 不根据一次对话或一次成功/失败自动修改 live Skill、AGENTS、Agent、Hook、权限或 eval。
- 不让优化候选修改自己的评测集，不以单个 LLM judge 或单一综合分数决定发布。

## 5. 宿主机制必须分开建模

### 5.1 统一领域类型

| 类型 | 含义 | 信任级别 |
|---|---|---|
| Instruction | 每次或按作用域提供给模型的自然语言指导 | 软约束 |
| Skill | 按需加载的单一可复用工作流 | 软约束，可能包含可执行内容 |
| Agent | 独立上下文、角色、工具与权限配置 | 高影响软约束 |
| Policy | 客户端执行的 allow/ask/deny、sandbox 或企业约束 | 硬边界 |
| Hook | 生命周期事件上的确定性程序 | 可执行代码，高风险 |
| Plugin | 可安装、可分发的能力集合 | 高信任供应链组件 |
| Prompt | 当前任务的一次性目标与限制 | 会话级软约束 |

### 5.2 Codex 原生映射

| 原生位置 | 类型 | 主要职责 | 关键事实 |
|---|---|---|---|
| `AGENTS.md` / `AGENTS.override.md` | Instruction | 项目命令、约定、验证、review 期望、子树路由 | 从项目根到 CWD 组合；近层更具体 |
| `.agents/skills/<name>/SKILL.md` | Skill | 单一可复用任务工作流 | `name` 与 `description` 必需；正文按需加载 |
| `.codex/agents/*.toml` 与 config 中 agent 声明 | Agent | 自定义子代理角色、工具、sandbox 与模型设置 | 与 Claude Markdown agent 不同 |
| `.codex/config.toml` | Runtime config | sandbox、approval、MCP、agent、skill、hook 等设置 | 仅可信项目层加载；不能承载团队手册 |
| `.codex/rules/*.rules` | Policy | 控制哪些命令可在 sandbox 外执行 | 是 Starlark 命令策略，不是 Markdown 规范 |
| `.codex/hooks.json` 或 config 中 hooks | Hook | 生命周期阻断、校验、审计 | 多来源并行；不是完整安全边界 |
| `.codex-plugin/plugin.json` 所在包 | Plugin | 分发 skills、hooks、MCP、assets 等 | cache 不是编辑源 |

### 5.3 Claude Code 原生映射

| 原生位置 | 类型 | 主要职责 | 关键事实 |
|---|---|---|---|
| `CLAUDE.md` / `.claude/CLAUDE.md` | Instruction | 每次会话应知道的命令、架构、约定与限制 | 官方建议每文件目标少于 200 行；不是硬限制 |
| `CLAUDE.local.md` | Instruction | 当前用户、当前项目的个人偏好 | 应 gitignore；禁止密钥 |
| `.claude/rules/**/*.md` | Instruction | 单一主题规则；可用 `paths` 按需加载 | 无 `paths` 时仍启动加载 |
| `.claude/skills/<name>/SKILL.md` | Skill | 可复用流程、参数、支持文件、隔离上下文 | 官方建议正文少于 500 行 |
| `.claude/commands/*.md` | Skill 兼容入口 | 旧版 custom command | 继续有效；新建优先 skill；同名 skill 胜出 |
| `.claude/agents/**/*.md` | Agent | 专用 subagent 提示、工具与权限 | 递归发现；重复 `name` 可能不确定 |
| `.claude/settings.json` | Runtime/Policy | 团队共享设置、权限、hooks、plugins | 可提交，但不能放秘密 |
| `.claude/settings.local.json` | Runtime/Policy | 本地授权与覆盖 | 不得自动提交 |
| settings 或组件中注册的 hooks | Hook | 生命周期确定性动作 | 以用户权限运行，视为可执行代码 |
| `.claude-plugin/plugin.json` 所在包 | Plugin | 分发 skills、agents、hooks、MCP、LSP | 安装前必须审计 |

### 5.4 主流宿主不能只按文件名映射

Cursor、GitHub Copilot、Windsurf / Devin Desktop、Cline、Roo Code、Gemini CLI、OpenCode、Continue 与 Aider 的原生位置、加载方式、支持等级和官方证据统一维护在 [HOST_SUPPORT_MATRIX.md](HOST_SUPPORT_MATRIX.md)。几个直接影响架构的差异是：

- Cursor、Windsurf/Devin Desktop、Continue 均有条件规则，但 activation、glob、预算与发现范围并不相同；Windsurf/Devin 还处于目录和 agent surface 迁移期。
- GitHub Copilot 的 instruction/agent 支持取决于 IDE、CLI、coding agent 等具体 surface。
- Cline 把 rules 与 slash workflows 分开；Roo Code 还增加 mode-specific rules 和 custom modes。
- Gemini CLI 的 `GEMINI.md` 是分层 memory，并与 settings、trusted folder、checkpoint 配合。
- OpenCode 同时支持原生和兼容 skill 来源，并把 agent 的工具权限作为原生配置。
- Aider 更适合作为 conventions/config 的 Export Only 目标，不应伪装成完整 agent 配置体系。

因此 adapter 的职责包括发现、解析、Effective Context、capability 声明、渲染和原生验证。只有路径检测而没有语义与 fixture 的实现只能标为 Inventory。

### 5.5 转换安全边界

跨宿主转换是产品能力，但必须通过宿主无关 IR 和版本化转换 recipe 完成。以下做法仍然绝对禁止：

- 不把 `.claude/rules/*.md` 渲染成 `.codex/rules/*.rules`。
- 不把 Claude subagent Markdown 直接改名为 Codex TOML agent。
- 不把 Hook 描述当作备份机制。
- 不把 `@import` 机械拆分宣传为减少 token；只有按路径或按需加载才可能减少 live context。
- 不把管理策略、权限、sandbox 写进自然语言 instruction 并声称已强制执行。

## 6. 多宿主组织与转换工作台

### 6.1 推荐的 portable core 与 Core 双宿主基线

项目共享的“永远应知道”内容以根 `AGENTS.md` 为主。Claude Code 按官方建议通过一个很短的 `CLAUDE.md` 导入它，并只追加 Claude 专属内容：

```text
repo/
├── AGENTS.md                     # Codex 原生；跨工具共享的核心项目说明
├── CLAUDE.md                     # @AGENTS.md + 少量 Claude 专属说明
├── services/
│   └── billing/
│       └── AGENTS.md             # Codex：仅 billing 子树的差异
├── .agents/
│   └── skills/                   # Codex 项目 skills
├── .codex/
│   ├── config.toml
│   ├── agents/
│   ├── rules/                    # Codex command policies
│   └── hooks.json                # 可选，默认不由本工具安装
└── .claude/
    ├── rules/                    # Claude path/topic instructions
    ├── skills/                   # Claude 项目 skills
    ├── agents/
    ├── settings.json
    └── settings.local.json       # gitignored
```

根 `CLAUDE.md` 示例：

```md
@AGENTS.md

## Claude Code

- Use path-scoped rules under `.claude/rules/` when working in specialized areas.
```

MVP 不自动在 `.agents/skills` 与 `.claude/skills` 间建立符号链接。原因是平台兼容、Git 行为、越界目标和来源漂移难以安全解释。Conversion Workbench 在 staging 中生成目标宿主的独立原生副本，并用 provenance 记录与源文件关联；只有用户确认后的 ChangeSet 才能写入目标位置。

`AGENTS.md` 可作为 portable instruction 的优先共享面，但它不是所有宿主、所有 surface 都无条件读取的标准。`.agents/skills/<name>/SKILL.md` 可作为另一个 portable skill 候选面，因为 Codex、Copilot CLI、Devin/OpenCode 等已提供兼容入口；其中 allowed-tools、trigger、参数与动态执行仍由目标 adapter 单独解释。工具必须根据目标 adapter 决定是直接复用、生成 wrapper、提取 path-scoped rule，还是只导出人工迁移建议。各宿主的原生文件仍保留在各自目录中，不把仓库强制重排成某一家产品的结构。

### 6.2 操作模式

| 模式 | 用途 | 默认行为 |
|---|---|---|
| Native | 审计和整理当前宿主的原生文件 | 只读扫描、原生规则解释、候选 Diff |
| Convert | 从 Host A 资产生成 Host B 原生候选 | 先转 IR，再按目标 capability 与版本渲染到 staging |
| Multi-target Export | 同一 portable source 导出到多个目标宿主 | 每个目标独立生成 grade、loss report、验证结果和 ChangeSet 草案 |

Codex ↔ Claude 是首批完成的 recipe 集，不再是写死在产品里的唯一方向。任意源/目标都不意味着所有文件可以一一等价；每个转换项必须显示 adapter 支持等级、映射等级、丢失字段、权限变化、目标加载语义与需要人工决定的问题。

### 6.3 转换等级

| 等级 | 含义 | 允许的自动化 |
|---|---|---|
| Exact | 已用 fixture 证明语义和 round-trip 等价 | 可自动生成候选；仍需用户批准后写入 |
| Compatible | 共同子集可稳定映射，宿主专属字段被完整保留到 loss/provenance report | 可自动生成候选，必须展示差异 |
| Assisted | 作用域、权限或执行语义不同，需要规则或 AI 给出多个方案 | 只生成候选和问题清单，不可默认选方案 |
| Unsupported | 没有安全、可验证的目标等价物 | 禁止生成可应用变更；只给迁移报告或最小骨架 |

### 6.4 初始 Core 映射矩阵

| 源 | 目标 | 等级 | 转换行为 |
|---|---|---|---|
| 根 `AGENTS.md` | Claude 根 `CLAUDE.md` | Exact | 生成 `@AGENTS.md` wrapper，并保留已有 Claude 专属段落 |
| 根 `CLAUDE.md` | 根 `AGENTS.md` | Assisted | 识别共享内容与 Claude 专属内容；只提议提取共享段落 |
| nested `AGENTS.md` | Claude path-scoped rule | Assisted | 将目录作用域提议为 `paths` glob，并明确 CWD 与 file-read 触发差异 |
| Claude path-scoped rule | nested `AGENTS.md` | Assisted | 建议最近公共目录；明确 Codex 基于启动/CWD 的加载差异 |
| 基础 `SKILL.md` 共同子集 | 另一宿主 `SKILL.md` | Compatible | 映射 name、description、正文和相对支持文件；宿主专属 frontmatter 单列 |
| `.claude/commands/<name>.md` | `.claude/skills/<name>/SKILL.md` | Compatible | 同宿主迁移；保留参数与调用语义，目标 skill 优先级单独提示 |
| Agent contract 与无副作用提示正文 | 目标宿主 Agent | Assisted | 迁移职责、输入输出、成功标准；工具、模型、权限逐项重新确认 |
| Codex `.rules` | Claude permissions/settings | Unsupported | 只生成命令策略意图报告，不自动创建 allow/deny |
| Claude permissions/settings | Codex `.rules` | Unsupported | 只生成最小权限迁移建议和待确认 match examples |
| Hook、Plugin、MCP、完整 settings/config | 另一宿主对应物 | Unsupported | 仅清单、风险报告和禁用状态骨架；绝不转换或执行代码 |

### 6.5 转换流水线

```text
选择源资产、源宿主与目标宿主
  → 源宿主 schema/CST 解析
  → 归一化 IR + provenance
  → 目标 capability/版本匹配
  → 应用版本化 conversion recipe
  → 生成 staging 原生文件
  → 目标 schema 与静态安全校验
  → semantic diff + loss report
  → target → IR round-trip 对比
  → 用户处理 assisted questions
  → 生成普通 ChangeSet
  → 复用快照、事务、验证和回退流程
```

转换器本身没有独立写入通道。所有转换结果最终都必须进入与原生编辑相同的 ChangeSet 和事务引擎。

### 6.6 转换结果规则

- 目标文件已存在时生成 source / current target / proposed target 三方审阅，默认不覆盖。
- 每个结果记录 source hash、source host/version、recipe id/version、target host/version、unmapped fields 与 validator versions。
- 不在目标文件中插入可能影响模型行为的生成器注释；provenance 存在本地审计记录和可导出的 sidecar report。
- 转换不得静默扩大工具、权限、网络、自动调用、模型调用或可执行行为。
- 动态 shell、hook command、MCP executable 与 plugin binary 在目标候选中默认禁用或不生成。
- Round-trip 比较的是归一化语义，不要求不同宿主文件字节相同。
- 用户可以逐项接受、拒绝或改写，不要求整批转换。

### 6.7 Quick Convert 小工具

除批量 Conversion Workbench 外，桌面端提供一个轻量 Quick Convert 入口，用于快速转换单个 instruction、skill 或 agent：

1. 拖入或选择一个已扫描的源文件。
2. 自动识别源宿主，显示识别证据与 adapter 成熟度，并让用户选择一个已支持的目标宿主。
3. 立即显示 mapping grade、目标路径建议、目标预览、loss report 与 round-trip 结果。
4. 用户可导出候选文件和报告，或发送到当前工作区的 ChangeSet Review。

Quick Convert 复用同一 adapter、IR、recipe 和 validator，不建立第二套转换逻辑，也没有直接覆盖目标文件的按钮。目标为 Preview Adapter 或 Export Only 时只能下载候选/报告，不能进入 Apply。批量转换、冲突处理和真实 Apply 仍进入完整工作流。

### 6.8 Adapter Registry 与支持晋级

每个 adapter 都由 registry manifest 声明：host id、显示名、已验证版本范围、官方证据、可发现路径、capability、解析/渲染能力、原生 validator 与当前成熟度。成熟度只能按下列顺序晋级：

```text
Inventory only
  → Read adapter
  → Conversion preview
  → Apply enabled
```

晋级必须有真实 fixture 和版本证据，降级可以由版本漂移、schema 变化或 validator 回归自动触发。UI 对未知或超出已验证范围的宿主版本默认切回只读，并提示用户查看原生文档，而不是继续尝试写入。

## 7. 内容设计标准

Skill、Instruction、Agent 与 Prompt 的 persistence triage、Blueprint、拆分规则、Router、Gap taxonomy 和演化流程统一见 [SKILL_LIFECYCLE_SPEC.md](SKILL_LIFECYCLE_SPEC.md)。本节继续定义所有宿主配置共同遵守的内容质量基线。

### 7.1 根 instruction 应写什么

建议结构：

1. Project overview：2–6 个项目事实和定位。
2. Commands：可直接运行的安装、启动、测试、lint、typecheck、build 命令。
3. Architecture pointers：只写模块边界和详细文档位置。
4. Conventions：模型无法从代码稳定推断、团队真实执行的约定。
5. Hard constraints：必须避免的行为、原因和安全替代路径。
6. Gotchas：症状、原因、修复或验证步骤。
7. Verification：改动类型到验证命令的映射。
8. Routing：哪些工作应转入 nested instruction、rule、skill、policy、hook 或深层文档。

### 7.2 一条好规则的形式

```text
动作 + 明确作用域 + 可验证结果 + 必要时的原因/安全替代
```

示例：

- 好：`修改 Java 文件后运行 ./gradlew test；失败时保留完整失败输出，不跳过测试。`
- 差：`确保代码质量。`
- 好：`不要把凭据写入 settings.json；使用系统凭据存储，并只在审计日志记录 secret reference。`
- 差：`注意安全。`

### 7.3 不应写入 always-on instruction 的内容

- 可从构建文件、目录树或代码稳定推断的显然事实。
- 完整 API 参考、长教程、设计历史或大段示例。
- 只在一个任务中使用的一次性要求。
- 能由 formatter、linter、type checker、tests 或权限策略确定执行的规则。
- 密钥、Token、内部 URL、个人测试数据或客户内容。
- 相互矛盾、已过期、没有作用域或无法验证的口号。

### 7.4 长度与上下文预算

产品必须把“官方行为/建议”和“本项目启发式”分开显示：

| 对象 | 官方依据 | 产品默认提示 | 是否阻断 |
|---|---|---|---|
| Codex instruction chain | `project_doc_max_bytes` 默认 32 KiB；官方页面对单文件/组合口径略有差异 | 同时显示单文件与当前 CWD 有效链累计字节；接近 32 KiB 提醒 | 预测会截断时阻断自动应用，除非用户调整配置或缩小内容 |
| Claude `CLAUDE.md` | 官方目标 `<200` 行/文件，更长会增加上下文并可能降低遵循 | 绿色建议为 40–120 行，120–200 行提醒审查作用域；这是可配置工程启发式 | 超过 200 行只警告，不声称失败率 |
| Claude `SKILL.md` | 官方建议 `<500` 行 | 主流程尽量 `<300` 行；详情放 supporting files | 超过 500 行警告，不自动截断 |
| Codex `SKILL.md` | 无官方正文行数硬限；元数据列表有上下文预算 | 主流程保持单一任务；详情移入 references/scripts/assets | 不按行数阻断 |
| Claude rule | 官方要求一文件一主题；无 50 行限制 | 10–60 行为维护性提示；优先 path scope | 不按行数阻断 |
| Skill description | Codex/Claude 都依赖描述触发；Claude 列表项默认截断到 1,536 字符，Codex 初始列表有整体预算 | 1–3 句，先写用途和触发词，再写不适用边界 | 超过宿主元数据限制时阻断 |
| Agent prompt | 无通用官方行数上限 | 一个角色、一个合同、最小工具面 | 依据冲突和权限风险，而非行数 |

“绿色 40–120 行”“rule 10–60 行”“skill 主流程 300 行”是本项目待评测的 UX 默认值，不是厂商规范或通用质量定律。用户可配置，产品必须保留来源标签。

### 7.5 需要计算的真实质量信号

- 单文件字节、行、字符与近似 token。
- 指令/约束数量，而不只是总行数。
- always-on、path-scoped、on-demand 的实际上下文占用。
- 重复、近重复、冲突、无安全替代的否定规则。
- 规则位置、来源层级、加载顺序与被更具体规则遮蔽的关系。
- 命令、路径、glob 和 import 是否真实存在。
- 说明能否由 fixture、schema、CLI 或 should/should-not-trigger eval 验证。
- 最近验证日期与宿主版本，防止规则随版本漂移。

## 8. Effective Context Compiler

这是产品最有差异化的确定性核心。输入是：

- 宿主类型与版本。
- 用户选择的项目根和模拟 CWD。
- 启用的 user/project/managed 来源范围。
- 项目信任状态与 relevant config。

输出是一个带来源证据的加载图：

```text
Artifact
  ├── logical_path / real_path
  ├── host / type / scope / precedence
  ├── load_mode: startup | path-scoped | on-demand | executable
  ├── conditions: cwd / glob / trust / enabled / user approval
  ├── imports / references / shadows / conflicts
  ├── byte / line / token / constraint budgets
  └── provenance: source file + parser version + observed host version
```

编译器应能回答：

- “从 `services/billing` 启动 Codex，会读取哪些 `AGENTS` 文件？”
- “Claude 读取 `src/api/user.ts` 后，哪些 path-scoped rules 才进入上下文？”
- “这个无 `paths` 的 Claude rule 虽然拆成独立文件，是否仍在启动时全量加载？”
- “同名 skill/agent 哪个生效，是否存在未定义选择？”
- “当前链是否可能触及 Codex 32 KiB 默认预算？”
- “哪些内容是模型软指导，哪些是客户端硬策略，哪些会执行代码？”

### 8.1 当前 Instruction IR 与分析契约

当前只对 Codex 与 Claude Code 的已求值项目上下文开放如下流水线：

```text
host-native project files
  → Effective Context Compiler（宿主加载语义）
  → host-independent Instruction IR（source/scope/activation/provenance）
  → deterministic payload comparison + heuristic directive analysis
  → metadata-only Analyze JSON
```

- Context JSON 继续使用 schema v2；Analyze JSON 独立使用 schema v1，并记录其输入 context schema 版本。给 context 增加 provenance relation 是向后可读扩展，不把分析字段塞回原契约。
- 当前窄语义分别标识为 `codex-project-semantics-v1` 与 `claude-code-project-semantics-v1`；Context 和 Analyze 都输出该 profile，后续宿主行为变化必须新增或明确升级 profile，不能静默改变旧 profile 的含义。
- IR 区分完整文件 `revisionSha256` 与实际纳入上下文的 `effectiveSha256`；Codex 预算截断时，后者只覆盖真实 effective byte slice。
- 只有 effective hash、included length 和有效 scope 都相容时，才把重复标为确定性的 `EXACT_EFFECTIVE_DUPLICATE`。
- 规范化指令重复和直接极性冲突分别是 `NORMALIZED_DIRECTIVE_DUPLICATE`、`DIRECT_POLARITY_CONFLICT` 启发式候选；它们需要人工审阅，不驱动自动删除、覆盖或转换。
- Analyze JSON 不含原始指令正文、normalized text 或 `realPath`；同一 import 的多个父引用保留为 provenance edges，而不是复制 source node。
- 当前能力只读、不执行发现内容、不修改或转换文件；其他宿主尚未实现 Effective Context 语义，因此不能借用本分析器宣称 Read 支持。

## 9. 安全工作流

### 9.1 状态机

```text
DRAFT
  → SCANNED
  → PLANNED
  → VALIDATED
  → APPROVED
  → SNAPSHOT_READY
  → COMMITTING
  → COMMITTED
  → VERIFIED

任一步失败 → FAILED
COMMITTING 后失败 → ROLLING_BACK → ROLLED_BACK 或 NEEDS_MANUAL_RECOVERY
```

每次状态变化都写入 append-only journal。只有 `APPROVED + SNAPSHOT_READY` 能进入写入。

### 9.2 扫描阶段

- 默认只读且离线。
- 只扫描已授权根下的 allowlisted 配置位置。
- 保存逻辑路径和 real path，不跟随未验证 symlink/junction。
- 对秘密候选文件默认只记录路径与风险，不读取内容。
- 把 Markdown、README、第三方 repo 和网络内容全部当作不可信数据，不执行其中指令。
- 对 skill 中动态 shell、hook command、MCP executable、plugin binary 单独标红。

### 9.3 计划与验证阶段

- AI 只能返回 schema-valid 的候选操作：`create | patch | move | disable | no-op`。
- 确定性策略引擎重新验证每个路径、类型、作用域和权限。
- 使用 lossless/CST patch，保留未知字段、注释、编码、换行与文件权限。
- 展示 text diff 与 semantic diff；高风险配置单独展开。
- 不在真实目标上运行候选 hook/skill/plugin；需要行为验证时使用 fixture 或受限模拟器。

### 9.4 快照与提交

每个事务 manifest 至少包含：

```text
transaction_id
workspace_id
approved_root_realpath
tool_version / parser_versions
change_items[]
  logical_path / real_path
  operation
  existed_before
  preimage_sha256 / candidate_sha256
  mode / encoding / line_ending
  snapshot_object_id
approval_time
journal_state
```

提交协议：

1. 再次 canonicalize 所有路径并检查 real path 边界。
2. 对比 scan 时 preimage hash；任一文件改变则停止，不自动合并。
3. 为每个原文件或“不存在”状态创建内容寻址快照并验证可读。
4. 在目标文件同目录创建随机名 staging file，设置预期权限并写入候选内容。
5. flush/fsync staging 与 journal；重新解析并验证 staging。
6. journal 标记 `COMMITTING`。
7. 逐项 atomic replace，并在每项后记录实际 hash。
8. 全量重新扫描与宿主校验；通过后标记 `COMMITTED/VERIFIED`。

跨多个文件不存在真正的单次文件系统原子提交，因此必须依赖 write-ahead journal 与逆序补偿回退。

### 9.5 回退协议

- Git 不是回退前提；未跟踪文件和全局配置也必须恢复。
- 自动回退前检查当前 hash 是否仍等于本事务写入 hash；若用户已经继续编辑，停止并进入人工三方合并。
- 按逆序恢复原始字节、不存在状态、权限与可保留的元数据。
- 恢复后重新计算 hash，并验证 manifest 中所有 preimage。
- 绝不调用 `git reset --hard`、隐式 stash 或清理未跟踪文件。
- MVP 不做删除；未来删除需要第二次确认和已验证快照。

### 9.6 恢复仓

- 恢复仓位于应用用户数据目录，不放入目标 repo，避免误提交。
- 原始内容与审计元数据分离；审计日志只存 hash、路径类别和脱敏摘要。
- 快照按内容寻址、事务引用和保留策略管理。
- 默认使用 OS 安全存储包装每事务数据密钥，再用 AES-GCM 加密快照内容。
- 若 OS 安全存储不可用，产品必须明确提示并让用户选择恢复口令；不能静默降级为明文。

## 10. 威胁模型

| 威胁 | 例子 | MVP 防护 |
|---|---|---|
| Prompt injection | 第三方 repo 的 README 指示读取密钥或执行 curl | 内容只作为 data；AI 无写入权限；结构化计划再过策略引擎 |
| 路径穿越 | plugin archive 中 `../../.ssh` | canonical path、real path root check、拒绝绝对路径/设备文件 |
| Symlink/junction 逃逸 | `.claude/skills/x` 指向用户凭据目录 | 不默认跟随；显示真实目标；越界即阻断 |
| TOCTOU | scan 后用户或进程修改文件 | apply 前 hash 与 real path 再验证 |
| 部分提交 | 第 3 个文件写入时崩溃 | write-ahead journal、verified snapshot、逆序回退、启动恢复 |
| 日志泄密 | diff 或 hook 输出含 Token | secret detector、默认不读、字段级脱敏、禁止 raw prompt logging |
| Hook/skill 执行 | Claude skill 的动态 shell 或恶意 hook | 扫描阶段绝不执行；高风险标注；启用需单独批准 |
| 企业策略降级 | 项目 config 尝试绕过 managed deny | 只报告冲突；禁止生成降级方案 |
| Localhost 攻击 | 恶意网页调用本地服务 | 只绑定 loopback、随机端口、每次启动 token、Origin 校验、无宽泛 CORS |
| 供应链篡改 | GitHub release 或 action 被替换 | pin SHA、checksums、SBOM、签名/attestation、最小权限 |

## 11. 技术架构

### 11.1 推荐栈

- 产品形态：桌面优先、本地 Web UI；不是纯在线 SaaS。开发期由 Java loopback 服务承载 Vue，发布期用 `jpackage` 打包 Java runtime 与前端静态资源，必要时再评估薄桌面 WebView 壳。
- 前端：Vue 3、TypeScript、Vite。
- 本地核心：Java 21。
- API：当前选择 loopback-only HTTP 作为第一实现；只绑定 loopback、使用启动期随机 token 与严格 Origin 校验。稳定后再以小型 spike 比较同进程 IPC。
- 数据：本地 SQLite 保存脱敏元数据与 journal；加密文件 vault 保存快照内容。
- 分发：先使用 `jpackage` 产出带运行时的桌面安装包；Vue 静态资源由本地核心提供。
- Python：MVP 不需要。后续只作为无写权限的离线 eval/研究 worker，通过 JSONL/stdio 接口通信。

### 11.2 为什么安全核心选择 Java

- Java NIO 提供成熟的路径、权限、文件锁与原子 move 能力。
- 单一安全核心减少 Vue/Node/Python 各自直接写文件造成的权限分散。
- Java 结构化类型适合实现显式状态机、策略层、事务 journal 和错误分类。
- Python 对实验和数据分析很方便，但不应成为第一版的第二个特权写入运行时。

### 11.3 模块边界

```text
frontend/
  workspace-onboarding
  inventory-and-context-graph
  findings
  changeset-review
  validation-timeline
  recovery-center
  settings-and-privacy

core/
  domain                 # 宿主无关模型与状态机
  discovery              # allowlisted、只读文件发现
  host-registry          # adapter manifest、版本证据、成熟度 gate
  adapters/<host-id>     # 每个宿主独立实现统一 HostAdapter contract
  adapter-conformance    # 发现、解析、渲染、验证与安全 fixture 套件
  conversion             # N→N pipeline、mapping grade、loss report
  conversion-recipes     # 按源/目标宿主版本管理的确定性 recipe
  roundtrip-validator    # IR 语义回合检查与权限扩张检查
  context-compiler
  parsers                # lossless/CST 与 schema
  analyzer               # 冲突、重复、预算、风险
  policy                 # root boundary、capability、secret、managed constraints
  changeset
  transaction            # snapshot、journal、atomic replace、rollback
  recovery-vault
  git
  audit
  ai-provider            # 可选、无直接写权限
  eval

skill-lifecycle/
  intent-triage         # Prompt / Instruction / Skill / Agent / deterministic tool 分类
  blueprint             # 版本化 SkillBlueprint 与 TriggerContract
  package-ir            # 独立于 InstructionIr 的 SkillPackageIr
  decomposition         # keep/split/reference 与依赖 DAG 提案
  catalog-router        # 确定性过滤，LLM 仅可选重排
  gap-detector          # no-route、误触发、步骤失败、环境失败等分类
  trace-ingestion       # 用户授权、脱敏、去重、只保存受控引用
  improvement-proposer # 只生成不可变候选，不修改 live files
  skill-eval            # 正向/负向/holdout/regression/Pareto
```

### 11.4 本地服务边界

- 只监听 `127.0.0.1` / `::1`，随机可用端口。
- 启动时产生内存 session token；每个请求校验 token 与 Origin。
- 禁止通配 CORS；响应设置 CSP、frame 限制和敏感缓存控制。
- 文件选择由 native picker 或明确路径授权完成；授权范围可随时撤销。
- AI 与 GitHub 网络功能默认关闭，并在 UI 中显示将发送的字段。

## 12. 核心领域对象

| 对象 | 主要字段 |
|---|---|
| Workspace | id、logical root、real root、Git fingerprint、trust、capabilities |
| HostDescriptor | host、detected/verified version、support tier、official evidence、capabilities、user/project sources、official limits |
| Artifact | path、real path、type、scope、load mode、hash、format、risk |
| LoadGraph | CWD、ordered nodes、conditional edges、effective budgets |
| Finding | rule id、severity、evidence、artifact、location、safe alternatives |
| ConversionPlan | source/target host、adapter versions、source refs、recipe version、mapping grades、target candidates、losses、open questions、round-trip result |
| ChangeSet | base scan、items、diffs、risk、validation ids、approval |
| Snapshot | object ids、manifest hash、encryption metadata、retention |
| Transaction | state、journal sequence、written hashes、recovery status |
| ValidationRun | validator version、inputs、result、evidence、duration |
| AuditEvent | run id、redacted event、actor、timestamp、outcome |

Skill lane 使用独立版本化对象：`IntentCapture`、`PersistenceDecision`、`SkillBlueprint`、`SkillPackageIr`、`TriggerContract`、`DecompositionPlan`、`RouteIndex`、`RouteDecision`、`ConversationTraceRef`、`GapCandidate`、`EvalCase`、`EvalSuite`、`EvalRun`、`SkillRevision`、`ImprovementProposal`、`OptimizationCampaign` 与 `ApprovalRecord`。现有 content-free `InstructionIr v1` 保持稳定，不承载 Skill 正文。

## 13. UI 信息架构

### 13.1 页面

1. 欢迎与隐私说明：本地优先、默认离线、不会自动执行项目内容。
2. 选择工作区：路径授权、Git 状态、检测宿主与信任范围。
3. 工作区概览：配置资产、风险分布、实际加载预算、最后恢复点。
4. Effective Context：以树/图展示来源、顺序、按需加载与冲突。
5. Findings：逐条证据、影响、建议机制和可忽略理由。
6. Conversion Workbench / Quick Convert：批量或单文件选择源/目标宿主，查看 adapter 成熟度、映射等级、目标预览、损失与 round-trip 结果。
7. ChangeSet Review：左右 Diff、语义变化、文件列表、风险与验证。
8. Apply Timeline：快照、每步状态、失败位置、自动/人工恢复。
9. Recovery Center：事务、原始/当前 hash、恢复预览和保留策略。
10. Prompt Export：只读审计、建议变更、验证三种 prompt。
11. Settings：隐私、外部模型、GitHub、宿主预算和开发者模式。
12. Skill Draft Studio：自然语言分类、Blueprint、候选正文、触发边界和静态验证。
13. Skill Catalog / Router Lab：能力树、候选和拒绝证据、依赖 DAG、上下文预算与 gap。
14. Eval Lab / Improvement Inbox：baseline/candidate、holdout、回归、trace consent、Diff 与 promotion。

### 13.2 必须设计的状态

- Empty：没有发现配置时提供“只生成建议，不写入”的入口。
- Loading：显示当前扫描阶段与可取消状态，不伪造百分比。
- Partial success：某些文件无权限或解析失败时保留可用结果。
- Error：解释失败层、未执行动作和安全恢复方式。
- Unsupported host/conversion：区分“adapter 尚未成熟”与“该资产语义不可映射”，保留可转换项并明确原因。
- Existing target conflict：显示三方 Diff，不以源文件覆盖目标文件。
- Concurrent change：禁止 Apply，提供重新扫描与三方 Diff。
- Recovery required：启动即优先处理未完成事务。
- Narrow window：桌面窗口变窄时 Diff 改为上下堆叠，表格可横向滚动。
- Overflow：文件路径、命令、JSON 和长描述均可折行/复制，不用固定高度截断。

### 13.3 易用性原则

- 默认视图用“建议/警告/阻断”而非模糊总分。
- 每条 Finding 都显示“依据来自官方规则、研究、启发式还是用户策略”。
- 每次写入前固定展示：文件数、真实路径范围、是否含可执行配置、快照位置、回退 ID。
- 高级模式才能显示或修改 hooks、permissions、plugins 等特权配置。
- 把 Shared、Local、User、Managed 四种作用域用颜色和文字同时区分，不能只靠颜色。

## 14. AI 与 Prompt 模式

### 14.1 确定性 baseline 与可选 AI

- 将模糊、重复的自然语言规则分类到正确机制。
- 提议更具体、可验证的规则表达。
- 生成 skill 初稿、trigger/non-trigger eval case 与说明。
- 解释冲突与提供多个安全替代方案。
- 对确定性过滤后的路由候选重排。
- 从用户确认的 gap 和脱敏 trace 提议版本化修订。

AI 不得：

- 决定最终文件路径是否安全。
- 直接写目标文件、删除文件、执行 hook/skill/plugin。
- 覆盖 managed policy 或自动扩大授权根。
- 在未获明确同意时向外部模型发送原文件。
- 决定读取哪些历史记录、修改 eval、批准自己的候选或把候选直接提升为 active。

自然语言分类、Skill 骨架、静态验证、基础路由、eval 执行、ChangeSet、Apply 与 rollback 都必须有非 AI 实现。LLM 输出必须符合 schema，并记录 provider、model 和 prompt version。

Hooks 只允许发出有界、脱敏、可丢失的事件 envelope。Hook 内不得调用 LLM、解析完整 transcript、运行 eval、改文件或执行 Git。历史改进由独立的本地 importer 和离线 worker 完成，详见 [SKILL_LIFECYCLE_SPEC.md](SKILL_LIFECYCLE_SPEC.md)。

### 14.2 Prompt 导出等级

| 模式 | 输出 | 安全等级 |
|---|---|---|
| Read-only audit prompt | 要求代理只列出文件、作用域、问题与来源，不修改 | 较高，但仍需核对代理实际权限 |
| Proposal prompt | 生成候选文件内容和 Diff，不落盘 | 中等 |
| Apply prompt | 包含快照、hash、验证与回退要求 | 低于应用内事务；默认不推荐 |

Prompt 模式无法提供应用内同等级的原子事务、并发检测和恢复仓。因此 UI 应明确标注“可移植替代方案”，不能宣传为等价执行方式。

## 15. Git 与 GitHub 策略

### 15.1 本地 Git

- Git 检测和 `status/diff` 为只读默认能力，不要求 clean worktree。
- 记录 apply 前后的相关路径 Diff，但不得把用户已有 dirty change 归因于本工具。
- 不自动 stash、切分支、提交、清理或重置。
- 用户验证通过后，可在后续版本选择生成建议 commit message 或显式 commit。

### 15.2 GitHub 集成路线

- Phase 1：GitHub 作为源码、issue、设计记录和 release 渠道；工具运行不需要 GitHub 账户。
- Phase 2：通过用户现有 `gh` 登录或 GitHub App 做可选 PR 导出；不用应用自存长期 PAT。
- 默认最小权限，创建分支/推送/PR 每项单独确认。
- 导入第三方 skill/plugin 时记录 repository、requested ref、resolved commit SHA、内容 hash 和许可证。
- 下载到 staging，限制文件数、总大小、压缩比和路径类型；安装前审计可执行组件。

### 15.3 项目自身供应链

- GitHub Actions 默认 `contents: read`，额外权限按 job 最小声明。
- 第三方 Actions pin 到完整 commit SHA。
- 依赖锁文件、dependency review、Dependabot/漏洞扫描纳入 CI。
- release 生成 SBOM、SHA-256 checksums、签名或 GitHub artifact attestation。
- 发布不可变版本；安装包中展示版本、commit 与构建来源。

## 16. 评测与可观测性

### 16.1 最小 trace

```text
run_id
workspace pseudonymous id
host + detected version + adapter maturity
step name
parser / policy / prompt version
source/target host + adapter versions + recipe version + mapping grades
artifact counts and redacted summaries
tools or validators called
schema validity
latency
error class
human approval / rejection
transaction and rollback outcome
skill id + revision + content hash
route candidates + selected/rejected evidence
invocation mode: explicit | implicit | not_triggered
user/task outcome labels
redaction profile + consent scope + retention class
```

不记录 raw secrets、完整 repo 内容、完整模型 prompt 或无必要的个人路径。路径在分析事件中可归一化；恢复 manifest 在本地受保护存储保留精确路径。

### 16.2 Fixture 矩阵

- 空项目、非 Git 项目、干净 Git、dirty Git。
- Codex-only、Claude-only、Wave 1 单宿主与混合多宿主。
- Codex → Claude 与 Claude → Codex 的 exact、compatible、assisted、unsupported 样例。
- Cursor、Copilot、Windsurf 各自的 discovery、activation/surface、unknown-version 与只读降级样例。
- 每个 Wave 2 adapter 至少有官方目录结构、invalid syntax、unsupported capability 和零写入 fixture。
- nested CWD、monorepo、多个 instruction 层。
- invalid TOML/JSON/YAML/frontmatter、未知字段和注释。
- duplicate skill/agent、import cycle、失效路径与 conflicting rules。
- 目标已存在、宿主版本不同、unknown frontmatter、unmapped field 与 permission widening。
- symlink/junction 正常目标、循环、授权根逃逸。
- skill dynamic shell、hook command、plugin manifest、MCP executable。
- scan 后并发编辑、磁盘写满、权限失败、进程崩溃、重启恢复。
- secret fixtures 与 prompt-injection fixtures。
- Prompt/Instruction/Skill/Agent persistence classification fixture。
- 每个候选 Skill 的 positive、negative、boundary、ambiguous 和 regression route fixture。
- 大 Skill keep/split/reference 建议及原版/拆分版对照 fixture。
- 历史 trace 的 consent、脱敏、去重、版本关联、并发候选和 eval 污染 fixture。

### 16.3 核心质量指标

| 层级 | 指标 | MVP 门槛 |
|---|---|---|
| North star | 经验证且带可恢复快照的批准 ChangeSet 完成率 | 先建立真实基线，不虚构目标 |
| 安全 | 未批准目标写入、越界写入、秘密外发 | 0 |
| 恢复 | 支持文件 byte-identical rollback | 100% fixture |
| 可靠性 | dry-run 目标目录写入次数 | 0 |
| 并发 | stale preimage 被阻断 | 100% fixture |
| 兼容性 | 未知字段/注释在支持格式中保留 | 100% fixture |
| 正确性 | 资产发现、加载顺序、条件 scope 与官方 fixture 一致 | 100% 核心 fixture |
| 转换安全 | Unsupported 映射被自动生成可应用变更 | 0 |
| 权限安全 | 转换后工具/网络/执行权限静默扩大 | 0 |
| 转换质量 | Exact/Compatible recipe 通过目标 schema 与语义 round-trip | 100% 对应 fixture |
| Adapter 可信度 | 超出已验证版本仍自动开放写入 | 0 |
| Adapter 一致性 | Apply-enabled adapter 通过 conformance suite | 100% |
| 可解释性 | 每个非 Exact 项都有 loss report、来源和人工问题 | 100% |
| 可用性 | 首次用户完成 scan 并理解一个 Finding | 可用性测试建立基线 |
| AI 质量 | 机制路由正确、schema valid、trigger/non-trigger 通过 | 分项报告，不合成单一幻觉分 |
| Skill 路由 | positive trigger recall、negative avoidance、top-1/top-3、no-route precision | 逐模型/任务/版本报告 |
| Skill 改进 | holdout delta、fixed-case rate、candidate win rate、回归数、token/成本/延迟变化 | 无 critical regression；不使用单一健康分 |

### 16.4 指令遵循实验

不复刻或引用未审计的 92%/96% 数字。自建 eval 应：

- 使用真实匿名化 fixture。
- 控制规则数量、位置、冲突、重复、scope 与宿主版本。
- 多次运行并记录随机性、模型、prompt、原始 transcript 与分母。
- 报告置信区间、失败类型和版本回归。
- 分开评估“被加载”“被理解”“被执行”“被硬策略阻断”。

## 17. 实施阶段

### Phase 0：指南与证据基线（本轮）

- 完成项目指南、多宿主支持矩阵、来源审计、核心 ADR 与安全不变量。
- 不添加依赖，不初始化框架。
- 状态：已完成。

### Phase 1：只读 scanner 与 Host Adapter API（核心切片完成；平台加固待办）

- 建立 Java 核心源码边界和 fixture；当前 spike 使用 JDK 脚本编译，正式多模块构建工具待后续最小实验决定。
- 实现受限路径发现、logical/real path、hash、格式与显式授权的最小 Git 管理元数据读取；dirty 状态保持 unknown。
- 定义 HostAdapter、HostDescriptor、capability vocabulary、registry manifest 与 maturity gate；注册 Codex、Claude、Cursor、Copilot、Windsurf/Devin 的 inventory manifest。
- dry-run 零写入测试必须先通过。
- 当前切片：registry、allowlist discovery、logical/real path、streaming SHA-256、编码/换行提示、符号链接 fail-closed、深度/总条目/字节预算、协作取消、确定性部分结果、显式 Git 元数据探测与 JSON CLI 已实现。
- 剩余平台 gate：Windows junction/reparse-point fixture、可重复并发替换 fixture，以及三平台 GitHub runner 首次通过。它们仍是发布前要求，但在实验原型阶段不阻塞后续只读功能；当前五个 adapter 仍保持 `INVENTORY`。

### Phase 2：Codex / Claude Core adapters 与 Effective Context Compiler（进行中）

- Codex `AGENTS`、skills、config、rules、hooks、agents。
- Claude instructions、rules、skills、commands、agents、settings、hooks。
- 输出确定性加载图与来源说明。
- 已完成实验切片：`context codex` 支持授权项目根到 CWD 的 override/base/fallback 选择、优先级、hash 和默认或显式配置的字节预算；配置只从用户明确传入的 TOML snapshot 读取，不隐式访问 `CODEX_HOME`。
- `context claude-code` 支持 main/local/project memory、工作区内最多四跳的递归 imports，以及 `.claude/rules` 的无条件、目标匹配、不匹配和无目标未决状态；imports 跳过 inline/fenced code，并显式报告缺失、循环和外部批准未知。
- Context JSON 升级为 schema v2，增加 `orderingModel`、`resolutionStatus: COMPLETE|PARTIAL` 和结构化 findings。遇到未给 target 的 path rule、import 循环、语义解析预算、symlink 未建模或双 project-memory 歧义时返回 `PARTIAL`，CLI 退出码为 3。
- 已完成第一版宿主无关 Instruction IR：把当前 effective sources 归一为 source identity、logical path、revision/effective hash、load state/order、scope、activation evidence 和 import/shadow provenance；不把原文带入默认输出。
- 已完成 Analyze schema v1 与 `analyze codex|claude-code`：effective-payload 字节完全相同是确定性重复；normalized directive duplicate 和 direct polarity conflict 是明确标注的启发式候选。Analyze schema 与 Context schema v2 分开版本化，context 契约保持兼容。
- 当前 analyze 输出不含正文、normalized text 或 `realPath`，只读且不执行、不写入、不转换；同一 Claude import 的多个父引用只形成 provenance edges，不产生重复 source node。
- 开发命令：`scripts/run-analyze.sh codex <authorized-root> <cwd> [--codex-config <snapshot.toml>]`；`scripts/run-analyze.sh claude-code <authorized-root> <cwd> [--target-file <project-relative-file>]`。
- 当前限制：尚未读取全局/用户/managed 配置、合并 Codex 配置层、模拟 Claude external-import approval、`claudeMdExcludes`、symlink 原生加载或按需 descendant memory。Claude project rules 与 memory 的精确拼接位置仍需版本 fixture；整体 adapter maturity 因此仍不晋级。
- 版本化 conformance 已覆盖 Codex 9 项、Claude Code 10 项、分析器对抗性 8 项；`scripts/run-conformance.sh` 输出机器可读 schema v1 PASS 报告。
- 已实现 ConversionPlan schema v2 与两个 recipe v2：计划包含 source/target semantic profile、mapping grade、provenance、capability delta、loss、未决问题、候选/现有目标 hash 元数据，以及绑定 candidate hash 的 renderer、target validation、semantic round-trip 和 metadata target review profile。
- `scripts/run-convert-preview.sh codex claude-code <authorized-root> <cwd> [--codex-config <snapshot>]` 及反向命令已可运行。命令严格拒绝 `PARTIAL` IR，限定目标探测在授权根目录，不跟随 symlink，只输出元数据，且固定 `writesPerformed=false`、`applyEligible=false`。
- 单一、完整、根级 `AGENTS.md → CLAUDE.md` 会在受限内存形成 canonical `@AGENTS.md\n` wrapper，并实际运行 recipe-specific Claude 结构验证和 hash/scope/import round-trip。candidate bytes 不进入默认 JSON；existing target 只使用 bounded hash/size 元数据，identical/conflict/unsafe/stale 分开表达。
- 其余结构因 content-free IR 不携带正文而通常保持 `ASSISTED/METADATA_ONLY`。Claude → Codex 正文、nested chain、path rule、local override 仍需独立授权的 ephemeral content plane；这不代表整体 adapter maturity 晋级。
- 当前本地 165 项 fixture 全部通过；后续以 `scripts/test-core.sh` 当次完整输出为准。

### Phase 3：Analyzer、Core conversion 与 UI

- 在第一版 duplicate/direct-conflict analyzer 上继续增加预算、secret、可执行内容、路径、schema Findings 与误报评测。
- Codex ↔ Claude 的双向 ConversionPlan、mapping grade、loss report 和冲突元数据已完成第一刀；下一刀实现受限 renderer、target validator、round-trip 和 three-way review，再扩展为 Quick Convert / 批量 Conversion Workbench UI。
- Vue 工作区、Context 图、Findings、Conversion 和 Diff 页面。
- 只生成 ConversionPlan / ChangeSet，不写目标。

### Phase 4：Wave 1 adapters（只读与转换预览）

- Cursor：MDC rules、activation 与 nested scope。
- GitHub Copilot：repository/path/agent instructions 与 surface compatibility。
- Windsurf / Devin Desktop：首选 `.devin/rules`、兼容 `.windsurf/rules`、仅旧 Cascade 使用的 workflows、skills、`AGENTS.md` 与 product/surface 迁移。
- 所有 Wave 1 adapter 通过 conformance suite 前保持只读；Quick Convert 支持选择这些目标但不允许 Apply。

### Phase 5：事务与恢复（仅 fixture）

- vault、journal、snapshot、atomic replace、故障注入与启动恢复。
- byte-identical rollback 与并发阻断达到门槛。

### Phase 6：真实工作区 Apply

- 原生编辑和转换结果统一经过逐能力授权、明确批准、真实配置应用与 post-validate。
- 先开放 Codex/Claude；Wave 1 逐个通过 native validator、round-trip 与权限差异 gate 后再开放，不捆绑发布。
- Recovery Center 与脱敏审计。

### Phase 7：Wave 2 preview、可选 AI 和 Prompt Export

- 为 Cline、Roo Code、Gemini CLI、OpenCode、Continue 提供只读 inventory 与转换预览；Aider 提供 Export Only。
- provider interface、结构化输出、隐私预览、离线 eval。
- 证明 AI 比确定性模板有增益后再默认展示。

### Phase 8：GitHub 与分发

- PR 导出、来源锁定、release、SBOM、attestation 与签名安装包。
- Plugin/Hook 安装仍是独立的高风险后续能力。

### 当前活跃路线：Codex 单资产闭环

- S0：Codex Skill package 只读 inventory 与引用图。
- S1：自然语言/向导 → persistence classification → `SkillBlueprint v1`，preview-only。
- S2：确定性模板、内存候选、静态校验、正负触发例与 Diff/export。
- S3：项目内单文件 Simple Apply/Rollback。
- S4：同一闭环扩展到 AGENTS、Agent TOML 与 Rule/Policy。
- S5：Claude 同等闭环。
- S6：依据用户证据解冻其他宿主、Router、eval、history 和跨宿主能力。

当前状态：Codex `inspect` 第一刀已完成，S0/S1 尚未实现。旧 Gate 4 conversion 继续作为技术能力记录，不再决定近期产品顺序。详细 gate 见 [SKILL_LIFECYCLE_SPEC.md](SKILL_LIFECYCLE_SPEC.md)。

### 当前 Gate 状态（2026-08-04）

| Gate | 目标 | 当前状态 | 仍缺什么 |
|---|---|---|---|
| Gate 1 | 官方证据、格式边界、决策基线 | 已完成基线 | 持续做版本漂移复核 |
| Gate 2 | 只读 inventory 与路径安全 | 核心切片完成，Linux/macOS/Windows CI 已通过 | Windows reparse/junction、确定性并发替换 fixture |
| Gate 3 | Codex/Claude 项目语义读取与 IR | 实验性纵向切片完成 | 用户/managed 层、完整配置合并、外部批准、lossless parser/native validation；整体 adapter 仍为 Inventory |
| Gate 4 | 双向转换预览 | canonical Codex root wrapper 纵向切片已验证；能力冻结 | 只有核心闭环验证后才复审通用/反向 renderer |
| Gate 5 | ChangeSet、快照、事务与恢复 | 未开始 | journal、vault、stale-hash、故障注入、byte-identical rollback |
| Gate 6 | 真实工作区 Apply | 未开始 | Gate 2/4/5 全通过、明确批准与 post-validate |
| Gate 7 | UI、Wave 1、GitHub/分发 | GitHub 发布基线完成，其余未开始 | Vue 工作流、其他宿主语义、PR 导出、安装包、签名/SBOM |

## 18. MVP 发布门槛

- 所有安全不变量有自动化测试。
- 启动时能恢复或清晰隔离未完成事务。
- dry-run 对目标工作区写入为零。
- symlink/junction 越界、路径穿越、stale hash 全部 fail closed。
- 支持的每种配置都能无损保留未知字段或明确降级为“只读”。
- Codex ↔ Claude 两个 Core 方向都覆盖初始矩阵；Unsupported 项不会生成可应用写入。
- Wave 1 adapter 至少达到 Read + Conversion preview；任何未通过 conformance suite 或超出已验证版本的 adapter 均不得 Apply。
- Exact/Compatible 转换必须通过目标 schema、semantic diff 与 round-trip fixture。
- 转换不得静默扩大权限、工具、网络或可执行行为。
- 每个 ChangeSet 都有来源、Diff、验证、批准、快照与 rollback ID。
- 外部 AI、GitHub 和 telemetry 默认关闭且权限透明。
- 安装包有 checksum、SBOM 和构建来源证据。
- 文档明确区分官方规范、同行评审研究、项目启发式和社区说法。

## 19. 当前决定与待验证问题

已决定：

- Vue + Java 是 MVP 主栈；Python 不进入安全写入链。
- 本地优先、默认离线、只读起步。
- 同时提供 Native、Convert、Multi-target Export 三种模式；Codex ↔ Claude 是首批 recipe，不是写死的唯一方向。
- 多宿主转换使用统一 IR、Host Adapter Registry、版本化 recipe、目标校验与 round-trip；不做文件名级互转或 N×N 成对转换器。
- Codex/Claude 为 Core；Cursor/Copilot/Windsurf-Devin 为 Wave 1；Cline/Roo/Gemini CLI/OpenCode/Continue 为 Wave 2 Preview；Aider 为 Export Only。
- `AGENTS.md` 作为 portable instruction 的优先共享面，`CLAUDE.md` 可导入并追加 Claude 差异；各 adapter 仍按真实 surface 判断是否加载。
- Git 与 hooks 都不是回退基础。
- 行数是带来源的预算提示，不是遵循率保证。
- 产品前台定位为 Skills 生命周期助手，现有配置工作台是治理内核。
- 历史驱动改进只生成不可变候选；Hook 只做轻量事件感知，绝不直接自修改。
- Skill Manager 是 catalog/router 服务；路由优先确定性过滤，LLM 只在模糊候选上可选增强。

需要通过 spike/fixture 决定：

- loopback HTTP 与同进程 IPC 的最终桌面通信方式。
- Java 中各格式 lossless/CST 库的最小依赖组合。
- OS keychain/keystore 的跨平台实现与无安全存储时的降级 UX。
- Codex 官方页面对 32 KiB 单文件/组合口径不一致时的兼容测试。
- 跨宿主 skill/workflow 的 Exact/Compatible frontmatter 子集与 recipe 版本策略。
- Assisted 转换问题在 UI 中的逐项确认与批量处理方式。
- 原生 CLI 验证的稳定、非交互、只读调用方式和版本矩阵。
- GitHub Copilot 各 surface 的能力探测与 UI 表达；未知 surface 默认只读。
- Wave 1 adapter 的独立发布、降级和紧急禁用机制。
- `SkillBlueprint v1`、`SkillPackageIr v1`、Route/Eval/Trace schema 的字段和兼容策略。
- paired repeated trials 的统计门槛、holdout 大小、canary 范围和多目标 Pareto 选择 UX。
- 各宿主可稳定、用户授权地读取哪些历史数据；不能把 transcript 文件格式当作统一公共协议。

## 20. 下一条 Codex 实现任务

```text
Use personal-ai-project-design and skill-creator.

Read AGENTS.md and docs/PROJECT_GUIDE.md in this repository.
Implement the Codex Project Skill Draft slice without target writes.

Scope:
- Add read-only Codex project Skill package inventory for `.agents/skills/<name>/SKILL.md`.
- Define `SkillBlueprint v1` and deterministic persistence triage for Prompt / Instruction / Skill / Agent / deterministic tool.
- Produce an in-memory Codex Skill candidate plus human preview and machine-readable metadata; do not create target files.
- Require structured goal, scope, trigger, exclusion, inputs, outputs, steps, validation, risk, and at least three should-trigger plus three should-not-trigger cases before a blueprint is complete.
- Include static checks and a text Diff/export boundary; keep LLM integration, Claude rendering, decomposition, router, trace learning, hooks, UI and Apply out of this slice.
- Preserve ConversionPlan schema v2, Context v2, Analyze v1, Instruction IR v1 and existing semantic profile IDs.

Run scripts/test-core.sh and scripts/run-conformance.sh.
Before adding a dependency, explain why the JDK is insufficient.
```

该切片完成后进入 Diff/export，再实现 ADR-026 的单文件 Simple Apply/Rollback；严格保持当前切片零目标写入，不同时启动历史学习、遗传优化、通用转换或更多宿主。

详细来源和证据等级见 [RESEARCH_NOTES.md](RESEARCH_NOTES.md)。
