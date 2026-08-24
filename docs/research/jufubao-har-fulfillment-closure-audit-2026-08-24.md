# 聚福宝 HAR 发货闭环审计

日期：2026-08-24
代码基线：`ae9168ef999fad02edc1872abd1ac748a855187f`（`codex/jufubao-convergence-20260824`）

> 本文的“代码差距”表是上述固定提交的实施前基线审计，不代表随后实现分支的当前状态。
> 实施后的收敛状态与验证证据单列在文末，避免把历史缺口误读成仍未实现。

## 结论

**是，这个 HAR 包里有聚福宝真实成功的发货 happy path 闭环。**

同一子单（下文脱敏代号 `T1`）在一方浏览器流量中完成了：

`NO_RECEIPT / receive` → `receive-order` → 短时仍为 `NO_RECEIPT` → 约 6 秒后变为 `NO_DELIVERY / send_good` → 地址变更检查 → 读取收货人与可发商品 → 读取承运商 → `sub-order-send` → 约 2.5 秒后离开 `no_delivery` 列表。

所以，旧的“抓包没有 receiver 来源、没有 `NO_RECEIPT` 接单契约、没有发货前地址检查契约”判断是错误的，应撤回。准确边界是：

- HAR 已证明一方真实浏览器会话中的成功发货 happy path；
- 上述固定代码基线尚未完整实现该闭环；
- HAR 没有覆盖异常分支，也没有捕获 `need_confirm=true` 的真实样例；
- HAR 不是“当前 Java 客户端已经用真实账号跑通”的证据。

## 一手来源与脱敏边界

本报告只使用两类一手来源：

1. 用户提供的 `彩食鲜+聚福宝闭环.har`；
2. 上述代码基线中的当前源码与测试。

HAR 取证指纹：

- SHA-256：`c55effab305f1384faf409d86ae2f32235384d4adfb85f752a3d938f6c0f7c5a`；
- 文件大小：`13,936,571` 字节；
- 共 `679` 个 `log.entries`，其中 `67` 个 URL 属于 `jufubao.cn` 域；
- 下文业务请求编号均为 HAR 的零基 `log.entries[index]`；
- 前端静态资源在 Reqable 列表中的显示序号为 `326`，对应 HAR 数组 `log.entries[294]`、`chunk-4cf4d9e0.js`。

报告没有复制真实订单号、姓名、电话号码、地址、运单号、Cookie、Token 或 `request_id` 值。跨请求关联均通过程序比较 `sub_order_id` 是否相等完成，统一以 `T1` 代称。

## HAR 中的完整成功链路

| HAR entry | 一方请求/响应证据 | 对 `T1` 的事实 | 判定 |
|---|---|---|---|
| `261` | `POST /order-supplier/v1/orders/query`，HTTP `200`，请求 `tab=no_delivery` | 响应项为 `order_status=NO_RECEIPT`，`button_list[].action=receive` | `no_delivery` 页并不只含 `NO_DELIVERY`；未接单订单也在其中。 |
| `290` | `POST /order-supplier/v1/order/receive-order`，HTTP `200` | 请求 JSON 只有 `sub_order_id`、`system`；响应 JSON 只有 `request_id` | `NO_RECEIPT` 的接单写契约已抓实。HTTP 200 只表示请求获受理，仍需查状态。 |
| `291` | `POST /order-supplier/v1/orders/query`，HTTP `200` | 接单约 `0.35s` 后仍为 `NO_RECEIPT`，action 仍为 `receive` | 平台存在最终一致性窗口，不能立即断言接单完成。 |
| `292` | `POST /order-supplier/v1/orders/query`，HTTP `200` | 接单约 `6.15s` 后变为 `NO_DELIVERY`，action 变为 `send_good` | `NO_RECEIPT → NO_DELIVERY` 的接单终态核验已捕获。 |
| `663` | `GET /order-supplier/v1/sub-orders/{id}/shipment-receipt-address-confirmation?system=supplier`，HTTP `200` | `need_confirm=false`，`message` 为空，`original_address=[]`、`latest_address=[]` | 发货前地址变更检查是明确门禁；本次走“无需确认”分支。 |
| `664` | `GET /order-supplier/v1/logistics/sub-order-info?sub_order_id=...&system=supplier`，HTTP `200` | `location`、`receipt_user_name`、`receipt_phone_number` 均非空；电话字段为 11 位；`product_list[].allow_send_num` 存在 | 单订单发货表单提供 receiver 与可发商品的权威读取源。 |
| `665` | `GET /order-public/v1/logistics-company/options?system=supplier`，HTTP `200` | 响应为 `items[].label/value` | 承运商应以实时字典映射，而不是猜测或硬编码某次捕获值。 |
| `667` | `POST /order-supplier/v1/logistics/sub-order-send`，HTTP `200` | 请求含 7 个顶层字段：`sub_order_id`、`product_list_json`、`is_need_logistics`、`company_id`、`logistics_number`、`remarks`、`system`；响应只有 `request_id`，没有 `code` | 单订单发货写契约和真实成功响应形态均已抓实。 |
| `668` | `POST /order-supplier/v1/orders/query`，HTTP `200` | 发货约 `2.55s` 后，`T1` 不再出现在完整的 `no_delivery` 第一页结果中；该响应无下一页 token | 写后离开待发货列表构成此 HAR 的终态成功证据。HAR 没有再查 `delivered` 页，因此不能声称捕获了显式 `DELIVERED` 响应。 |

