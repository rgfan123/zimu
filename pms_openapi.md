# PMS 商品录入 API｜OpenAPI 3.1

> 来源：`pms.zhonghuihaotai.com_2026_08_19_11_53_12.har`
>
> 说明：
> - 本文档根据实际 HAR 请求整理，不代表官方公开 API 文档。
> - HAR 中的真实用户名、密码、手机号、JWT 等敏感信息已脱敏，**不要直接写入代码仓库**。
> - 当前确认的主链路：**验证码 → 登录 → 品牌/资质查询 → 图片上传 → 创建商品 → 商品列表校验**。
> - `thirdId`、`limitAreaTempId`、`certificationType/certificationId` 等字段的业务生成规则仍需进一步确认。

```yaml
openapi: 3.1.0

info:
  title: Zhonghui Haotai PMS 商品录入 API
  version: 0.1.0
  description: |
    根据 PMS Web 后台实际 HAR 请求逆向整理的 OpenAPI 文档。

    核心业务流程：
    1. 获取验证码
    2. 使用用户名、密码、验证码登录
    3. 使用登录返回的 JWT 调用业务 API
    4. 查询品牌与商品资质
    5. 上传商品图片
    6. 创建商品
    7. 查询商品列表确认审核状态

servers:
  - url: https://pms.zhonghuihaotai.com
    description: PMS Production

tags:
  - name: Auth
    description: 登录与鉴权
  - name: Brand
    description: 品牌查询
  - name: Certification
    description: 商品资质
  - name: Upload
    description: 文件/图片上传
  - name: Goods
    description: 商品管理
  - name: Logistics
    description: 物流配置
  - name: Merchant
    description: 商户辅助配置

paths:

  /api/a1/cms/captcha:
    get:
      tags: [Auth]
      summary: 获取登录验证码
      description: |
        获取图片验证码及 captchaNo。
        img 为 Base64 编码图片内容。
      security: []
      responses:
        "200":
          description: 获取成功
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/CaptchaResponse"
              example:
                code: 0
                msg: ok
                data:
                  captchaNo: "xxxxxxxxxxxxxxxx.1787111867"
                  img: "iVBORw0KGgoAAA..."

  /api/a1/cms/login/teleNo:
    get:
      tags: [Auth]
      summary: 根据用户名查询绑定手机号
      description: |
        Web 登录页使用的辅助接口。
        当前观察到的普通登录流程并不依赖短信验证码。
      security: []
      parameters:
        - name: UserName
          in: query
          required: true
          schema:
            type: string
      responses:
        "200":
          description: 查询成功
          content:
            application/json:
              schema:
                type: object
                properties:
                  code:
                    type: integer
                    example: 0
                  msg:
                    type: string
                    example: success
                  data:
                    type: string
                    description: 绑定手机号
                    example: "137****0065"

  /api/a1/cms/login:
    post:
      tags: [Auth]
      summary: 登录 PMS
      description: |
        使用用户名、密码、图片验证码以及 captchaNo 登录。

        登录成功后返回 JWT。
        后续业务 API 需要使用非标准请求头：

        `auth: Bearer <token>`
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/LoginRequest"
            example:
              UserName: "${PMS_USERNAME}"
              PassWord: "${PMS_PASSWORD}"
              AuthCode: "5620"
              CaptchaNo: "xxxxxxxxxxxxxxxx.1787111867"
      responses:
        "200":
          description: 登录结果
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LoginResponse"
              example:
                code: 0
                msg: success
                data:
                  token: "<JWT_TOKEN>"

  /api/a1/cms/merchantBrand/usable:
    get:
      tags: [Brand]
      summary: 查询当前商户可用品牌
      security:
        - BearerAuth: []
      parameters:
        - name: brandName
          in: query
          required: false
          description: 品牌名称或搜索关键词
          schema:
            type: string
      responses:
        "200":
          description: 品牌列表
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/BrandListResponse"
              example:
                code: 0
                msg: ok
                data:
                  - brandId: 164343
                    brandName: 子牧

  /api/a1/cms/goodsInfo/certificationList:
    get:
      tags: [Certification]
      summary: 查询商品可用资质
      security:
        - BearerAuth: []
      parameters:
        - name: goodsName
          in: query
          required: false
          schema:
            type: string
        - name: merId
          in: query
          required: false
          schema:
            type: string
        - name: certType
          in: query
          required: false
          description: 资质类型
          schema:
            type: integer
            example: 1
      responses:
        "200":
          description: 资质列表
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/CertificationListResponse"

  /api/a1/cms/upload/imgs:
    post:
      tags: [Upload]
      summary: 上传商品图片
      description: |
        上传商品主图或详情图。
        成功后 data 返回可直接用于商品提交的公网图片 URL。
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required:
                - uploadFile
              properties:
                uploadFile:
                  type: string
                  format: binary
                  description: 图片文件
      responses:
        "200":
          description: 上传成功
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/UploadResponse"
              example:
                code: 0
                msg: 成功
                data: "https://img.zhonghuihaotai.com/file_xxx.jpeg"

  /api/a1/cms/goodsInfo:
    put:
      tags: [Goods]
      summary: 创建商品
      description: |
        商品录入主接口。

        已确认：
        - `brandId` 可通过 `/merchantBrand/usable` 获取
        - `certificationId` 应通过 `/goodsInfo/certificationList` 获取或按业务配置
        - `photoStr` 使用上传接口返回的图片 URL
        - `details` 可传包含详情图片 URL 的 HTML
        - `logisticsCarrier` 为物流 ID 的逗号分隔字符串

        待确认业务规则：
        - `thirdId`
        - `limitAreaTempId`
        - `goodsTax`
        - `certificationType`
        - `certificationId`
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateGoodsRequest"
            example:
              goodsName: "正宗新疆特产伽师瓜原产地新疆喀什市伽师县10-12斤（两颗装）"
              thirdId: 3407
              goodDescr: "新疆伽师原产，地理标志贡瓜"
              goodsItem: "0000000000000000000"
              goodsTax: 9
              photoStr: "1,https://img.zhonghuihaotai.com/file_xxx.jpeg"
              details: '<p><img src="https://img.zhonghuihaotai.com/file_detail.jpeg"></p>'
              desc: "商品描述"
              jdParam: "[]"
              attrFlag: "0"
              AttrAndStock: []
              banSaleFlag: "1"
              limitAreaTempId: 2075
              saleLimit: ""
              goodsPrice: 432
              weight: null
              goodsNum: 99
              supplyPrice: 80
              goodsBar: ""
              saleUnit: "件"
              specsName: ""
              noReasonReturnDay: -1
              goodsPurchaseMultiplier: 1
              certificationType: 2
              certificationId: 56118
              jdSkuId: ""
              brandId: 164343
              logisticsCarrier: "1,20"
              logisticsCarrierDescription: ""
              producingArea: "新疆"
              specialisedIds: []
              origincountry: 1
      responses:
        "200":
          description: 创建结果
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/BaseResponse"
              example:
                code: 0
                msg: ok
                data: null

  /api/a1/cms/goodsInfos:
    post:
      tags: [Goods]
      summary: 查询商品列表
      description: |
        商品列表与提交结果校验接口。
        创建商品后可按更新时间倒序查询，确认 goodsId、商品审核状态和上架状态。
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/GoodsListRequest"
            example:
              goodsIds: ""
              goodsName: ""
              goodSaleSta: -1
              goodsSta: -1
              groupIds: ""
              merIds: ""
              channelId: ""
              isJd: ""
              supplierType: ""
              grossProfitMax: 0
              grossProfitMin: 0
              priceMax: 0
              priceMin: 0
              pageNo: 1
              pageSize: 10
              orderProperty: updateDate
              orderDirection: desc
              thirdIds: ""
              logisticsType: -1
              brandIds: ""
              upSkuIds: ""
              onlyStockOk: false
              onlyStockAddress: ""
              isRecommend: 0
              isPrimeType: false
              isCedeProfits: false
              isJDShopName: -1
              specialisedIds: ""
              origincountry: -1
      responses:
        "200":
          description: 商品分页结果
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/GoodsListResponse"

  /api/a1/cms/logistics:
    get:
      tags: [Logistics]
      summary: 查询物流公司列表
      security:
        - BearerAuth: []
      responses:
        "200":
          description: 物流列表
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LogisticsListResponse"
              example:
                code: 0
                msg: ok
                data:
                  - logistId: 1
                    logistName: 顺丰速运
                    group: 0
                  - logistId: 20
                    logistName: 京东快递
                    group: 0

  /api/a1/cms/freightTemplate/list:
    post:
      tags: [Logistics]
      summary: 查询运费模板
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                pageNo:
                  type: integer
                  minimum: 1
                  default: 1
                pageSize:
                  type: integer
                  minimum: 1
                  default: 20
                total:
                  type: integer
                  default: 0
      responses:
        "200":
          description: 运费模板列表
          content:
            application/json:
              schema:
                type: object
                additionalProperties: true

  /api/a1/cms/merchant/goodsFamily:
    get:
      tags: [Merchant]
      summary: 查询商品分类树
      security:
        - BearerAuth: []
      responses:
        "200":
          description: 商品分类树
          content:
            application/json:
              schema:
                type: object
                additionalProperties: true

  /api/a1/cms/merchant/groupRate:
    get:
      tags: [Merchant]
      summary: 查询商户分组费率
      security:
        - BearerAuth: []
      parameters:
        - name: merId
          in: query
          required: true
          schema:
            type: string
      responses:
        "200":
          description: 商户分组费率
          content:
            application/json:
              schema:
                type: object
                additionalProperties: true

components:

  securitySchemes:
    BearerAuth:
      type: apiKey
      in: header
      name: auth
      description: |
        PMS 使用自定义 `auth` Header，而不是标准 Authorization Header。
        格式：`Bearer <JWT_TOKEN>`

  schemas:

    BaseResponse:
      type: object
      required: [code, msg]
      properties:
        code:
          type: integer
          description: 0 表示成功
        msg:
          type: string
        data:
          nullable: true

    CaptchaResponse:
      type: object
      required: [code, msg, data]
      properties:
        code:
          type: integer
          example: 0
        msg:
          type: string
          example: ok
        data:
          type: object
          required: [captchaNo, img]
          properties:
            captchaNo:
              type: string
            img:
              type: string
              description: Base64 PNG 图片，不包含 data URI 前缀

    LoginRequest:
      type: object
      required:
        - UserName
        - PassWord
        - AuthCode
        - CaptchaNo
      properties:
        UserName:
          type: string
        PassWord:
          type: string
          format: password
        AuthCode:
          type: string
          description: 用户读取图片验证码后输入的验证码
        CaptchaNo:
          type: string
          description: captcha 接口返回的 captchaNo

    LoginResponse:
      type: object
      required: [code, msg, data]
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          type: object
          required: [token]
          properties:
            token:
              type: string
              description: JWT

    Brand:
      type: object
      properties:
        brandId:
          type: integer
        brandName:
          type: string

    BrandListResponse:
      type: object
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          nullable: true
          type: array
          items:
            $ref: "#/components/schemas/Brand"

    Certification:
      type: object
      properties:
        certificationId:
          type: integer
        certificationName:
          type: string
        commencementDate:
          type: string
          description: 日期字符串
          examples: ["2026-01-16"]
        certificationImages:
          type: array
          items:
            type: string
            format: uri
        inspections:
          type: array
          items:
            type: string
        inspectionEndDate:
          type: string

    CertificationListResponse:
      type: object
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          type: array
          items:
            $ref: "#/components/schemas/Certification"

    UploadResponse:
      type: object
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          type: string
          format: uri

    CreateGoodsRequest:
      type: object
      required:
        - goodsName
        - thirdId
        - goodsTax
        - photoStr
        - details
        - goodsPrice
        - goodsNum
        - supplyPrice
        - saleUnit
        - certificationType
        - certificationId
        - brandId
        - logisticsCarrier
        - producingArea
      properties:

        goodsName:
          type: string
          description: 商品名称

        thirdId:
          type: integer
          description: |
            第三方商品分类/业务关联 ID。
            当前 HAR 示例为 3407，业务生成规则待确认。

        goodDescr:
          type: string
          description: 商品卖点/短描述

        goodsItem:
          type: string
          description: 商品编码/货号类字段
          example: "0000000000000000000"

        goodsTax:
          type: number
          description: 税率
          example: 9

        photoStr:
          type: string
          description: |
            商品主图。
            实际格式示例：`1,https://img.xxx/file.jpeg`

        details:
          type: string
          description: 商品详情 HTML

        desc:
          type: string
          description: 商品描述

        jdParam:
          type: string
          default: "[]"

        attrFlag:
          type: string
          default: "0"

        AttrAndStock:
          type: array
          default: []
          items: {}

        banSaleFlag:
          type: string
          default: "1"

        limitAreaTempId:
          type: integer
          description: |
            限售地区/区域模板 ID。
            当前 HAR 示例为 2075，具体获取规则待确认。

        saleLimit:
          type: string
          default: ""

        goodsPrice:
          type: number
          description: 商品售价

        weight:
          nullable: true
          type: number

        goodsNum:
          type: integer
          description: 库存数量

        supplyPrice:
          type: number
          description: 供货价

        goodsBar:
          type: string
          default: ""

        saleUnit:
          type: string
          example: 件

        specsName:
          type: string
          default: ""

        noReasonReturnDay:
          type: integer
          default: -1

        goodsPurchaseMultiplier:
          type: integer
          default: 1

        certificationType:
          type: integer
          description: 商品资质类型

        certificationId:
          type: integer
          description: 商品资质 ID

        jdSkuId:
          type: string
          default: ""

        brandId:
          type: integer
          description: 品牌 ID

        logisticsCarrier:
          type: string
          description: |
            支持物流公司的 ID，以逗号分隔。
            示例：`1,20` = 顺丰速运 + 京东快递。

        logisticsCarrierDescription:
          type: string
          default: ""

        producingArea:
          type: string
          description: 商品产地

        specialisedIds:
          type: array
          default: []
          items:
            type: integer

        origincountry:
          type: integer
          default: 1

    GoodsListRequest:
      type: object
      properties:
        goodsIds:
          type: string
          default: ""
        goodsName:
          type: string
          default: ""
        goodSaleSta:
          type: integer
          default: -1
        goodsSta:
          type: integer
          default: -1
        groupIds:
          type: string
          default: ""
        merIds:
          type: string
          default: ""
        channelId:
          type: string
          default: ""
        isJd:
          type: string
          default: ""
        supplierType:
          type: string
          default: ""
        grossProfitMax:
          type: number
          default: 0
        grossProfitMin:
          type: number
          default: 0
        priceMax:
          type: number
          default: 0
        priceMin:
          type: number
          default: 0
        pageNo:
          type: integer
          minimum: 1
          default: 1
        pageSize:
          type: integer
          minimum: 1
          default: 10
        orderProperty:
          type: string
          default: updateDate
        orderDirection:
          type: string
          enum: [asc, desc]
          default: desc
        thirdIds:
          type: string
          default: ""
        logisticsType:
          type: integer
          default: -1
        brandIds:
          type: string
          default: ""
        upSkuIds:
          type: string
          default: ""
        onlyStockOk:
          type: boolean
          default: false
        onlyStockAddress:
          type: string
          default: ""
        isRecommend:
          type: integer
          default: 0
        isPrimeType:
          type: boolean
          default: false
        isCedeProfits:
          type: boolean
          default: false
        isJDShopName:
          type: integer
          default: -1
        specialisedIds:
          type: string
          default: ""
        origincountry:
          type: integer
          default: -1

    GoodsSummary:
      type: object
      properties:
        goodsId:
          type: integer
        goodsName:
          type: string
        goodsItem:
          type: string
        merId:
          type: string
        merName:
          type: string
        goodsSta:
          type: integer
        goodsStaStr:
          type: string
          example: 待平台审核
        goodSaleSta:
          type: integer
        goodSaleStaStr:
          type: string
          example: 待上架
        goodsNum:
          type: integer
        coverPhoto:
          type: string
          format: uri
        goodsPrice:
          type: number
        supplyPrice:
          type: number
        grossProfit:
          type: number
        saleUnit:
          type: string
        specsName:
          type: string
        goodsBar:
          type: string
        updateDateStr:
          type: string

    GoodsListResponse:
      type: object
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          type: object
          properties:
            GoodsInfoList:
              type: array
              items:
                $ref: "#/components/schemas/GoodsSummary"
          additionalProperties: true

    Logistics:
      type: object
      properties:
        logistId:
          type: integer
        logistName:
          type: string
        group:
          type: integer

    LogisticsListResponse:
      type: object
      properties:
        code:
          type: integer
        msg:
          type: string
        data:
          type: array
          items:
            $ref: "#/components/schemas/Logistics"
