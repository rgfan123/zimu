Parent spec: #120（ADR 0008）

## What to build（纯前端，归前端会话）

- [ ] 采购工作台建议区头部「管理盯盘品」上下文入口（按 navigation-admission 规则注册为隐藏路由，不占一级菜单位）
- [ ] 清单编辑界面：品名增删、启用/停用；改动即存库（#121 的 CRUD 端点），下一采集周期生效
- [ ] 密度优先（ADR 0005）：清单就是一张可编辑表，无解释文案
- [ ] routeHarness 测试：增删动作、URL、空清单态

## Blocked by

- #121（盯盘品 API）
