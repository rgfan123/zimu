# 子牧隔离合并与工作区防重复开发规格

## Problem Statement

子牧仓库长期由 Codex、Claude 和人工在大量 linked worktree、detached worktree 与独立 clone 中并行推进。当前同一 Git common-dir 已注册数十个工作区，并同时存在两条没有共同祖先的 `main` / `master` 历史、目录名与实际分支名不一致、绝大多数分支没有 upstream、完整实现停留在未提交工作树、旧实现被后续架构替代但仍可见、以及活跃任务持续提交或合并等状态。

从维护者视角看，最直接的问题不是“分支多”，而是无法稳定回答四个问题：某项工作是否已经有人在做；完成结果究竟存在于哪个 commit、分支或未提交工作树；某个旧工作树是独有成果、已被吸收的重复副本还是状态未知；以及下一位 Agent 应从哪条权威开发历史开始。结果是同一功能被再次开发，完成工作没有提交或交接，另一侧只能重新实现，同时任何直接清理都可能破坏活跃工作或唯一副本。

本轮还存在一个即时交付目标：在不干扰现有开发的前提下，从已完成阶段性合并的确定提交创建一条全新的隔离收敛线，把已确认稳定且仍有价值的提交和未提交实现逐项迁入，形成供维护者后续迁移仍在开发中增量的可靠基线。

## Solution

建立一条只在新 worktree 中写入的隔离收敛分支，以 `af0b58c0` 作为冻结基线。所有现有工作区只作为只读证据源；不在来源工作区执行暂存、提交、切换、重置、清理或合并。

收敛分支按小而可验证的提交顺序完成两类工作：

1. 逐项吸收已经提交但尚未进入冻结基线的稳定提交，并在当前架构上解决语义冲突，而不是批量 cherry-pick。
2. 对审计中确认完整度高、仍属独有且尚未提交的实现进行人工移植；每项实现单独测试、单独提交，并记录来源 HEAD、来源脏状态指纹、适配决策和验证证据。

同时把仓库已有的基线自检入口加深为持续开工门禁：它应拒绝无共同祖先的镜像历史，识别已有同票工作、已集成结果、同路径冲突和状态未知工作区，并失败关闭。最终提供一个仓库级合并验收入口，统一验证基线、来源证据、迁移编号、工作树清洁度、聚焦测试和完整 CI 门禁。

本轮不迁入仍在活跃开发的 SKU 主数据修复及其他持续变化的工作区。维护者将在本轮合并完成后，把这些新增增量迁入收敛分支。

## User Stories