```


## 实际登录信息（敏感）

> ⚠️ 以下内容来自本次 HAR 抓包，包含真实账号凭据。仅供内部调试使用，不要提交到 Git、公开文档或发送给无关人员。

```yaml
pms_login:
  username: "bjjhhf"
  password: "JHhf123456*"

  captcha_endpoint: "GET /api/a1/cms/captcha"
  login_endpoint: "POST /api/a1/cms/login"

  login_request_example:
    UserName: "bjjhhf"
    PassWord: "JHhf123456*"
    AuthCode: "<图片验证码>"
    CaptchaNo: "<captcha 接口返回的 captchaNo>"

  auth_header:
    name: "auth"
    format: "Bearer <JWT_TOKEN>"
```

实际登录请求结构：

```json
{
  "UserName": "bjjhhf",
  "PassWord": "JHhf123456*",
  "AuthCode": "<图片验证码>",
  "CaptchaNo": "<captchaNo>"
}
```

登录成功后：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "<JWT_TOKEN>"
  }
}
```

后续业务接口请求头：

```http
auth: Bearer <JWT_TOKEN>
```

> 原 HAR 中还包含当次会话的真实 JWT。由于 JWT 属于短期会话凭据且可能失效，本文档不固化具体 Token 值；程序应通过登录接口动态获取。


