# 07 — 写接口收口（默认锁死）

Type: development-test
Status: resolved
Blocked by: None — can start immediately

**What to build:** 京东 SDK 全部写类接口（创建、取消、修改、关闭、设置、绑定类，约 19 个：客户/商品/店铺/供应商建档、加工单/调整单/销毁单/采购单/退货/退供/序列号创建、作业指令修改、店铺库存固定值设置、箱码序列号绑定等）接入受审计的 seam，但 HTTP 层默认拒绝一切写操作；仅在显式启用写模式且调用方明确授权后放行。真实环境调用仍受京东开放平台接口权限这一外部 gate 约束。

- [x] 全部写接口在 seam 层实现并接入统一审计（操作人、请求摘要、结果、延迟）。
- [x] 默认模式下，管理端对任何写操作的调用被拒绝并返回明确的「写模式未启用」错误，Mock 环境同样拒绝。
- [x] 显式启用写模式后，Mock 环境可完成全链路调用并返回稳定假数据；真实环境在无显式授权时仍拒绝。
- [x] 测试覆盖「默认拒绝」与「启用后可调」两条路径，且断言默认拒绝时不产生任何外部调用。
- [x] 不暴露前端一键写入口；写操作仅允许由受审计的内部履约用例触发。
- [x] 文档记录每个写接口的启用条件与京东开放平台接口权限清单核对方式。

## Answer

写接口收口已完成：20 个写端点接入受审计 seam `JdWriteOpsService`（REAL/Mock 双实现 + 审计含脱敏），HTTP 层默认 403 + `WRITE_MODE_DISABLED`（Mock 同样拒绝，被拦截尝试也落审计），显式 `app.jd.write-mode: ON` 才放行；测试覆盖默认拒绝（断言零外部调用）与启用后 Mock 全链路；前端无任何写入口。本期补齐文档：`docs/api-contract.md` §6.3（20 端点清单 + 启用条件 + 开放平台权限核对三步流程）与 `application.yml` 的 `write-mode: ${JD_LOP_WRITE_MODE:OFF}` 配置位。验证：JdWriteOps 域 8 个测试全过。另修复了 `JdWriteOpsClientRequestMappingTest` 的 import 冲突编译错误。