1. As a repository maintainer, I want all merge work to happen in one new isolated worktree, so that existing developers are not interrupted.
2. As a repository maintainer, I want the convergence branch to start from an exact immutable commit, so that later source-branch movement cannot silently change the baseline.
3. As an active developer, I want my current branch, index, untracked files, merge state and processes left untouched, so that the consolidation cannot destroy or rewrite my work.
4. As a repository maintainer, I want each imported contribution to retain its source branch, HEAD and evidence fingerprint, so that I can audit where every result came from.
5. As a repository maintainer, I want committed changes that remain outside the convergence branch evaluated one by one, so that semantic overlap is resolved intentionally rather than hidden by a bulk cherry-pick.
6. As a repository maintainer, I want the remaining workbench blocker change evaluated independently, so that its UI behavior can be retained without overwriting newer contracts.
7. As a repository maintainer, I want the remaining product-code governance change evaluated independently, so that duplicate product identity rules converge to one implementation.
8. As a repository maintainer, I want the remaining stdio write-gate change evaluated independently, so that tool exposure stays aligned with current Agent and MCP authorization boundaries.
9. As an order-operations developer, I want file import, API pulls and manual bundle resolution to share one source-bundle lookup seam, so that the same source item cannot resolve differently by entry path.
10. As a database maintainer, I want rescued schema changes assigned a new migration number above the current history, so that applied historical migrations remain immutable.
11. As a product-operations user, I want SKU search to support barcode, SKU code, category, tag and active-state filters through one read interface, so that I can find authoritative SKU records without separate ad-hoc queries.
12. As an Agent user, I want read-only bundle discovery tools available through the governed tool registry, so that the Agent can inspect static bundle composition without gaining write capability.
13. As a security maintainer, I want Agent tools and public MCP protocol tools represented by separate governed surfaces, so that enabling an Agent capability cannot accidentally expose it publicly.
14. As a security maintainer, I want public MCP exposure to remain read-only and fail closed, so that configuration mistakes cannot publish write tools.
15. As a developer, I want new bundle and SKU read tools adapted to the final split tool registry, so that later feature ports do not reintroduce the old single-index design.
16. As a developer, I want every rescued feature to be one reviewable commit, so that it can be reverted or migrated independently.
17. As a developer, I want tests written against existing public business interfaces, so that ports verify behavior rather than copied implementation structure.
18. As a repository maintainer, I want a single repository-level convergence verification command, so that a branch is not declared complete from a clean status or successful compile alone.
19. As a repository maintainer, I want the verification command to distinguish focused tests, full backend tests, frontend typecheck, frontend tests and frontend build, so that unrun gates are never reported as passed.
20. As a repository maintainer, I want migration history and generated schema snapshots checked together, so that a rescued migration cannot create duplicate numbers or drift.
21. As a developer starting new work, I want the baseline gate to reject the unrelated mirror history, so that I cannot spend time editing a branch that cannot merge into the development lineage.
22. As a developer starting new work, I want the gate to find another worktree with the same work item or intent, so that I resume existing work instead of starting a duplicate.
23. As a developer starting new work, I want the gate to report when the requested result is already integrated, so that I do not reimplement an existing feature.
24. As a developer starting new work, I want same-path but different-content changes reported as a collision rather than a duplicate, so that genuine design divergence receives human review.
25. As a developer starting new work, I want staged, unstaged and relevant untracked implementation evidence reported separately, so that completed but uncommitted code cannot disappear behind a generic dirty flag.
26. As a developer starting new work, I want merge/rebase state, changing HEAD/index, timeout and unreadable files to fail closed as protected or unknown, so that uncertainty is never treated as clean.
27. As a repository maintainer, I want generated artifacts and ignored dependency directories excluded from functional WIP evidence, so that build noise does not block legitimate decisions.
28. As a repository maintainer, I want remote status explicitly identified as current, local-ref-only or without tracking evidence, so that stale remote refs are not described as confirmed push state.
29. As a repository maintainer, I want active Codex/Claude tasks and pinned worktrees protected, so that age or clean status cannot make them automatic cleanup candidates.
30. As a repository maintainer, I want orphan and dataless directories recorded for rescue but not automatically deleted, so that missing Git metadata does not erase unique files.
31. As a repository maintainer, I want the active SKU master-data work excluded from this merge, so that I can migrate its final result after the convergence branch is complete.
32. As a repository maintainer, I want superseded Feixiang, Jufubao and mixed UI prototypes excluded from bulk import, so that old architecture and obsolete migrations do not return.
33. As a repository maintainer, I want a final handoff showing included, adapted, excluded and pending sources, so that the next migration starts from evidence rather than memory.
34. As a repository maintainer, I want no automatic push, branch deletion or worktree removal during this delivery, so that I retain control of remote publication and cleanup.
35. As a repository maintainer, I want the isolated branch committed locally after review, so that the completed convergence survives independently of every source worktree.

## Implementation Decisions

- The only writable checkout is the new convergence worktree on branch `codex/workspace-convergence-20260830`.
- The frozen baseline is commit `af0b58c0`; moving `main`, `integration`, `master` and all other worktrees remain read-only sources.
- Git facts, work-item facts and convergence evidence remain distinct. A commit proves code reachability; it does not prove tests, push, merge, deployment or external acceptance.
- The import order is dependency-driven:
  1. evaluate and adapt the three committed changes still outside the frozen baseline;
  2. establish the final Agent/public MCP tool-surface split;
  3. add bundle read tools to the final governed registry;
  4. add product-operations SKU search to the final governed registry;
  5. add the unified source-bundle lookup seam and a new append-only migration;
  6. add the ongoing baseline/workspace gate and final convergence verifier;
  7. run final integration verification and write the migration handoff.
