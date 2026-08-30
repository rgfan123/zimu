# 06 — 挂账清理（P3，改到哪补到哪）

**What to build:** 以下六项按「谁先动那片代码谁顺手收拢」执行，不单独排期；
每项收拢时遵守 ADR 0012 纪律（知识进 owning module、测试打接口、删旧碎片测试）。

**Blocked by:** 无
**Status:** 挂账

1. **FulfillmentExportWecom 状态机**：`file/FulfillmentExportWecomStore` 932 行
   30 公开方法只有同包 3 个调用方；`PENDING/SENDING/SENT/FAILED/UNKNOWN` 状态
   判定散在 Runner（5 个 switch）/Finalizer/Store/Service。状态机收进一个模块，
   Store 收窄为存取。
2. **MasterDataService 拆分**：1247 行 36 公开方法混 7 个聚合（客户/类目/商品/
   SKU/来源映射/履约方映射/履约方 + 2 个 Excel 导入）。按聚合拆，Excel 导入并入
   file 包既有解析。
3. **PlatformOrderRefreshService 归接缝**：531 行内 4 次自建渠道知识
   （DEFAULT_CHANNELS 裸串、CHANNEL_SCRIPTS 脚本名/凭据名/环境变量映射、
   switch(channel) 组 argv、自建 EnumMap 绕开 PlatformConnectorRegistry）。
   渠道知识全部回 `connector/sync/PlatformConnectorRegistry`；
   `ZhonghuiPmsBatchUploadService` 的传输异常/会话管理下沉 client。
4. **MCP 参数解析收拢**：`mcp/` 三个工具类 34 个 SimpleTool，identifier/
   optionalIdentifier/optionalString/page/pageSize 三份逐字节复制 →
   `McpArguments` 一处；audit+幂等 ceremony 模板化。
5. **/internal 镜像控制器**：`agent/AgentRunReadController` 与 Internal 版逐字节
   同构（diff 验证过），共 4 对 ≈300 行。合并为单控制器双路由或共享委托。
6. **POI 读写接缝**：WorkbookFactory/XSSFWorkbook/DataFormatter 裸用 6 文件
   3 包，各自重发明表头探测/样式复制/单元格取文本；收一个 spreadsheet 读写模块。
   `connector/jd/order/JdOrderController.java:330` 的控制器内联建 XLSX 一并迁出。