`261`、`290`、`291`、`292`、`663`、`664`、`667`、`668` 的目标 ID 经程序比较一致，因此不是把不同订单的零散接口误拼成闭环。

## Receiver 与地址门禁不是推测

一方前端静态资源（Reqable 显示 entry `326`；HAR `log.entries[294]`）包含 `handleSendOrder` 逻辑：

- 先调用地址检查接口；
- `need_confirm=true` 时展示 `message` 并要求用户确认；
- `need_confirm=false` 时才打开发货弹窗；
- 发货弹窗把 `sub-order-info.receipt_user_name`、`receipt_phone_number`、`location` 分别显示为“收货人”“手机号”“收货地址”。

这与 HAR entry `663`、`664` 的真实响应相互印证：receiver 字段和地址变更门禁都有一方前端代码与一方 API 响应双重证据。当前 HAR 只走了 `need_confirm=false`，因此不能推导 `true` 分支后续确认请求的完整写契约。

## `product_list_json` 的真实写入形态

HAR entry `664` 的读取 DTO 与 entry `667` 的写入 DTO 不是整对象原样透传：

- `sub-order-info.product_list[0]` 含 `allow_send_num` 等读取字段；
- 浏览器提交的 `product_list_json[0]` 含 `send_num`，但不含 `allow_send_num`；
- 捕获中 `send_num` 是字符串，按数值与对应的 `allow_send_num` 相等；
- 浏览器还提交了一个随机 `fd-*` 临时字段，该字段不应成为服务端持久契约。

因此，服务端应建立显式写 DTO/allowlist，并生成 `send_num = allow_send_num`；不应把 `sub-order-info.product_list` 整体当作提交对象。

## HAR 与实施前代码基线的差距

| 能力 | HAR 一方事实 | `ae9168e` 基线事实 | 当时缺口 |
|---|---|---|---|
| 待处理订单读取 | `no_delivery` 同时可返回 `NO_RECEIPT`、`NO_DELIVERY`（entry `261/292`） | `JufubaoHttpPullClient.java:36-68` 只分页读取 `tab=no_delivery` | 读取到了不等于正确分流；还需按状态接单或进入发货。 |
| `NO_RECEIPT` 接单 | `receive-order` 请求/受理/写后状态转换完整（entry `290/291/292`） | `JufubaoShipmentGateway.java:12-18` 没有 `receiveOrder`；`JufubaoConnector.java:191-196` 只接受已经是 `NO_DELIVERY` 的订单 | 当前实现不会把 `NO_RECEIPT` 推进为可发货状态。 |
| receiver 入单 | `sub-order-info` 返回非空姓名、电话、地址（entry `664`） | `JufubaoOrderTransform.java:31-36,83-110` 固定 `receiver=null` 并把每单标为 `reviewRequired`；`211-242` 固定写 `receiver_missing=true` | 旧“receiver 契约未验证”前提已被 HAR 推翻，拉单映射尚未更新。 |
| 地址变更门禁 | 发货前检查已捕获；前端按 `need_confirm/message` 分支（entry `663` + 前端 entry `326`） | `JufubaoHttpShipmentGateway.java:19-24` 只有 query、sub-order-info、carrier、sub-order-send 四条路径；`JufubaoShipmentGateway.java:12-18` 没有地址检查 port | 当前发货会绕过一方前端已有的地址确认门禁。 |
| 商品/承运商读取 | `allow_send_num` 与承运商 `label/value` 已捕获（entry `664/665`） | `JufubaoHttpShipmentGateway.java:70-103` 已读取商品和承运商；`JufubaoConnector.java:202-220` 已校验总数量并映射承运商 | 这部分已有基础，但商品读取 DTO 尚未转换成真实写 DTO。 |
| `sub-order-send` 请求 | entry `667` 的写 DTO 含 `send_num` | `JufubaoHttpShipmentGateway.java:105-114` 直接序列化 products；`JufubaoConnector.java:294-337` 只递归删除 `fd-*`，不生成 `send_num` | 当前会保留 `allow_send_num`，却缺少抓包实际提交的 `send_num`。 |
| 成功响应解释 | entry `667` 为 HTTP 200，响应只有 `request_id` | `JufubaoHttpShipmentGateway.java:125-147` 在 `code` 为空时直接返回 `UNKNOWN`；`JufubaoConnector.java:237-247` 随即停止，不执行终态查询 | 真实成功响应会被当前实现误判为 `RECONCILIATION_REQUIRED`。 |
| 写后状态核验 | 接单约 6 秒、发货约 2.5 秒才收敛（entry `291/292/667/668`） | `JufubaoConnector.java:241-247` 提交后只立即调用一次 `findOrder` | 缺少短时、有界、可超时的轮询，正常最终一致性可能被误判。 |
| 生产调用入口 | 浏览器人工流程已真实执行 | `backend/src/main` 中 `pushShipmentResult(...)` 只出现于 `PlatformConnector.java:51-53` 的默认声明和 `JufubaoConnector.java:122-159` 的实现 | 没有 Controller、scheduler 或履约编排器调用点；有实现不等于系统可触发。 |

