package cn.zimu.fulfillment.connector.zhonghui;

import java.math.BigDecimal;
import java.util.List;

/**
 * 中汇好泰 PMS 商品录入客户端应用边界（契约见仓库根目录 {@code pms_openapi.md}）。
 *
 * <p>REAL（{@link ZhonghuiPmsHttpClient}）与 MOCK（{@link MockZhonghuiPmsClient}）实现共用此
 * 边界。领域层只依赖本接口，不接触 PMS 原始 HTTP 细节与登录 token。
 *
 * <p>调用顺序（pms_openapi.md「自动上品建议调用顺序」）：{@link #captcha()} → {@link #login(LoginCommand)}
 * → 品牌/资质查询 → {@link #uploadImage(byte[], String)} → {@link #createGoods(GoodsCreateCommand)}。
 * 业务接口（品牌/资质/上传/创建）需要有效登录态，{@link #authenticated()} 用于前置校验。
 */
public interface ZhonghuiPmsService {

    /** 当前是否持有有效登录态（REAL 由内存 token 决定；MOCK 恒为 true）。 */
    boolean authenticated();

    /** 获取登录图片验证码；img 为 Base64 编码的 PNG（不含 data URI 前缀）。 */
    CaptchaView captcha();

    /** 使用用户名/密码/图片验证码登录，成功后持有 token。 */
    LoginView login(LoginCommand command);

    /** 查询当前商户可用品牌。 */
    List<BrandView> usableBrands();

    /** 查询商品可用资质。 */
    List<CertificationView> certifications();

    /** 查询可用物流公司（logisticsCarrier 覆盖字段的候选值）。 */
    List<LogisticsView> logistics();

    /**
     * 商品列表校验（POST /api/a1/cms/goodsInfos）：按商品名查询后定位 goodsItem 匹配的行，
     * 确认创建成功并取回 goodsId / 审核状态 / 上架状态；未命中返回 null。
     */
    GoodsVerifyView queryGoods(String goodsItem, String goodsName);

    /** 上传商品图片，返回可直接用于商品提交的公网图片 URL。 */
    String uploadImage(byte[] bytes, String contentType);

    /** 创建商品（PUT /api/a1/cms/goodsInfo）。 */
    GoodsCreateResult createGoods(GoodsCreateCommand command);

    /** 登录验证码。 */
    record CaptchaView(String captchaNo, String img) {}

    /** 登录请求（用户名/密码来自配置，AuthCode/CaptchaNo 由人工输入）。 */
    record LoginCommand(String username, String password, String authCode, String captchaNo) {}

    /** 登录结果（token 只存在于内存会话，不对外暴露）。 */
    record LoginView(boolean success, String businessCode, String message) {}

    /** 可用品牌。 */
    record BrandView(String brandId, String brandName) {}

    /** 商品可用资质。 */
    record CertificationView(
            String certificationId,
            String certificationName,
            String commencementDate,
            String inspectionEndDate) {}

    /** 可用物流公司。 */
    record LogisticsView(String logistId, String logistName) {}

    /** 商品列表校验结果；goodsId 为 PMS 侧商品 id（十进制字符串），状态文本如 待平台审核 / 待上架。 */
    record GoodsVerifyView(String goodsId, String goodsStaStr, String goodSaleStaStr) {}

    /** 创建商品结果；PMS 成功响应 data 为 null，goodsId 需后续通过商品列表查询确认。 */
    record GoodsCreateResult(boolean success, String businessCode, String message) {}

    /**
     * 创建商品载荷（字段名与 pms_openapi.md CreateGoodsRequest 一一对应）。
     * 业务生成的字段（thirdId / limitAreaTempId / certificationType / certificationId /
     * goodsTax / brandId / logisticsCarrier 等）由配置默认值或批次覆盖值注入。
     */
    record GoodsCreateCommand(
            String goodsName,
            Integer thirdId,
            String goodDescr,
            String goodsItem,
            BigDecimal goodsTax,
            String photoStr,
            String details,
            String desc,
            String jdParam,
            String attrFlag,
            List<Object> attrAndStock,
            String banSaleFlag,
            Integer limitAreaTempId,
            String saleLimit,
            BigDecimal goodsPrice,
            BigDecimal weight,
            Integer goodsNum,
            BigDecimal supplyPrice,
            String goodsBar,
            String saleUnit,
            String specsName,
            Integer noReasonReturnDay,
            Integer goodsPurchaseMultiplier,
            Integer certificationType,
            Integer certificationId,
            String jdSkuId,
            Integer brandId,
            String logisticsCarrier,
            String logisticsCarrierDescription,
            String producingArea,
            List<Integer> specialisedIds,
            Integer origincountry) {}
}