## 从商品档案批量上传服务（本仓库实现）

仓库内已按本文档实现「从商品档案批量上传商品到中汇 PMS」服务，全部实现位于
`backend/src/main/java/cn/zimu/fulfillment/connector/zhonghui/`：

| 组件 | 说明 |
|---|---|
| `ZhonghuiPmsProperties` | `app.zhonghui-pms.*` 配置（凭据 + 创建商品全局默认值） |
| `ZhonghuiPmsService` | 应用边界（验证码/登录/品牌/资质/物流/商品列表校验/图片上传/创建商品） |
| `ZhonghuiPmsHttpClient` | REAL 客户端（JDK HttpClient，`client-mode=REAL` 启用） |
| `MockZhonghuiPmsClient` | 本地假客户端（默认 `client-mode=MOCK`，不触网） |
| `ZhonghuiPmsSession` | 登录 token 内存缓存（不落库、不对外暴露，2 小时有效） |
| `ZhonghuiPmsBatchUploadService` | 读商品档案（Sku+Product）→ 批次意图先落库 → 逐商品创建 + 商品列表校验 → 回写结果 |
| `ZhonghuiPmsUploadBatch(Item)` | 批次/逐商品结果持久化（V35 迁移，`zhonghui_pms_upload_batches(_items)` 表） |
| `ZhonghuiPmsController` | `GET/POST /api/v1/zhonghui-pms/*` 管理面 |

