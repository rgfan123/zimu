# 16 — 飞象发货回填接口抓包（HITL，11 的 blocker）

> 本票是合并新增。用户裁定回填 **3/3 全在范围内**（原 spec 把回传补抓列为范围外，已推翻）。三平台里彩食鲜回填契约 2026-08-18 已抓到（`importDeliverExcl`，见 caishixian 契约 §6）、聚福宝 `multi-send` 早有——**飞象是唯一真缺口**。

**这是 HITL 票**：需真人登录 `ziyousupplier.wowcarp.com` 走一次真实发货回传并抓包。

**需要用户做的事：**
1. DevTools 开 Preserve log，登录飞象供应商后台
2. 走一次**真实的批量发货导入**：下载/填好发货表 → 上传 → 直到平台提示成功
3. 导出 HAR（含请求体）到 `data-local/`，告知路径

**要确认的事实：**

| # | 事实 | 为什么要 |
|---|---|---|
| 1 | `deliveryImport`（或实际端点）URL、method、Content-Type | 投递通道目标 |
| 2 | 是否带 CSRF / 隐藏表单字段 / `__token__`（ThinkPHP 惯例） | 漏了会 403 |
| 3 | 上传文件**真实格式**与表头列结构 | **最大的坑**：拉取侧 `deliveryExport` 是误命名 `.csv` 的 XLSX（v1 21 列），回填侧未必同格式 |
| 4 | 回填表列与现有 `SourceReturnExport` 飞象模板是否一致 | 决定生成器能否零改动复用 |
| 5 | 成功/失败响应：JSON 还是 HTML？成功判定依据 | ThinkPHP 服务端渲染，可能返回 HTML；错误映射依据 |
| 6 | 是否有异步导入任务与结果查询页 | 决定推送后要不要轮询 |
| 7 | 部分行失败时如何反馈（整批回滚 / 逐行报错） | 关系到 11 的批次语义与「部分成功」表达 |
| 8 | 物流公司取值：文本还是 id？有无字典接口？ | Carrier 映射 |

**顺带（拉取侧两个遗留未验证项，HAR 里若有线索一并答）：** `deliveryExport` 的 `start_time`/`end_time` 是按下单还是发货时间？`end_time` 含不含当天？——**搞错会静默漏单或重复拉单**，是 08 最大的风险点。

**Blocked by:** None — can start immediately

**Status:** needs-user

- [ ] 回填端点契约写入 `feixiang-supplier-export-api.md` 新增「发货回传」章节
- [ ] 上传文件格式与列结构确认，与 `SourceReturnExport` 飞象模板比对结论明确
- [ ] 成功/失败判定方式确认
- [ ] 拉取侧区间口径结论一并记录（或明确未能确认）
- [ ] 总览文档 §4 飞象行从「未抓包」更新为已确认