- Cherry-pick is allowed only when a commit applies cleanly and its behavior still matches the current architecture. Any conflict or semantic duplicate is resolved as a manual port with the original commit used only as evidence.
- Uncommitted sources are never committed in place. Their diffs and untracked source/test files are read, fingerprinted and reimplemented in the convergence worktree.
- Each rescued capability receives its own commit after focused tests pass. Documentation/spec/ticket scaffolding is a separate commit.
- Existing migration files are immutable. The source-bundle migration is renumbered above the convergence baseline's highest migration and all generated schema/migration compatibility artifacts are updated through repository conventions.
- The existing baseline-check interface is retained as the single pre-work entry and deepened rather than replaced by an unrelated script.
- The pre-work verdicts are limited to start allowed, resume existing, already integrated, collision and protected/unknown. Ambiguous evidence fails closed and never triggers Git mutation.
- The repository-level convergence verifier is read-only. It validates the development lineage, required source evidence, absence of unresolved Git operations, migration consistency, worktree cleanliness and the declared test gates.
- Remote refresh, upstream changes, push and PR creation are explicit later operations. This local delivery does not depend on GitHub availability.
- The active SKU master-data branch, active source worktrees, orphan directories, independent clone cleanup and remote branch cleanup are excluded from the merge and remain available for the maintainer's later migration.
- No credentials, environment values, raw diffs containing business data or untracked file contents are written into convergence reports. Evidence uses paths, hashes, Git identities and stable verdicts.

## Testing Decisions

- The pre-agreed total seam is the repository-level convergence verification command. A successful run means all declared focused and full gates completed; it must not infer success from missing tools or skipped commands.
- The baseline/workspace gate is tested through its command-line interface against temporary real Git repositories and linked worktrees. Tests assert exit codes and stable machine-readable verdicts, not internal shell/Python functions.
- Gate fixtures cover unrelated histories, correct development lineage, duplicate intent, already integrated commits, reverse-applicable old patches, same-path collisions, staged/unstaged/untracked work, ignored artifacts, detached worktrees, merge state, changing HEAD, timeout and missing upstream.
- Gate tests verify that every repository ref, index and working-tree file is unchanged after scanning; only an explicitly configured coordination record may change.
- The Agent/public MCP split is tested through tool listing and tool invocation interfaces. Public protocol listing/calls must reject write tools even if configuration is wrong; Agent tools continue to obey their own allow-write gate.
- Bundle reads are tested through the tool registry's public read interface and database-backed application behavior, including missing bundle, exact bundle, candidate lookup and module-disabled cases.
- SKU search is tested through its public read interface with independently known records covering each filter and combined filters; invalid parameters fail with stable caller-visible errors.
- Source-bundle resolution is tested at the highest shared application seam through file import, API-imported orders and manual resolution. The same source reference must produce the same bundle decision and preserve existing name/ID compatibility.
- Migration tests verify uniqueness, append-only history, schema snapshot equivalence and upgrade compatibility. No applied migration is edited to make tests pass.
- Focused tests run after each vertical slice. Backend compile/test selection and frontend typecheck/test selection are reported separately.
- Final verification runs the repository's complete backend test suite, frontend typecheck, frontend unit/component tests and frontend production build. Docker/Testcontainers failures are reported as unverified integration, not converted into a pass.
- Code review uses the frozen baseline as the fixed point and reports Standards and Spec findings separately.

## Out of Scope

- Modifying, committing, stashing, resetting, checking out, cleaning or deleting any existing source worktree.
- Migrating work that continues to change after the frozen baseline, including the active SKU master-data task; the maintainer will migrate that work after this delivery.
- Bulk-merging old Feixiang V33–V35 work, the old Jufubao client/UI branch, mixed IA prototypes or other work classified as superseded/partial.
- Deleting branches, refs, worktrees, orphan directories, independent clones, generated artifacts or remote branches.
- Fetching by default, setting upstreams, pushing commits, creating a PR, changing the GitHub default branch or publishing issues while authentication is unavailable.
- Editing historical migrations already present in the baseline.
- Running real supplier-platform writes, production deployment, database mutation outside tests or external acceptance.
- Treating clean status, commit reachability, patch similarity, age or TTL as automatic deletion approval.
- Building a GUI for workspace coordination.

## Further Notes

- GitHub authentication was unavailable, so this specification and its tickets are intentionally published to the repository's local historical tracker under `.scratch`. They can be migrated to GitHub later without changing their semantics.
- This work builds on the earlier “master 收敛盘点 — 2026-08-25” and the legacy-lineage port assessment. Those efforts proved the value of patch/reachability checks but did not create a persistent pre-work gate.
- At the audit snapshot, `origin/main` and `origin/master` were unrelated histories. The local `origin/HEAD` setting is therefore not a safe source of truth for new work.
- Completion of this spec produces a local convergence branch and migration handoff. It does not authorize cleanup or declare remote publication, deployment or production acceptance.
