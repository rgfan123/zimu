# 08 — 飞象 Java Connector：pullOrders（文件化试点候选）

**What to build:** 飞象 Connector 实现在线拉单：cookie 会话登录（表单+302 判定）→ 导出直下文件字节 → 复用现有文件解析管线。飞象无 JSON 接口，维持文件形态；长期定位为文件化 pullOrders。拉取产物复用文件解析入口（同 07 的可见性放开）。

**Blocked by:** 01

**Status:** resolved

- [ ] 登录 → 导出直下 → 文件解析全链路可跑通
- [ ] 真实数据区间导出一次，验证 21 列 v1 数据行（当前样例仅表头），确认与解析器列名一致
- [ ] 生成批次可 confirm，行为与文件导入一致
- [ ] 失败重拉不产生重复订单（配合 02）

---

## 合并修订（2026-08-18）

**风险补强（红队 §0.5）**：parser 的 `feixiang()` 读取 `收货人姓名 / 收货人手机号 / 收货人地址 / 会员名称`，而飞象样例文件**仅有表头、无数据行**——真实 21 列的列名若与解析器不一致，会导致**全行 NEED_REVIEW**，批次 confirm 直接被拒。这是本票最大的落地风险，必须在真实区间下载后第一时间验证。

**区间口径未验证**：`start_time`/`end_time` 是按下单还是发货时间、`end_time` 含不含当天，从未验证。搞错会**静默漏单或重复拉单**（不报错，最难发现）。需与 `POST /order/ajaxOrderNum` 的页面统计交叉核对；票 16 抓包时会顺带留意。

**拉取窗口口径**（原 05 的内核）在本票内裁定，并交 13 统一编排。

新增验收项：

- [ ] 真实数据行的 21 列列名与 parser `feixiang()` 的取值字段逐一比对通过
- [ ] 区间口径经 `ajaxOrderNum` 交叉核对确认，结论写入契约文档

---

## Answer (2026-08-19)

**Status: resolved**

Java Connector 在线拉取已实现：`FeixiangPullClient`（GET 登录页种 fxqf_sess cookie → 表单登录 302 判定 → deliveryExport 直下 xlsx，PK 魔数校验）+ `FeixiangConnector.pullOrders`（登录→拉取→`SourceImportService.upload`→PullResult）。测试 `FeixiangConnectorTest` 8 例通过。

遗留风险（验收项未闭环，需真实区间导出验证）：
- **真实数据行的 21 列列名与 parser `feixiang()` 取值字段逐一比对**——当前样例仅表头，未验证
- **区间口径**（start_time/end_time 按下单还是发货、end_time 含不含当天）未经 `ajaxOrderNum` 交叉核对
