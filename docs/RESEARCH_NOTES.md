# 研究与证据备忘录

- 核查日期：2026-08-04
- 范围：Codex、Claude Code、Skills 效果与路由、自动 prompt/skill 优化、长上下文与多约束研究、Agent/Tool 安全、GitHub 供应链
- 原则：厂商官方文档用于产品行为；论文用于一般性工程推论；无方法和原始数据的博客数字不能升级为事实

## 1. 证据等级

| 等级 | 定义 | 本项目用途 |
|---|---|---|
| A | 同行评审原始论文，公开方法、数据或可审查评测 | 支撑一般性设计原则与自建 eval |
| B | 官方产品文档、厂商原始研究或方法清晰的公开预印本 | 支撑当前宿主行为、版本约束与安全建议 |
| C | 机构/个人博客，缺少完整实验方法或可复现材料 | 形成待验证假设，不作产品门槛 |
| D | 社媒、截图、转载和二次转述 | 只能作为研究线索 |

所有产品规则都应在 UI 中携带 evidence type、来源 URL、核查日期与适用宿主版本。

## 2. 截图中 92% / 96% / 200 行说法的核查

### 2.1 追溯结果

找到的可能原始页面是 SFEIR Institute 的 [Claude Code Memory System deep dive](https://institute.sfeir.com/en/claude-code/claude-code-memory-system-claude-md/deep-dive/)。页面声称单文件和模块化规则存在 92%/96% 差异，但没有公开：

- 模型与 Claude Code 版本。
- 任务集、规则文本和样本量。
- “application rate”的评分定义。
- 随机性控制、重复次数和置信区间。
- 原始日志、代码、数据集或独立复现。

而且转述中的“200 行拆为 5 × 30 行”与 200 不相等，也与原页其他表述不一致。“400 行后下降”和二手网页新增的 71% 同样没有可审计曲线或原始数据。

结论：证据等级 C/D。严谨措辞只能是“截至核查日未发现可审计原始实验”，不能写成“SFEIR 已证明”。

### 2.2 官方真正支持的结论

Anthropic 当前 [Memory 文档](https://code.claude.com/docs/en/memory) 支持以下结论：

- `CLAUDE.md` 是上下文，不是强制配置。
- 具体、简洁、结构化的指令更容易稳定遵循。
- 官方建议每个 `CLAUDE.md` 目标少于 200 行；这是建议，不是硬截断或遵循率保证。
- 更长文件会消耗更多上下文，并可能降低 adherence。
- 矛盾规则可能被任意选择，因此需要冲突检查。
- `@imports` 只改善组织，不减少启动上下文。
- `.claude/rules` 只有配置 `paths` 后才按匹配文件加载；无 `paths` 的模块仍会启动加载。

因此，项目可以采用“短根文件 + 单一主题 + path scope + on-demand skill”，但不能声称“机械拆文件必然提升 4 个百分点”。

## 3. Codex 官方机制摘要

### 3.1 `AGENTS.md`

- [AGENTS.md 发现与优先级](https://learn.chatgpt.com/docs/agent-configuration/agents-md#how-codex-discovers-guidance)：全局层先读，项目层从 repo root 向 CWD 组合；每目录最多一个文件；近层更具体。
- 默认 `project_doc_max_bytes` 为 32 KiB。官方页面对单文件与组合预算的表述存在轻微口径差异，因此产品应同时计算单文件和有效链累计值，并通过真实版本 fixture 验证。
- 新 run / TUI 会话重建指令链，可用官方验证方式确认加载来源。
- [Customization overview](https://learn.chatgpt.com/docs/customization/overview#agents-guidance) 建议根文件只保留小而重要的项目指导，把确定性约束交给 formatter、linter、test、CI 或权限基础设施。

### 3.2 Skills

- [Build skills](https://learn.chatgpt.com/docs/build-skills)：`SKILL.md` 至少包含 `name` 与 `description`；可有 `scripts/`、`references/`、`assets/`、UI metadata。
- Codex 先看到 skill 名称、描述与路径，选中后再读取完整正文；这是 progressive disclosure。
- 初始 skill 列表有整体上下文预算，描述可能被缩短，因此触发用途和边界必须前置。
- 项目 skills 位于从 CWD 向 repo root 各层的 `.agents/skills/`。

### 3.3 Config、Rules、Hooks、Agents 与 Plugins

- [Config precedence](https://learn.chatgpt.com/docs/config-file/config-basic#configuration-precedence)：项目 `.codex/config.toml` 只在已信任项目中加载；机器本地和企业约束不能被项目随意替代。
- [Rules](https://learn.chatgpt.com/docs/agent-configuration/rules)：Codex `.rules` 控制 sandbox 外命令，决策最严格者优先；文件格式为 Starlark，并支持 match/not_match 测试。
- [Hooks](https://learn.chatgpt.com/docs/hooks)：多来源 hook 共同加载；命令 hook 可能并发；pre hook 是 guardrail 但非完整安全边界；post hook 不能撤销副作用。
- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)：Codex custom agent 使用 TOML 配置层，可设置独立职责、模型、sandbox 与 skills。
- [Plugin structure](https://developers.openai.com/plugins/build/plugins#plugin-structure)：plugin 以 `.codex-plugin/plugin.json` 为入口，可组合 skills、hooks、MCP 和 assets；安装 cache 不是源文件。
- [Agent approvals and security](https://learn.chatgpt.com/docs/agent-approvals-security)：保持版本控制、审阅 Diff、使用审批和 sandbox；本项目仍需独立恢复，因为 Git 不覆盖所有目标文件。

## 4. Claude Code 官方机制摘要

### 4.1 Instructions 与 Rules

- [Memory and rules](https://code.claude.com/docs/en/memory)：managed、user、project、local 内容按层组合；子目录 instruction 可能按需加载。
- Claude Code 原生读取 `CLAUDE.md`，不直接读取 `AGENTS.md`；官方建议用 `CLAUDE.md` 的 `@AGENTS.md` import 实现共享。
- `.claude/rules/**/*.md` 可递归组织；一个文件一个主题；`paths` frontmatter 控制按文件加载。
- 项目外 import 首次需要批准；import 递归深度有限，应做循环和越界检查。

### 4.2 Skills 与 Commands

- [Skills](https://code.claude.com/docs/en/skills)：custom commands 已并入 skills；旧 `.claude/commands` 继续工作，同名时 skill 优先。
- 新建应使用 `.claude/skills/<name>/SKILL.md`，因为它支持 supporting files、调用策略、工具权限和隔离上下文。
- 官方建议 `SKILL.md` 少于 500 行，详细参考移入支持文件。
- `description + when_to_use` 默认列表项有字符上限；描述要先写关键触发用途。
- skill 可含动态 shell 注入，扫描时必须作为可执行风险，绝不能运行。

### 4.3 Agents、Settings、Hooks、Plugins 与 Checkpoint

- [Subagents](https://code.claude.com/docs/en/sub-agents)：项目 agent 位于 `.claude/agents/`，Markdown + YAML frontmatter；身份来自 `name`；重复名必须报告。
- [Settings](https://code.claude.com/docs/en/settings) 与 [Permissions](https://code.claude.com/docs/en/permissions)：managed 和本地层有明确优先级；权限判断遵循 deny/ask/allow，sandbox 与权限应同时使用。
- [Hooks reference](https://code.claude.com/docs/en/hooks) 与 [Hooks guide](https://code.claude.com/docs/en/hooks-guide)：hook 以用户权限运行，必须视为可执行代码，不能用作备份或事务。
- [Security](https://code.claude.com/docs/en/security)：不可信内容、MCP 和网络数据存在 prompt injection 风险，仍需最小权限、审批与隔离。
- [Plugins](https://code.claude.com/docs/en/plugins)：plugin 能分发 skills、agents、hooks 和 MCP，是高信任供应链组件。
- [Checkpointing](https://code.claude.com/docs/en/checkpointing)：内置 rewind/checkpoint 不覆盖 Bash、外部程序、hooks 和并发会话的所有修改，不能替代 Git 或本工具恢复仓。

## 5. 原始研究及其可用边界

### 5.1 长上下文与多约束

| 研究 | 等级 | 主要结果 | 本项目可以推论 | 不能推论 |
|---|---|---|---|---|
| [Lost in the Middle, TACL 2024](https://aclanthology.org/2024.tacl-1.9/) | A | 多文档 QA/KV retrieval 中，信息位置影响表现，常见首尾优于中部 | 展示关键规则位置，避免把所有高优先内容埋在长链中 | 不能换算为 `CLAUDE.md` 行数遵循率 |
| [RULER, COLM 2024](https://openreview.net/pdf?id=kIoBbc76Sy) | A | 上下文与任务复杂度增加时，多数模型有效表现低于宣称窗口 | 预算应看有效上下文和任务复杂度 | 不能断言所有新模型都按同一曲线下降 |
| [IHEval, NAACL 2025](https://aclanthology.org/2025.naacl-long.425/) | A | 多层级冲突使所有被测模型明显下降 | 必须做来源、优先级和冲突可视化 | 不能仅靠文件靠后就保证安全解决冲突 |
| [MOSAIC, EACL 2026](https://aclanthology.org/2026.eacl-long.62/) | A | 约束类型、数量和位置影响遵循，并存在模型差异 | 自建 eval 要控制约束数、类型、位置和版本 | 不能给出跨模型通用行数阈值 |
| [IFScale](https://arxiv.org/abs/2507.11538) | B | 人工多指令任务随密度增加出现下降与位置偏差 | 同时指令越多越需要真实测试 | 不能外推 200 行等于某百分比 |

### 5.2 Prompt injection 与 Agent/Tool 安全

| 研究 | 等级 | 主要结果 | 工程影响 |
|---|---|---|---|
| [AgentDojo, NeurIPS 2024](https://proceedings.neurips.cc/paper_files/paper/2024/hash/97091a5177d8dc64b1da8bf3e1f6fb54-Abstract-Datasets_and_Benchmarks_Track.html) | A | 工具返回的不可信数据可通过间接注入劫持 agent，现有攻防均有失败面 | repo、README、skill、rule、网页全部作为 data；执行器不接受自然语言直接授权 |
| [Task Shield, ACL 2025](https://aclanthology.org/2025.acl-long.1435/) | A | 检查指令/工具调用与用户目标是否一致，可改善安全与效用 | 每个特权动作经过 task-alignment/capability gate |
| [ToolEmu, ICLR 2024](https://openreview.net/pdf?id=GEcwtMk1uA) | A | 模拟 sandbox 有助于系统发现 agent/tool 长尾风险 | 先在 fixture/模拟环境验证写入和恢复，再接真实工作区 |
| [Indirect prompt injection](https://arxiv.org/abs/2302.12173) | B | 检索数据可远程影响工具调用与数据外泄 | 外部内容与特权工具分离，秘密默认不读不发 |
| [OpenAI instruction hierarchy](https://openai.com/index/the-instruction-hierarchy/) | B | 显式可信层级训练可提高对低权限注入的鲁棒性 | IR 保存 trust/provenance 层级；不能把顺序当唯一防线 |
| [Anthropic prompt injection defenses](https://www.anthropic.com/research/prompt-injection-defenses) | B | 厂商明确表示 agent 防御仍非免疫 | UI 不承诺“已完全防注入”；仍需最小权限和人审 |

## 6. GitHub 与供应链依据

- [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)：默认 token 只读、第三方 Action pin 完整 SHA、避免把不可信上下文拼进 shell。
- [Artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)：发布构建来源证明并支持 SBOM attestation。
- [Supply chain security](https://docs.github.com/en/code-security/concepts/supply-chain-security/supply-chain-security)：dependency graph、review、Dependabot、immutable releases 与 attestations。
- [API credential security](https://docs.github.com/en/rest/authentication/keeping-your-api-credentials-secure)：最小权限、短有效期、优先 fine-grained token，不把凭据提交到 repo。

## 7. 由证据导出的产品规则

1. 行数只能是有来源的提示，不是健康或遵循率的代理变量。
2. 机械拆分不等于减少上下文；必须计算实际加载条件。
3. 有效上下文编译器应显示来源、顺序、scope、冲突、位置和预算。
4. 软 instruction 不能承担不可丢文件、不可越界、不可泄密等硬安全义务。
5. AI 只产生候选计划；独立策略引擎、最小权限和人审决定特权动作。
6. 外部 GitHub 内容在被信任前一律按不可信数据处理。
7. 写入必须 staged、可视、可验证、可恢复，且回退独立于 Git 和宿主 checkpoint。
8. 自建 eval 要公布样本、版本、分母、重复次数、置信区间和原始 trace。

## 8. 项目文案禁区

不得写：

- “SFEIR 实验证明 200 行以内遵守率 92%。”
- “拆成 5 个 30 行文件必定达到 96%。”
- “超过 400 行会降到 71%。”
- “模块化本身降低 token。”
- “Hook 可以保证不会丢文件。”
- “Git 可以恢复所有本地 agent 配置。”
- “安装该工具即可免疫 prompt injection。”

可以写：

> Anthropic 当前官方建议单个 `CLAUDE.md` 目标少于 200 行，但没有给出通用遵循率。长上下文、多约束和指令冲突研究表明表现受长度、位置、数量、冲突与模型版本共同影响，因此本工具把阈值作为可配置启发式，并用仓库级 eval 验证。

## 9. 来源更新策略

- 每个宿主 adapter 记录 `verified_at`、文档 URL 与最低/最高已测版本。
- 官方路径、字段或优先级变化时先更新 fixture，再更新文案和 renderer。
- 社区数字只有在找到方法、代码、数据和独立复现后才能升级等级。
- 所有可能执行代码的格式变化必须触发安全评审，而不是普通兼容性更新。

## 10. 多宿主扩展结论

2026-08-03 对 Cursor、GitHub Copilot、Windsurf、Cline、Roo Code、Gemini CLI、OpenCode、Continue 与 Aider 的官方文档核查表明：这些工具虽然都能接收项目指令，但原生能力并不处于同一层级。差异至少覆盖：

- repository、user、system、managed 等来源层级；
- always、glob、description/model decision、manual、mode、surface 等激活条件；
- instruction、rule、workflow/command、skill、agent、permission、hook 的类型边界；
- Markdown、MDC、YAML、JSON/JSONC、TOML 等格式与未知字段保留要求；
- 是否提供原生 schema、checkpoint、trusted folder、ignore 与权限控制。

因此“支持某宿主”必须拆成 Inventory、Read、Conversion preview、Apply 四个成熟度，不再使用一个布尔字段。所有新宿主经统一 Host Adapter Registry 接入，完整文件矩阵和官方 URL 见 [HOST_SUPPORT_MATRIX.md](HOST_SUPPORT_MATRIX.md)。托管式生成平台在没有稳定可验证的本地配置契约前只做 Export Only。

## 11. Skills 是否能提升模型/Agent 表现

结论必须写成条件句：**经过人工策划、与任务匹配、版本正确并通过评测的 Skill 可以显著提升部分任务；无关、过期或路由错误的 Skill 可能无效或负向。** 不能写成“有 Skill 一定更强”或“LLM 自动生成的 Skill 普遍优于人工 Skill”。

| 研究 | 等级 | 主要结果 | 工程推论 |
|---|---|---|---|
| [SkillsBench](https://arxiv.org/abs/2602.12670) | B，2026 预印本 | 86 个任务、11 个领域、7 种 agent/model 配置和 7,308 条轨迹；人工策划 Skill 平均提升 16.2 个百分点，但 84 个可比较任务中 16 个变差；自生成 Skill 平均无增益 | 先做人工/模板 baseline；按任务和模型评测，不用平均值替代每个 Skill 的证据 |
| [SWE-Skills-Bench](https://arxiv.org/abs/2603.15401) | B，2026 预印本 | 49 个公开 SWE Skill、约 565 个真实仓库任务；39/49 没有 pass-rate 提升，平均仅 +1.2%；少数专用 Skill 最多 +30%，3 个因版本不匹配最多 -10%，token 开销可达 451% | 专用、版本匹配和低开销比“装更多 Skill”重要；路由、版本与成本必须进入 gate |
| [Evaluating AGENTS.md](https://arxiv.org/abs/2602.11988) | B，2026 预印本 | 138 个实例、12 个仓库加 SWE-bench Lite；开发者上下文平均约 +4%，LLM 生成上下文约 -3%，成本超过 20% | 不能把 LLM 生成设为默认真理；短而具体的项目事实和要求更值得保留 |
| [ASSAY: Not All Skills Help](https://arxiv.org/abs/2606.15390) | B，2026 预印本 | 随机 masking 估计每个 Skill 的任务级作用；报告 AppWorld hardest 47.4% 相对提升、tau-bench retail GPT-4.1 8.7% 相对提升 | 瓶颈常在 task-to-skill matching；对特定任务抑制负向 Skill，不轻易全局删除 |
| [AgentSkillOS](https://arxiv.org/abs/2603.02176) | B，2026 预印本 | 在 200 至 200K Skill 生态中测试能力树与 DAG orchestration；tree retrieval 接近 oracle，DAG 优于 flat invocation | Manager 应实现 catalog、能力树和依赖 DAG，而不是把所有能力写进一个超级 Skill |
| [How Well Do Agentic Skills Work in the Wild?](https://arxiv.org/abs/2604.04323) | B，2026 预印本 | 从 34K 真实 Skill 检索时，收益随现实噪声增加接近 no-skill baseline；query-specific refinement 可恢复效果，Terminal-Bench 2.0 报告 57.7%→65.5% | Skill 数量扩大后必须做检索、精炼、负例与消费模型评测 |

这些 2026 论文均较新，需在复现和后续审稿中继续校正；它们适合决定评测方向，不适合成为市场承诺。

## 12. 自动生成、反思与遗传式优化证据

| 研究 | 等级 | 方法与结果 | 对本产品的边界 |
|---|---|---|---|
| [APE, ICLR 2023](https://openreview.net/forum?id=92gvk82DE-) | A | 从 I/O 例生成多个 instruction，在开发集选优；21/24 instruction-induction 任务达到或超过人工 prompt | 生成多个不可变候选并用版本化 eval 排序，不按文字观感选择 |
| [ProTeGi, EMNLP 2023](https://aclanthology.org/2023.emnlp-main.494/) | A | textual gradient + beam/bandit selection；在小型 NLP/jailbreak 任务上相对初稿最高提升 31% | 保存“批评→补丁”provenance；标注数据与多次调用成本，独立验证补丁 |
| [EvoPrompt, ICLR 2024](https://proceedings.iclr.cc/paper_files/paper/2024/hash/9156b0f6dfa9bbd18c79cc459ef5d61c-Abstract-Conference.html) | A | LLM mutation/crossover + population fitness；31 个数据集，BBH 最高提升 25% | 遗传模式只能离线、预算化、保留多样性并用 holdout 防止过拟合 |
| [Promptbreeder, ICML 2024](https://proceedings.mlr.press/v235/fernando24a.html) | A | 同时演化任务 prompt 与 mutation prompt，在算术/常识基准超过 CoT 与 Plan-and-Solve | mutation operator 本身也要版本化评测；递归优化不能操作 live store |
| [MIPRO, EMNLP 2024](https://aclanthology.org/2024.emnlp-main.525/) | A | 对多阶段 LM program 的 instruction 和 demonstration 联合优化；5/7 program 优于 baseline，最高约 13 accuracy points | 可在确定性/人工模块边界建立后优化各模块；不证明自动拆文件有效 |
| [TextGrad, Nature 2025](https://www.nature.com/articles/s41586-025-08661-4) | A | 用 LLM 文本反馈在复合系统中反向传播；论文报告 GPT-4o GPQA 51%→55%，LeetCode-Hard 相对提升 20% | textual feedback 可生成候选，但噪声和 judge 偏差要求独立 evaluator |
| [Reflexion, NeurIPS 2023](https://papers.neurips.cc/paper_files/paper/2023/hash/1b44b878bb782e6954cd888628510e90-Abstract-Conference.html) | A | 将任务反馈写入 episodic verbal memory；HumanEval 报告 91% pass@1，相比 GPT-4 baseline 80% | reflection 是候选证据，不是经验证的 durable Skill 改写 |
| [ExpeL, AAAI 2024](https://ojs.aaai.org/index.php/AAAI/article/view/29936) | A | 从成功/失败轨迹提炼可复用经验并检索相似轨迹；HotpotQA/ALFWorld/WebShop 均报告提升 | trace insight 必须保存任务簇、支持/反例与版本，避免冲突和错误迁移 |
| [GEPA](https://arxiv.org/abs/2507.19457) | B，预印本 | 对执行 trace 做反思、演化 prompt modules、Pareto 选择；6 任务平均较 GRPO +6%、最高 +20%，rollout 最多少 35 倍 | 适合作为已有 evaluator 后的可选离线 optimizer；不能用单一 scalar 自动晋级 |
| [Voyager](https://arxiv.org/abs/2305.16291) | B，预印本/NeurIPS 2023 展示 | 在 Minecraft 中迭代生成、验证、检索和组合代码 Skill，报告探索和进度显著提升 | 仅支持隔离环境中的“生成→验证→入库”思路，不证明生产配置可自动自改 |

没有高质量研究证明生产工具应持续、实时改写 Codex/Claude 的 live `SKILL.md`、`AGENTS.md`、权限或 Hook。研究共同支持的稳妥闭环是：

```text
脱敏历史 → 不可变候选 → 静态检查 → 正向/负向触发评测
→ task/model-specific utility → holdout/canary → 人工 Diff 审批
→ 原子晋级 → 监控和回退
```

## 13. Hook 与历史采集的官方边界

- [Codex Hooks](https://learn.chatgpt.com/docs/hooks) 将 Hook 定义为确定性生命周期脚本；多个 command Hook 可能并发，当前不支持真正 async，transcript 格式不是稳定接口，`SessionEnd` 预算短，Hook context 还会占用模型上下文。
- [Codex app-server threads](https://learn.chatgpt.com/docs/app-server#threads) 提供文档化的 thread/turn 读取能力，更适合用户授权后的历史导入；实验接口不能作为唯一依赖。
- [Claude Hooks reference](https://code.claude.com/docs/en/hooks) 说明 transcript 异步写入可能晚于 Hook、async Hook 每次触发是独立进程且不能阻断已发生动作、command Hook 具有当前系统用户权限。

因此 Hook 只发送极小、脱敏、幂等、可丢失的事件 envelope。完整历史由显式 importer 获取，改进由离线 worker 完成。Hook 中不运行 LLM、完整解析、eval、文件修改、Git、包安装、项目脚本、网络上传或批准。
