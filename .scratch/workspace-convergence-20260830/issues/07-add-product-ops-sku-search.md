# 07: 在最终工具面加入商品运营 SKU 查询

**What to build:** 商品运营与 Agent 可以通过一个只读接口按条码、SKU 编码、品类、标签和启用状态组合检索权威 SKU，参数错误如实返回，不需要多套 ad-hoc 查询。

**Blocked by:** 05: 统一 Agent 与公共 MCP 双工具面。

**Status:** completed

- [x] 每个过滤条件及组合条件均通过同一查询接口工作。
- [x] 查询保持只读并返回最小业务投影。
- [x] 空条件保留既有全量口径；非法布尔和不支持参数得到稳定调用者可见错误。
- [x] 数据库查询、工具契约与文档保持一致。
- [x] 聚焦测试通过，并形成独立提交。

**Verification:** PostgreSQL 多条件/分页查询、query 条码片段回归、REST SKU 搜索、
Agent 与公共协议注册、stdio/HTTP 传输验收全部通过。原生查询 value/count 两侧均显式
保留条码模糊匹配与 null 参数类型转换。
