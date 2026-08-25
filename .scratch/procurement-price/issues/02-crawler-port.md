Parent spec: #120（D2）

## What to build（纯后端 + scripts/，勿碰 frontend/）

把同事 Demo 的两平台爬虫移植进子牧脚本通道：

- [ ] `scripts/` 下新增牧集、肉交所 Playwright 采集脚本（移植 Demo 的 crawlers/muji.py、roujiaosuo.py + filter.py 的防错价资产：单位换算防 1000 倍、无单位拒算标待核、厂号归一化 SIF→N厂、白名单厂）——Demo 源码在 Jerry 处（scratchpad 解包副本路径见 PLAN，或找 Jerry 要 zip）
- [ ] 凭据/会话材料落 `data-local/`（0600 不入库）；会话由 Demo 的交互式登录脚本人工生成，失效不自动重登（防风控）
- [ ] 后端按 `PlatformScriptRunner` 既有模式执行脚本，结果写报盘表（#121 的表）
- [ ] 采集失败落运营告警（含原因：会话失效/解析失败/网络）
- [ ] 解析器用录制的页面 HTML 夹具做离线测试；真实采集属人工验收不进 CI

## Blocked by

- #121（报盘表）