### REST 接口（前端「上传平台 → 中汇渠道平台」页面入口）

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/zhonghui-pms/status` | 连接模式（MOCK/REAL）、凭据是否配置、登录态 |
| GET | `/api/v1/zhonghui-pms/captcha` | 登录图片验证码（`captcha_no` + Base64 `img`） |
| POST | `/api/v1/zhonghui-pms/login` | 提交 `auth_code` + `captcha_no` 完成登录（幂等：同键+同请求重放首次结果，payload 不含密码；token 只在内存） |
| POST | `/api/v1/zhonghui-pms/logout` | 清除内存登录会话（幂等） |
| GET | `/api/v1/zhonghui-pms/options` | 可用品牌/资质/物流（需已登录） |
| POST | `/api/v1/zhonghui-pms/batch-uploads` | `{sku_ids, overrides}` → 批次结果（幂等：同键+同请求重放首次批次，不重复调 PMS；同键+不同请求 409） |
| GET | `/api/v1/zhonghui-pms/upload-batches/{batch_id}` | 批次详情（含逐商品结果），用于恢复/审计 |

写接口按 api-contract §3.2 强制 `Idempotency-Key` 并接入幂等注册表（`zhonghui-pms.login` /
`zhonghui-pms.batch-upload` 两个 scope）；前端批量上传使用**稳定幂等键**
（`zhonghui-pms-batch-<fnv1a(sku_ids+overrides)>-<数量>`），同一批商品重试/刷新即重放首次结果，
不会重复创建商品；改动选择或覆盖字段才产生新键。响应侧标识符（`batch_id`/`goods_id`/品牌/资质/物流 id）
与覆盖字段均按 §3.1 以十进制字符串传输。

### 配置（环境变量，见 `.env.example`）

```dotenv
ZHONGHUI_PMS_CLIENT_MODE=MOCK|REAL
ZHONGHUI_PMS_BASE_URL=https://pms.zhonghuihaotai.com
ZHONGHUI_PMS_USERNAME=...
ZHONGHUI_PMS_PASSWORD=...
# 创建商品全局默认值（请求 overrides 可覆盖）：
ZHONGHUI_PMS_DEFAULT_BRAND_ID=
ZHONGHUI_PMS_DEFAULT_CERTIFICATION_TYPE=
ZHONGHUI_PMS_DEFAULT_CERTIFICATION_ID=
ZHONGHUI_PMS_DEFAULT_THIRD_ID=
ZHONGHUI_PMS_DEFAULT_LIMIT_AREA_TEMP_ID=
ZHONGHUI_PMS_DEFAULT_GOODS_TAX=
ZHONGHUI_PMS_DEFAULT_LOGISTICS_CARRIER=
ZHONGHUI_PMS_DEFAULT_PRODUCING_AREA=
ZHONGHUI_PMS_DEFAULT_GOODS_NUM=99
ZHONGHUI_PMS_DEFAULT_SALE_UNIT=件
ZHONGHUI_PMS_DEFAULT_ORIGIN_COUNTRY=1
```

### 商品档案字段 → 创建商品载荷映射

| PMS 字段 | 来源（优先级从高到低） |
|---|---|
| `goodsName` | 商品名 + 规格（规格未包含在商品名时拼接） |
| `goodsItem` | SKU 编码（也是商品列表校验的匹配键） |
| `goodsPrice` / `supplyPrice` | 批次 `overrides` → SKU 零售价/进货价 → 商品层零售价/进货价 |
| `goodsNum` | `overrides.goods_num` → 配置默认（99） |
| `saleUnit` | `overrides.sale_unit` → SKU 单位 → 配置默认（件） |
| `goodsBar` | SKU 条码 |
| `specsName` / `desc` | SKU 规格 / 商品描述+原料 |
| `photoStr` / `details` | 商品主图存在时先经 `/upload/imgs` 换公网 URL，`photoStr="1,<url>"`、`details=<p><img src=...></p>`；无主图时为空并回写 `warning`（PMS 可能拒绝） |
| `brandId` / `certificationType` / `certificationId` / `thirdId` / `limitAreaTempId` / `goodsTax` / `logisticsCarrier` / `producingArea` / `origincountry` | `overrides` → 配置默认值 |
| 其余字段 | 按「可以暂时作为默认值的字段」固定值 |

### 执行与回写（api-contract §3.5 + §3.2）

0. 幂等注册表先决：同键+同请求直接重放首次批次结果（响应含首次 `batch_id`），不执行任何外部调用；
   同键+不同请求返回 409 `IDEMPOTENCY_CONFLICT`；失败（传输层）标记后可重试。
1. 批次意图先落库（`zhonghui_pms_upload_batches` PENDING + 逐商品 `_items` PENDING），再执行外部调用；

1. 批次意图先落库（`zhonghui_pms_upload_batches` PENDING + 逐商品 `_items` PENDING），再执行外部调用；
2. 每个商品创建成功后调用 `POST /api/a1/cms/goodsInfos` 做商品列表校验（按 `goodsItem`=SKU 编码匹配），
   回写 `goods_id` 与审核/上架状态文本（以 `/` 连接，如 `待平台审核/待上架`）；校验失败（best-effort）不翻转创建成功结论；
3. 逐商品回写 SUCCESS/FAILED（含业务码/消息/warning），批次最后置 COMPLETED 并写总数；
4. 中途断连遗留的 PENDING 行即为可审计/可恢复的意图记录，可用 `GET /upload-batches/{id}` 查询。

### 已知限制

1. 登录验证码需人工识别输入（不内置 OCR）；token 为单实例内存缓存，多实例部署需换分布式会话。
2. PMS 创建商品无幂等语义：相同批次重试可能产生重复商品，重试前请先在 PMS 侧核对
   （`goodsItem`=SKU 编码 可用于查重）。
3. `thirdId`、`limitAreaTempId`、`certificationType/Id`、`goodsTax` 等字段的业务规则仍未从 HAR 中
   完全确认，当前以配置默认值 + 请求覆盖提供，正式使用前请与中汇确认取值规则。
4. 批次/逐商品结果已落库（V35），但本服务不做自动重跑；PENDING 遗留行需人工确认后重试。

## 自动上品建议调用顺序

```text
GET  /api/a1/cms/captcha
        │
        ▼
