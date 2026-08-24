package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchDetailView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsBatchUploadService.BatchUploadView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.BrandView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CaptchaView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.CertificationView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginCommand;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LoginView;
import cn.zimu.fulfillment.connector.zhonghui.ZhonghuiPmsService.LogisticsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中汇 PMS 商品录入管理面（契约见 {@code docs/openapi.yaml}）：
 *
 * <ul>
 *   <li>{@code GET /status} —— 连接模式与登录态（前端据此区分 MOCK/REAL 与是否需要登录）；</li>
 *   <li>{@code GET /captcha} + {@code POST /login} + {@code POST /logout} —— 人工验证码登录
 *       （token 只在内存，不对外暴露；login 走幂等注册表）；</li>
 *   <li>{@code GET /options} —— 可用品牌/资质/物流，供批量上传覆盖字段选择（需已登录）；</li>
 *   <li>{@code POST /batch-uploads} —— 从商品档案批量上传商品（幂等：意图先落库，逐商品结果落库，
 *       同幂等键+同请求重放首次结果）；</li>
 *   <li>{@code GET /upload-batches/{id}} —— 批次详情（含逐商品结果），用于恢复/审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/zhonghui-pms")
public class ZhonghuiPmsController {

    private static final String LOGIN_SCOPE = "zhonghui-pms.login";

    private final ZhonghuiPmsService client;
    private final ZhonghuiPmsProperties properties;
    private final ZhonghuiPmsSession session;
    private final ZhonghuiPmsBatchUploadService batchUploadService;
    private final IdempotencyService idempotency;

    public ZhonghuiPmsController(
            ZhonghuiPmsService client,
            ZhonghuiPmsProperties properties,
            ZhonghuiPmsSession session,
            ZhonghuiPmsBatchUploadService batchUploadService,
            IdempotencyService idempotency) {
        this.client = client;
        this.properties = properties;
        this.session = session;
        this.batchUploadService = batchUploadService;
        this.idempotency = idempotency;
    }

    @GetMapping("/status")
    public StatusView status() {
        return new StatusView(
                properties.getClientMode().toUpperCase(Locale.ROOT),
                properties.getWriteMode().toUpperCase(Locale.ROOT),
                properties.externalWritesEnabled(),
                properties.credentialsConfigured(),
                properties.liveReady(),
                client.authenticated());
    }

    /** 获取登录图片验证码；img 为 Base64 PNG（无 data URI 前缀）。 */
    @GetMapping("/captcha")
    public CaptchaView captcha() {
        return client.captcha();
    }

    /** 提交验证码完成登录；用户名/密码来自配置，token 只存在内存会话。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        WriteCommands.writeContext(operator);
        properties.requireExternalWritesEnabled();
        LoginCommand command = new LoginCommand(
                properties.getUsername(), properties.getPassword(), body.authCode(), body.captchaNo());
        // 幂等：同键+同请求重放首次登录结果；payload 不含密码（避免敏感信息入注册表）。
        IdempotentResult<LoginView> result = idempotency.execute(
                LOGIN_SCOPE,
                registryKey(key),
                Map.of(
                        "username", command.username(),
                        "auth_code", command.authCode(),
                        "captcha_no", command.captchaNo()),
                200,
                () -> client.login(command));
        return WriteCommands.respond(result);
    }

    /** 清除内存登录会话（幂等操作；token 不对外暴露）。 */
    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        WriteCommands.writeContext(operator);
        session.clear();
        return Map.of("success", true);
    }

    /** 可用品牌/资质/物流（批量上传覆盖字段的候选值）；需已登录（登录后才查询品牌/资质）。 */
    @GetMapping("/options")
    public OptionsView options() {
        if (!client.authenticated()) {
            throw BusinessException.unprocessable("PMS_LOGIN_REQUIRED", "请先完成中汇 PMS 登录");
        }
        return new OptionsView(client.usableBrands(), client.certifications(), client.logistics());
    }

    /** 从商品档案批量上传商品；幂等：批次意图先落库，逐商品返回成功/失败结果。 */
    @PostMapping("/batch-uploads")
    public ResponseEntity<?> batchUploads(
            @Valid @RequestBody BatchUploadCommand body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        WriteCommands.writeContext(operator);
        IdempotentResult<BatchUploadView> result = batchUploadService.upload(
                body, registryKey(key));
        return WriteCommands.respond(result);
    }

    /** 批次详情（含逐商品结果），用于恢复/审计。 */
    @GetMapping("/upload-batches/{batch_id}")
    public BatchDetailView uploadBatch(@PathVariable("batch_id") String batchId) {
        return batchUploadService.batch(WriteCommands.parseIdentifier(batchId));
    }

    public record StatusView(
            String clientMode,
            String writeMode,
            boolean externalWritesEnabled,
            boolean credentialsConfigured,
            boolean liveReady,
            boolean authenticated) {}

    public record LoginRequest(
            @NotBlank String authCode,
            @NotBlank String captchaNo) {}

    public record OptionsView(
            List<BrandView> brands,
            List<CertificationView> certifications,
            List<LogisticsView> logistics) {}

    private static String registryKey(String key) {
        String value = WriteCommands.requireIdempotencyKey(key);
        if (value.length() > 255) {
            throw BusinessException.badRequest(
                    "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度不能超过 255 个字符");
        }
        return value;
    }
}
