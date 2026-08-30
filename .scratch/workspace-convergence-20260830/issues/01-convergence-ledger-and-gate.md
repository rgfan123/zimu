# 01: 建立隔离收敛来源账本与只读门禁

**What to build:** 维护者可以在隔离收敛分支上运行一个统一门禁，确认自己位于开发历史、当前没有未解决 Git 操作，并核对每个待迁入来源的分支、HEAD、脏状态指纹和处置结论；门禁只读扫描来源工作区，任何状态变化、超时或不可读情况都失败关闭。

**Blocked by:** None (can start immediately).

**Status:** completed

- [x] 门禁能拒绝与开发历史无共同祖先的镜像线，并接受冻结基线所在的开发线。
- [x] 来源账本记录本轮包含、适配、排除和待维护者迁移的来源身份，不保存凭据或原始 diff。
- [x] staged、unstaged、相关 untracked、进行中 Git 操作和状态未知分别报告。
- [x] 扫描前后来源 HEAD 或状态身份变化时返回受保护/未知，不输出伪稳定结论。
- [x] 临时真实 Git 仓库测试证明门禁不会修改 refs、index 或工作树内容。
- [x] 既有入口支持 `--pre-work --target WORKTREE --baseline COMMIT --work-item STABLE_ID --intent normalized-intent [--candidate REF_OR_PATH] [--registry FILE] --json`；仅 `START_ALLOWED` 返回 0，防重复裁决返回 1，非法输入/记录返回 2。
- [x] 开工裁决稳定限定为 `START_ALLOWED`、`RESUME_EXISTING`、`ALREADY_INTEGRATED`、`COLLISION`、`PROTECTED_UNKNOWN`；同票证据只来自显式只读 registry 或工作区根目录 `.workspace-work-item.json` marker，不从目录名/分支名猜测。
- [x] 输出包含目标 path/branch/HEAD/baseline、匹配工作区 path/branch/HEAD/work-item/intent、staged/unstaged/untracked 路径与 SHA-256；未在线查询远端时只报告 `REMOTE_UNVERIFIED` / `LOCAL_REF_ONLY` / `NO_TRACKING`，并单列本地 tracking relation。
- [x] candidate 只接受整份反向应用或当前树合并等“当前态”证明；不以历史 patch-id 断言已吸收，revert 和只吸收多 patch 中一部分均不会误报 `ALREADY_INTEGRATED`。
- [x] ledger、registry、marker、candidate 均以 `open` + `O_NOFOLLOW` + `O_NONBLOCK` + `fstat` + 限长读取；拒绝软链、中间软链、special file、超限及扫描中换档。
- [x] 目标有未认领 WIP 或被另一 work-item/intent 占用时不允许开工；同票目标恢复既有工作；clean worktree 也比较 `baseline..HEAD` 已提交路径的最终内容。
- [x] candidate 扫描后全局重查目标、worktree registry、coordination、匹配工作区和 candidate；ledger 扫描后全局重查目标、registry、全部强约束来源，且按 `expected_path` 精确定位。
- [x] deferred source 以 advisory 方式重扫并输出 `current` / `changed` / `reasons`；活跃来源移动不阻断 `READY`，但不会从报告中静默消失。
- [x] 临时真实 Git 仓库 CLI 回归覆盖五裁决、无关历史、detached/merge 状态、ignored artifact、同计数换内容、revert、多 patch、已提交冲突、目标脏态/异票占用、安全文件、全局扫描竞态、deferred 漂移、超时和本地 tracking ref 关系。