受控 HTTP 契约测试也固化了错误的成功形态：`JufubaoConnectorHttpContractTest.java:258-276` 把成功 stub 写成含 `code=0` 和 `message` 的响应，而真实 entry `667` 只有 `request_id`；`51-73` 也没有断言 `send_num`。因此现有绿测不能证明与真实成功契约一致。

## 应采用的成功语义

根据此 HAR，单订单发货不能以响应 `code=0/200` 为成功前提。更稳妥的闭环语义应是：

1. HTTP 2xx 且响应有结构合法的 `request_id`：记为“已受理，待核验”，不立刻标终态成功，也不直接标未知；
2. 在短时有界窗口内查询 `no_delivery`：目标离开列表才标成功；
3. 目标仍在列表、查询失败或轮询超时：进入人工对账，禁止盲目重提；
4. 明确业务拒绝：记录拒绝码与脱敏消息，不做成功核验；
5. `NO_RECEIPT` 接单同样需要“受理后轮询到 `NO_DELIVERY`”的两阶段语义。

HAR 给出了约 `6.15s` 和 `2.55s` 两个实际收敛样本，但它们不是官方 SLA，不能据此虚构固定超时或频控。实现参数应可配置，并以有界次数、退避和总时限保护平台。

## 尚未被这个 HAR 覆盖

- `need_confirm=true` 时的确认、取消以及后续请求；
- `receive-order` 明确拒绝、401、超时或长期不转 `NO_DELIVERY`；
- `sub-order-send` 明确业务拒绝、401、超时、重复运单与并发重放；
- 发货后显式 `delivered` 页或详情中的完成状态；
- 当前 Java 版本通过生产入口、真实账号执行全链路。

这些缺口不影响“这个 HAR 已包含真实成功 happy path 发货闭环”的结论，但意味着它不能被包装为全分支或 Java 生产验收。

## 最终判定

1. **“HAR 包里有发货闭环”——成立。** entry `261/290/291/292/663/664/665/667/668` 构成同一子单从接单到发货后离开待发货列表的真实成功链路。
2. **“HAR 没有 receiver、接单、地址门禁契约”——不成立。** 这三个契约都已在 API 响应与一方前端逻辑中找到。
3. **“`ae9168e` 基线已完整落地该闭环”——不成立。** 当时仍缺接单、receiver 入单、地址门禁、真实成功响应解释、有界轮询和生产调用入口；商品写 DTO 还缺 `send_num` 转换。
4. **“当前 Java 已真实账号验收”——不成立。** HAR 证明的是一方浏览器 happy path，不是 Java 客户端的线上执行结果；实现完成和真实外部验收必须分别陈述。

## 实施后收敛状态

本次实现分支以该 HAR 为契约基线，补齐 `NO_RECEIPT` 接单及状态复查、地址门禁、receiver 与可发商品读取、
实时承运商映射、显式 `send_num` 写 DTO、每次外部写的双层围栏、有界写后核验，以及 Shipment 级
`check / execute / reconcile` 入口。受控测试用于证明请求形态、状态机、幂等与失败关闭；它们仍不能替代
#102 所要求的授权真实订单外部验收。最终提交与测试清单应以本分支交付记录为准。
