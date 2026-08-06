# Agent Config Workbench repository guidance

## Project status

- This project is a lab prototype whose active delivery path is Codex-first: `Inspect -> Draft -> Diff/Export -> Simple Apply/Rollback`. Codex `inspect`, Skill S0-S2, an existing-Skill controlled CLI, and a same-origin Vue/loopback single-page workflow now exist. The UI can explicitly create a first project Skill or select and replace an existing one, with real Diff and guarded rollback. Gate 6 remains partial and Gate 7 has a usable core flow; reading/backfilling existing Skill content, restart recovery, ordinary-user workspace picking, packaging, power-loss durability, OS-level CAS/TOCTOU, and Windows reparse coverage remain explicit blockers.
- Read `docs/PROJECT_GUIDE.md` before changing product behavior or architecture.
- Do not scaffold frameworks or add dependencies unless the current task explicitly asks for them.

## Product goal

Build a local-first Skills lifecycle assistant that creates, organizes, routes, evaluates, improves, governs, converts, and safely rolls back skills and adjacent agent configuration across Codex, Claude Code, Cursor, GitHub Copilot, Windsurf / Devin Desktop, and other supported coding agents. Keep the existing configuration workbench as the deterministic governance kernel.

## Planned stack

- UI: Vue 3 and TypeScript.
- Safety-critical local core: Java 21.
- Python: optional, isolated, and limited to later offline evaluation or experimental analysis.
- Persistence: local metadata and an append-only transaction journal; raw secrets must never enter logs.

## Architecture boundaries

- Keep the vendor-neutral domain model separate from every host adapter.
- Add hosts through the shared Host Adapter contract and registry; do not grow pairwise `if source/target` converters.
- Support native management and N-to-N host conversion as separate product modes.
- Keep Quick Convert and batch conversion on the same adapters, recipes, validators, ChangeSet, and transaction path.
- Route every conversion through source adapter -> neutral IR -> target capability matcher -> staged target; never convert by renaming or copying paths alone.
- Classify mappings as exact, compatible, assisted, or unsupported. Unsupported mappings must never be auto-applied.
- Version conversion recipes and preserve source provenance, unmapped fields, and explicit loss reports.
- Require target-schema validation and a semantic round-trip check before a converted ChangeSet can be approved.
- Keep deterministic parsing, policy, validation, filesystem transactions, Git integration, and optional AI assistance as separate modules.
- AI output is an untrusted proposal. Only deterministic code may approve paths or commit writes.
- Preserve `InstructionIr v1` as the content-free instruction contract. Model Skill packages, routes, evals, traces, revisions, and Agent contracts in separate versioned schemas.
- Persistence triage must happen before generation: distinguish one-shot Prompt, always-on Instruction, reusable Skill, isolated Agent contract, and deterministic tool/policy.
- Do not split a Skill by length alone. Require separable triggers, outcomes, reuse, permissions, or measured negative transfer; otherwise use supporting references and progressive disclosure.
- Implement Skill Manager as a catalog/router service with deterministic host/scope/capability filtering. Optional LLM reranking may only operate on the filtered candidate set.
- History-derived learning may only produce immutable proposals. Promotion requires frozen evals, holdout/regression evidence, a human-reviewed Diff, and the ordinary ChangeSet/transaction path.
- Treat hooks as optional bounded event sensors. Never run LLM calls, transcript analysis, eval campaigns, Skill mutation, Apply, Git operations, or network upload inside a hook.
- Do not convert Claude Markdown rules into Codex `.rules`; the two mechanisms have different meanings.
- Treat `docs/HOST_SUPPORT_MATRIX.md` as the source of truth for host maturity. Path recognition alone is not support.
- Before adding or promoting a host adapter, record official evidence, verified versions, capability gaps, parser fixtures, target validation, and permission-delta tests.

## Safety invariants

- Default to read-only discovery. A dry run must perform zero target-workspace writes.
- Never execute discovered skills, hooks, plugins, MCP commands, shell snippets, or repository scripts during scanning.
- Canonicalize both logical paths and real paths. Reject traversal, cycles, junctions, and symlink targets outside the approved root.
- Do not read secret files by default. Never send raw secrets, tokens, private keys, auth stores, or `.env` content to a model or log.
- Before apply, require an exact ChangeSet, semantic and text diff, validation result, explicit user approval, and a verified snapshot.
- Recheck source hashes immediately before commit. Stop on concurrent modification.
- Use same-directory staging, durable journal records, fsync where supported, and atomic replace. Treat multi-file changes as a recoverable transaction.
- Roll back from the transaction manifest, never with `git reset --hard` or an implicit stash.
- Preserve existing dirty Git state and all unrelated files.
- Automatic rollback may overwrite a file only when its current hash still equals the hash written by the failed transaction.
- Unknown fields, comments, encoding, line endings, and permissions must be preserved where the target format permits it.
- Conversion must never silently widen tools, permissions, network access, model invocation, or executable behavior.
- If the target artifact already exists, produce a three-way review; never overwrite it by default.
- Deletion is out of MVP scope. A future delete or move requires a verified backup and separate confirmation.
- Managed or enterprise policy may be reported as conflicting but must never be weakened or bypassed.

## Implementation conventions

- Prefer small, reversible changes and explicit interfaces.
- Add no dependency without documenting why the standard library is insufficient and what security surface the dependency adds.
- Use lossless/CST-aware edits for TOML, JSON-with-comments, YAML, and Markdown whenever whole-file rewriting would lose user intent.
- Use structured schemas between AI assistance and the deterministic core.
- Give every workflow run, validation, snapshot, and audit event a stable ID.
- Store content hashes and redacted summaries in logs; store original bytes only in the protected recovery vault.