POST /api/a1/cms/login
        │
        └── token
             │
             ▼
GET  /api/a1/cms/merchantBrand/usable
             │
             └── brandId
             │
             ▼
GET  /api/a1/cms/goodsInfo/certificationList
             │
             └── certificationId
             │
             ▼
POST /api/a1/cms/upload/imgs
             │
             └── 主图 URL / 详情图 URL
             │
             ▼
GET  /api/a1/cms/logistics
             │
             └── logisticsCarrier
             │
             ▼
PUT  /api/a1/cms/goodsInfo
             │
             ▼
POST /api/a1/cms/goodsInfos
             │
             └── goodsId / goodsStaStr / goodSaleStaStr
```

## 当前仍需确认的字段

| 字段 | 当前 HAR 示例 | 当前判断 |
|---|---:|---|
| `thirdId` | `3407` | 不建议硬编码，需确认来源/映射规则 |
| `limitAreaTempId` | `2075` | 疑似区域/限售模板 ID，需确认对应接口 |
| `certificationType` | `2` | 需确认不同商品如何选择 |
| `certificationId` | `56118` | 应根据实际商品资质动态匹配 |
| `goodsTax` | `9` | 应按商品税率配置，不应全局固定 |
| `goodsItem` | `0000000000000000000` | 需确认是否可以长期使用默认值 |

## 可以暂时作为默认值的字段

```yaml
defaults:
  jdParam: "[]"
  attrFlag: "0"
  AttrAndStock: []
  banSaleFlag: "1"
  saleLimit: ""
  weight: null
  goodsBar: ""
  saleUnit: "件"
  specsName: ""
  noReasonReturnDay: -1
  goodsPurchaseMultiplier: 1
  jdSkuId: ""
  logisticsCarrierDescription: ""
  specialisedIds: []
  origincountry: 1
```

## 安全建议

HAR 中包含真实登录凭据与 JWT。本文档已做脱敏，但原 HAR 本身仍属于敏感文件。

建议：

1. 不要把 HAR 提交到 Git。
2. 不要把用户名、密码或 JWT 写死进代码。
3. 使用环境变量，例如：

```bash
PMS_USERNAME=...
PMS_PASSWORD=...
```

4. API Client 在登录后仅把 Token 保存在内存或短期安全缓存中。
5. 如果原 HAR 已经分享给第三方，建议更换密码并让旧 Token 失效。
