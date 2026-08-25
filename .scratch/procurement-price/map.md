# procurement-price — 采购比价：价格情报层 + 工单最小商业化

来源：2026-08-23 grilling（ADR 0007–0010，issue-103 worktree docs/adr/）+ 同事 Demo。
Spec：GitHub #120。Demo 源码：`data-local/procurement-demo/`（gitignored，含密钥勿入库）。

| 票 | GitHub | 归属 | Blocked by |
|---|---|---|---|
| 01 价格数据层与盯盘品 API | #121 | 后端 Agent | 无 |
| 02 爬虫移植进脚本通道 | #122 | 后端 Agent | #121 |
| 03 建议引擎与每日调度 | #118（重写） | 后端 Agent | #121、#122 |
| 04 工单商业化 + 回执凭证必传 | #123 | 后端 Agent | 无 |
| 05 盯盘品清单管理界面 | #124 | 前端会话 | #121 |
| 06 工单页参考价/成交价/凭证 UI | #125 | 前端会话 | #123 |
| （修正）采购工作台语义 | #110 评论 | 前端会话 | #121/#118 供数 |

企微推送：不在本期，依赖 #90（spec D8）。