## Verification expectations

- Run `scripts/test-core.sh` after changing Java scanner, registry, CLI, or safety behavior. It requires JDK 21 on `PATH`.
- Run `scripts/build-ui.sh` after changing `ui/`; it must keep the lockfile build, avoid remote runtime assets, and produce `ui/dist/index.html`. Use `scripts/run-local-web.sh <trusted-state-root>` for same-origin browser smoke tests. The fragment token must be removed immediately and remain memory-only; changing workspace or guided request must invalidate preview/approval, while rollback must remain bound to the workspace used by apply.
- Run `scripts/run-cli.sh <fixture-root>` only against a directory explicitly placed in scope; the CLI must remain metadata-only.
- Run `scripts/run-context.sh <codex|claude-code> <fixture-root> <cwd>` for the experimental project instruction chain. Codex optionally accepts `--codex-config <snapshot.toml>`; Claude optionally accepts `--target-file <project-relative-file>`. Keep `PARTIAL` findings and limitations explicit.
- Run `scripts/run-analyze.sh <codex|claude-code> <fixture-root> <cwd>` for Instruction IR and duplicate/conflict analysis; it accepts the same host-specific options as `run-context.sh`. Keep Analyze schema v1 separate from Context schema v2, and never add raw content, normalized directive text, or `realPath` to the default analysis JSON.
- Run `scripts/inspect-codex.sh <fixture-root> [cwd]` for the ordinary-user summary. It must stay Codex-only, human-readable, content-free, free of hashes/physical paths, partial-result aware, and zero-write; machine consumers continue to use Analyze schema v1.
- Run `scripts/run-skill-inventory.sh <fixture-root>` for Codex project Skill inventory schema v2 and reference profile `codex-skill-inline-reference-v1`. It may read only bounded UTF-8 `SKILL.md` metadata/body, may expose a target logical path only for `RESOLVED` content-free inline-reference edges, must redact `MISSING/UNKNOWN` targets, must never open or execute supporting-file content, must reject symlinked Skill paths and unsafe local destinations, and must keep `contentIncluded=false` and `writesPerformed=false`. Do not call this profile full CommonMark or recursively follow supporting references. A usable report exits 3 when it is `PARTIAL` or contains a blocking finding.
- Run `scripts/run-skill-blueprint-preview.sh <guided-request.intent>` for S1, or pipe the same request to `Main skill-blueprint-preview codex`. The Java core reads at most 32 KiB from stdin and never receives a workspace path; the convenience script accepts one explicitly selected regular non-symbolic-link file. Classification must use explicit guided signals, never free-text keywords. A complete Blueprint requires confirmed `SKILL + PROJECT`, all required fields, boundary examples, and at least three unique positive and negative trigger cases. The command may include bounded user-provided Blueprint fields but must keep workspace content and the raw request excluded, use no LLM/network/process, perform no target writes, and never become apply-eligible.
- Run `scripts/run-conformance.sh` after changing Codex/Claude project semantics or analyzer classification. Its stdout is a machine-readable schema v1 report; suite logs go to stderr. Preserve the existing semantic profile IDs unless behavior intentionally changes and receives a new profile.
- Run `scripts/run-convert-preview.sh <codex|claude-code> <claude-code|codex> <fixture-root> <cwd>` for ConversionPlan schema v2. It must remain content-free in JSON and write-disabled; `PARTIAL` IR must not produce a normal plan, unsafe/stale target paths exit 3, validation evidence must bind the candidate hash, and `NOT_RUN` must never be treated as success.
- Run `scripts/run-skill-draft-preview.sh <guided-request.intent> [--export <content|diff|prompt>]` for S2. Default JSON must remain candidate-content-free; exports are stdout-only, never probe a target, never start an LLM/network/process, and never write. Do not present the synthetic `/dev/null` Diff as a real workspace Diff.
- Keep fixture S3 isolated in `FixtureSkillTransactionTests` and `FixturePendingScanTests`. The narrower ordinary-workspace path is single-Skill-only and must retain explicit create/update intent, exact Diff approval, fixed external state root, stale/identity guards, truthful target/state write receipts, and guarded rollback. A create rollback may remove only the unchanged candidate and directories created by that transaction that remain empty. The loopback API must call Java services directly, bind only `127.0.0.1`, require its process-local bearer token plus exact Origin/Host, expose no CORS, and never log paths, guided requests, Diff, tokens, or file content.
- Treat only equal effective-payload hash and length as a deterministic exact duplicate. Normalized directive duplicates and direct polarity conflicts are heuristic candidates and must remain labeled as such.
- Git metadata must remain opt-in through `--git-metadata`; never execute Git or claim dirty/clean state from the minimal metadata probe.
- Add fixture-based tests before touching real configuration directories.
- Test empty, non-Git, dirty Git, monorepo, nested scope, invalid syntax, duplicate names, import cycles, symlink escape, concurrent edit, interrupted write, and rollback cases.
- Require byte-identical rollback for supported regular files.
- Require idempotence: applying the same normalized plan twice produces no second change.
- Test generated skill descriptions with both should-trigger and should-not-trigger prompts.
- Test every enabled source/target recipe, target validation, round-trip semantic equivalence, explicit loss reports, and zero automatic permission widening.
- Test UI empty, loading, partial-success, error, recovery, narrow-window, and overflow states.

## Documentation policy

- Update `docs/TECH_EVOLUTION.md` when architecture, dependencies, persistence, security boundaries, or adapters change.
- Update `docs/DECISION_LOG.md` for material tradeoffs or rejected alternatives.
- Update `docs/PROJECT_GUIDE.md` when user flow, scope, commands, modules, metrics, or release gates change.
- Never put credentials, customer content, or raw repository contents in documentation.
