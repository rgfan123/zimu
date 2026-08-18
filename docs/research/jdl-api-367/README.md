# 京东物流开放平台「仓配一体」API 文档快照

- 官方目录：<https://open.jdl.com/#/open-business-document/api-doc/367>
- 业务单元 ID：`367`
- 下载时间：2026-08-11 17:13（Asia/Shanghai）
- API 总数：62

## 目录完整性

| 分类 | API 数 |
|---|---:|
| 默认分类 | 6 |
| 基础数据 | 17 |
| 入库 | 8 |
| 库内 | 14 |
| 出库 | 14 |
| 取消 | 1 |
| 全程跟踪 | 2 |
| **合计** | **62** |

## 文件

- `catalog.json`：官方目录接口的完整响应。
- `manifest.json`：文档 ID、分类、API code、官方页面 URL、本地文件和 SHA-256。
- `html/`：62 份官方「下载本页」HTML。
- `json/`：62 份官方结构化 API 详情响应，用于机器检索和生成 Client。
- `access-guides/`：当前接口引用的销售出库单状态和销售平台枚举快照。

当前业务的最小接口选型与调用规则见 [当前业务所需京东 ISC API 清单](../jd-current-business-api-selection.md)。

## 当前项目相关的主要接口

| 文档 ID | API code | 用途 |
|---:|---|---|
| 1602 | `queryWarehouseInfo` | 仓库信息查询 |
| 1610 | `queryGoodsInfo` | 商品信息查询 |
| 1612 | `queryStock` | 库存查询 |
| 1596 | `addSoOrder` | 销售出库单创建 |
| 1632 | `querySoOrder` | 销售出库单查询 |
| 1552 | `cancelOrder` | 单据取消 |
| 1941 | `commonQueryOrderTrace` | 全程轨迹查询 |

## 验证

- 目录接口返回 7 个分类、62 个唯一文档 ID。
- 62 份 HTML 和 62 份 JSON 均已下载，无空文件。
- 每份 JSON 响应均为 `code=20000`。
- 62 份 HTML 均已在 `manifest.json` 记录 SHA-256，本地复算一致。

这是 2026-08-11 的本地快照；实施前若官方页面有更新，应重新拉取并比较 `manifest.json`。
