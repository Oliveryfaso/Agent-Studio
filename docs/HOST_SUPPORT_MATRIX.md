# 多宿主支持矩阵

- 基线日期：2026-08-03
- 用途：定义 Agent Config Workbench 支持哪些编码代理、支持到什么深度，以及转换时必须保留哪些宿主差异。
- 说明：“主流候选”表示本项目优先覆盖的典型产品集合，不代表市场份额排名。宿主格式变化很快，每个 adapter 都必须记录已验证版本和官方来源。

## 1. 产品路线等级与运行成熟度

下表是产品路线优先级，不是当前已经实现的能力：

| 路线等级 | 目标能力 | 写入资格 |
|---|---|---|
| Core | 发现、Effective Context、原生校验、转换、ChangeSet、应用与回退 | 仅在对应版本 fixture 全部通过后开放 |
| Beta Adapter | 发现、解析、原生预览、转换预览、loss report | 默认只读；目标 schema、round-trip 与事务 fixture 通过后逐宿主开放 |
| Preview Adapter | 只读盘点、能力识别、IR 导入、转换建议 | 不生成可应用写入 |
| Export Only | 导出 portable instruction、Prompt 或人工迁移报告 | 不修改该宿主的原生配置 |

运行成熟度单独按 `Inventory → Read → Conversion preview → Apply` 晋级。路线为 Core 的宿主也可能只有 Inventory 实现；路线等级、运行成熟度、单条转换的 Exact / Compatible / Assisted / Unsupported 三者互不替代。

当前另有一个比整体 adapter maturity 更窄的能力标记：Codex 与 Claude Code 的项目级 Effective Instruction Chain 与 Instruction IR 分析为 `EXPERIMENTAL_PROJECT_SEMANTICS`，对应 `codex-project-semantics-v1` 与 `claude-code-project-semantics-v1`。它已覆盖 Codex 显式配置快照中的 fallback/budget、Claude 工作区内 imports 和按目标文件计算的 rules，并可把 effective sources 投影为宿主无关 IR，生成 provenance、确定性的 effective-payload duplicate 与启发式 directive duplicate/direct-polarity-conflict 候选。两套宿主 profile 与 `instruction-analysis-v1` 目前共有 27 项独立 conformance；Context schema v2 保持兼容，Analyze schema v1 独立版本化。该能力仍不代表完整 Read adapter，因为用户/managed 配置、配置层合并、外部 import 批准状态、symlink 加载、按需 descendant memory、版本化 lossless parse 与原生验证尚未完成。

上述分析仅对 Codex 与 Claude Code 当前已实现的项目语义开放；Cursor、Copilot、Windsurf / Devin Desktop 和 Wave 2 宿主仍只有 inventory/planned 能力，不会借用 Codex/Claude 语义生成 IR 结论。Analyze JSON 不含正文、normalized text 或 `realPath`。

Codex ↔ Claude Code 另有更窄的 Gate 4 实验能力：ConversionPlan schema v2 能生成双向 recipe、mapping grade、loss、capability delta、未决问题和现有目标 hash 冲突元数据。其中只有“单一完整根 `AGENTS.md` → canonical `CLAUDE.md` import wrapper”通过受限 renderer、recipe-specific target validation 和 round-trip；大多数候选仍为 `METADATA_ONLY/ASSISTED`。这不提升表中的整体 adapter maturity，也不代表完整双向 Conversion Preview，更不能 Apply。

## 2. 首批宿主路线

| 批次 | 宿主 | 路线等级 | 当前运行成熟度 | 首批识别的项目资产 | 重点差异 |
|---|---|---|---|---|---|
| Core | OpenAI Codex | Core | Inventory | `AGENTS.md`、`.agents/skills/`、`.codex/config.toml`、agents、command policies、hooks、plugins | instruction、skill、agent、policy 需要分开建模 |
| Core | Claude Code | Core | Inventory | `CLAUDE.md`、`.claude/rules/`、skills、commands、agents、settings、hooks、plugins | import、path scope、permissions 与 hooks 有宿主语义 |
| Wave 1 | Cursor | Beta Adapter | Inventory | `.cursor/rules/*.mdc`、`.cursor/commands/*.md`、`AGENTS.md` 兼容入口 | rule activation 包含 Always、Auto Attached、Agent Requested、Manual；commands 仍标为 beta；`.cursorrules` 是 legacy |
| Wave 1 | GitHub Copilot | Beta Adapter | Inventory | `.github/copilot-instructions.md`、`.github/instructions/*.instructions.md`、`.github/agents/*.md`、`.github/skills/*/SKILL.md` | 支持情况依 Copilot surface 而异；CLI skill 还兼容 `.agents/skills` 与 `.claude/skills`，不能把 IDE、CLI、coding agent 行为视为完全相同 |
| Wave 1 | Windsurf / Devin Desktop | Beta Adapter | Inventory | `.devin/rules/*.md`、兼容 `.windsurf/rules/*.md`、Cascade `.windsurf/workflows/*.md`、`.agents/skills/*/SKILL.md`、`AGENTS.md` | 当前产品迁移中；rule、workflow、skill 与 agent surface 必须分开，旧 Cascade workflow 不适用于 Devin Local Agent |
| Wave 2 | Cline | Preview Adapter | Planned | `.clinerules/`、`.clinerules/workflows/`、`AGENTS.md` 及兼容规则入口 | `paths` 条件规则与显式 slash workflow 需要分开；兼容读取不等于原生等价 |
| Wave 2 | Roo Code | Preview Adapter | Planned | `.roo/rules/`、`.roo/rules-{mode}/`、`.roomodes`、legacy `.roorules*` | mode、工具权限与 mode-specific rules 是强宿主特性 |
| Wave 2 | Gemini CLI | Preview Adapter | Planned | `GEMINI.md`、`.gemini/settings.json`、`.geminiignore` | 分层 memory、配置优先级、checkpoint 与受信目录不能压扁成一份 Markdown |
| Wave 2 | OpenCode | Preview Adapter | Planned | `AGENTS.md`、`.opencode/agents/`、`.opencode/skills/`、`opencode.json/jsonc` | agent/skill 权限和兼容 skill 来源需要保留 provenance |
| Wave 2 | Continue | Preview Adapter | Planned | `.continue/rules/` 与相关本地配置 | rule 可带 glob/description/alwaysApply；排序和适用模式需进入 IR |
| Compatibility | Aider | Export Only | Planned | `.aider.conf.yml`、通过 `read` 加载的 conventions 文档 | 主要是 CLI 配置与只读上下文，不强行模拟完整 rules/skills/agents 体系 |

