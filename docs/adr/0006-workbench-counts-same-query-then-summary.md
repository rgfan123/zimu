---
status: accepted
---

# Workbench counts: same-query counting now, contract-pinned summary endpoint later

发货台（及后续工作台）骨架里的计数分两阶段供数，接缝一次设计到位：

1. **Phase 现在**：前端用既有列表接口 `size=1` 只取 `total_elements` 拼真数
   （复核徽标已验证的同一模式）。数字与点进去的列表天然同口径——同一接口同一筛选。
   拼不出的段位（回填失败全局清单、拉取配额）就地一行短诚实态占位。
2. **Phase 之后**：后端提供 `GET /api/v1/workbench/shipping/summary` 聚合端点，
   **口径契约**：每个计数必须等于对应列表端点同筛选的 `total_elements`，由一条
   契约测试把两者锁死——消除聚合口径与列表口径漂移的风险（方案 3 的唯一缺点）。
3. **接缝**：前端全部计数收在单一 hook（`useShippingSummary`）内；切换数据源改
   一个文件，骨架、交互、跳转零改动。

## Considered options

- 只做 size=1 拼数（不演进）：首屏 7–9 个请求常驻，规模大了是浪费。
- 只等聚合端点：P1 阻塞在后端排期上，且无契约测试时口径漂移无人察觉。
- 前端造假数占位：违反 ADR 0001，不讨论。