首批深度实现仍是 Codex 与 Claude Code。Wave 1 在同一 Host Adapter API 上紧随其后；Wave 2 先只读，避免“产品名字很多、实际转换不可信”。

## 3. 官方格式证据

### Cursor

- [Cursor Rules](https://docs.cursor.com/context/rules)：项目规则位于 `.cursor/rules`，使用 MDC；支持 Always、Auto Attached、Agent Requested、Manual 等激活方式，并将 `.cursorrules` 标为 legacy。
- [Cursor CLI](https://docs.cursor.com/en/cli/using)：CLI 可读取 `.cursor/rules`，并支持根目录的 `AGENTS.md` 与 `CLAUDE.md`。
- [Cursor Commands](https://docs.cursor.com/en/agent/chat/commands)：可复用 slash commands 位于 `.cursor/commands/*.md`；官方当前仍将此能力标为 beta。

### GitHub Copilot

- [Custom instructions support](https://docs.github.com/en/copilot/reference/custom-instructions-support)：列出 repository-wide、path-specific、agent instruction 与 personal instruction 的文件位置和各 surface 支持情况。
- [Custom agents configuration](https://docs.github.com/en/copilot/reference/custom-agents-configuration)：custom agent 使用带 YAML frontmatter 的 Markdown，并定义 tools、MCP、权限等配置边界。
- [Response customization](https://docs.github.com/en/copilot/concepts/prompting/response-customization)：区分 repository instructions、path-specific instructions、agent instructions 与 prompt files。
- [Adding agent skills for Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills)：项目 skills 可位于 `.github/skills`、`.agents/skills` 或 `.claude/skills`，并警告预授权 shell 会扩大执行风险。

### Windsurf / Devin Desktop

- [Memories & Rules](https://docs.devin.ai/desktop/cascade/memories)：当前首选 workspace rule 路径是 `.devin/rules/*.md`，`.windsurf/rules/*.md` 为兼容 fallback；`AGENTS.md` 由同一 rules engine 处理，并按目录产生 scope。
- [Workflows](https://docs.devin.ai/desktop/cascade/workflows)：旧 Cascade workflow 仍位于 `.windsurf/workflows/*.md` 并通过 slash command 调用，但官方明确其不适用于 Devin Local Agent，建议迁移到 skills。
- [Devin Skills](https://docs.devin.ai/product-guides/skills)：推荐 `.agents/skills/<name>/SKILL.md`，同时识别多种兼容目录；skill 的 allowed-tools、triggers 与动态命令内容必须进入风险模型。

### Cline

- [Cline Rules](https://docs.cline.bot/customization/cline-rules)：原生 workspace rules 位于 `.clinerules/`，支持 `paths` frontmatter；也会识别部分其他工具的规则格式和 `AGENTS.md`。
- [Using Commands](https://docs.cline.bot/core-workflows/using-commands)：自定义 workflow 位于 `.clinerules/workflows/`，通过 slash command 调用。

### Roo Code

- [Custom Instructions](https://roocodeinc.github.io/Roo-Code/features/custom-instructions/)：首选 `.roo/rules/` 与 `.roo/rules-{modeSlug}/`，legacy 单文件仅作为 fallback，并定义 global/workspace/mode 组合顺序。
- [Customizing Modes](https://roocodeinc.github.io/Roo-Code/features/custom-modes/)：项目 custom modes 使用 `.roomodes`，当前可读取 YAML 或 JSON，UI 编辑会产生格式迁移行为。

### Gemini CLI

- [Configuration](https://google-gemini.github.io/gemini-cli/docs/get-started/configuration.html)：定义 `GEMINI.md` 层级 memory、`.gemini/settings.json`、配置优先级、include directory 与过滤设置。
- [CLI commands](https://google-gemini.github.io/gemini-cli/docs/cli/commands.html)：`/memory` 可查看和刷新有效 memory，`/restore` 依赖 checkpoint 配置，`/init` 可生成 `GEMINI.md`。

### OpenCode

- [OpenCode initialization](https://opencode.ai/docs)：`/init` 会分析项目并生成应提交的根 `AGENTS.md`。
- [Agents](https://opencode.ai/docs/agents)：agent 可在 Markdown/frontmatter 中声明工具与权限。
- [Agent Skills](https://opencode.ai/docs/skills)：项目 skill 位于 `.opencode/skills/<name>/SKILL.md`，并支持 `.claude/skills` 与 `.agents/skills` 兼容来源。

### Continue 与 Aider

- [Continue Rules](https://docs.continue.dev/customize/rules) 与 [Rules deep dive](https://docs.continue.dev/customize/deep-dives/rules)：项目 rules 位于 `.continue/rules`，支持 Markdown/YAML 和条件元数据。
- [Aider conventions](https://aider.chat/docs/usage/conventions.html) 与 [YAML config](https://aider.chat/docs/config/aider_conf.html)：conventions 可通过 `--read` / `read` 加载，配置文件为 `.aider.conf.yml`。

## 4. Host Adapter 契约

Phase 1 的 `HostAdapter` v1 故意保持惰性，只返回不可变 manifest：API version、adapter version、identity、路线等级、当前成熟度、官方证据、capability 声明和 discovery allowlist。它没有文件系统句柄，也不能解析、渲染、转换或写入。

后续 adapter 晋级时，解析与渲染能力通过受限的独立接口增加，而不是扩张 inventory manifest 的权限。目标契约如下：

```text
HostAdapter
  identity()                  # host id、display name、已验证版本范围
  officialEvidence()          # 官方 URL、核查日期、schema/行为版本
  discover(authorizedRoot)    # allowlist、logical/real path、scope
  parse(bytes)                # lossless/CST 优先；未知字段可保留
  compileEffectiveContext()   # precedence、activation、CWD/file/mode 条件
  capabilities()              # instruction/skill/agent/policy/hook 等能力矩阵
  toNeutralIR()               # effective context → 带 provenance 与宿主扩展字段的宿主无关 IR
  renderCandidate(ir)         # 只渲染 staging candidate
  validateNative(candidate)   # schema、预算、引用、权限与版本检查
  extractRiskDelta()          # 权限、网络、执行、自动调用变化
```

注册流程：`Inventory only → Read adapter → Conversion preview → Apply enabled`。每次升级必须用目标版本 fixture 晋级，不能因为路径被识别就宣称“已支持”。

## 5. N → N 转换模型

系统只维护 `N` 个宿主 adapter 和一组按能力编写的 recipe，不维护 `N × (N-1)` 套成对复制器：

```text
source native files
  → source adapter
  → neutral IR + host extensions + provenance
  → target capability matcher
  → versioned recipe
  → target adapter renderer
  → native validation + semantic diff + loss report + round-trip
  → ordinary ChangeSet
```

这允许 Cursor rule 转到 Windsurf rule、Cline workflow 转到 Claude skill、`AGENTS.md` portable subset 导出到多个宿主，同时仍能把 mode、permission、hook、activation 等不可移植语义标为 Assisted 或 Unsupported。

## 6. Portable Core 与宿主扩展

可移植核心优先保存：

- 项目事实、命令、架构指针、约定、硬限制、gotchas、验证步骤。
- instruction 的适用路径、激活条件和证据来源。
- skill/workflow 的目标、输入、步骤、成功条件和支持文件引用。
- agent 的职责、输入输出契约和最小工具意图。

优先利用两个正在形成的兼容平面：

- `AGENTS.md` portable instruction subset：多个宿主可读取，但加载范围和 surface 支持仍由 adapter 验证。
- `SKILL.md` / Agent Skills portable subset：`.agents/skills` 已被多个工具识别；name、description、正文和 supporting files 可进入共同 IR，allowed-tools、trigger、参数和动态执行继续保留为宿主扩展。

必须作为宿主扩展保留、不得静默归一化：

- policy/permission/sandbox 的强制语义。
- hook/plugin/MCP 的执行与供应链行为。
- Cursor、Windsurf/Devin、Continue 的激活模式与 glob 细节，以及 Windsurf/Devin 的 product/surface 迁移状态。
- Roo Code mode、工具组和文件限制。
- Copilot surface 差异。
- Gemini memory precedence、trusted folder、checkpoint 设置。
- 任意未知 frontmatter、注释、排序、编码和行尾信息。

## 7. 暂不做原生写入适配的产品

Lovable、Bolt、v0、Replit Agent 等托管式生成产品可以通过 Prompt Export、通用 `AGENTS.md` 或用户提供的官方可移植格式接入，但在没有稳定、公开、可本地验证的项目配置契约前，不建立可写 adapter。UI 应显示“Export Only”，不能用“已支持”暗示能安全管理其平台侧状态。
