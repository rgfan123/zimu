package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.PlatformScriptRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 来源回填文件在线推送（票 11 回传通道，人工触发）。
 *
 * <p>系统已生成回填文件（SourceReturnExport）后，本服务把该文件投递到来源平台：
 * 彩食鲜走「上传 Excel」importDeliverExcl（复用回填文件字节），聚福宝走
 * multi-send JSON（脚本解析回填文件构造 package_list）。推送由操作员人工触发，
 * 同一回填文件版本只允许推送成功一次（幂等闸门）；PUSHING 防并发重推（含超时回收）；
 * FAILED 可重试。凭据复用 data-local 本地凭据文件（环境变量优先），脚本通道与拉取一致。
 *
 * <p>外部调用不持有事务（A2，契约 §3.5）：推送拆成三段——{@link #claimPush}（REQUIRES_NEW
 * 抢占 PUSHING 意图并提交）、{@link #runPushScript}（事务外执行平台脚本，不持有数据库连接）、
 * {@link #completePush}（REQUIRES_NEW 回写 SUCCESS/FAILED）。脚本执行期间绝无事务/连接被占用，
 * 平台已受理而本地回滚导致重复回传的窗口被消除。
 *
 * <p>结果未知格（A4，票 11）：脚本区分「平台明确拒绝」（outcome=rejected，有平台 code/message）
 * 与「网络/超时/未知」（outcome=unknown）。unknown 时 push_error 写入
 * {@code {"unknown_outcome": true, "message": "结果未知，请到平台核实是否已受理后再决定是否重推"}}，
 * 响应 message 同样提示，要求人工核实后再决定是否重推。
 *
 * <p>回传结果与订单同步（P1，票 11 未闭环项）：推送成功/失败在 completePush 的同一
 * REQUIRES_NEW 事务内，把该回填文件来源批次关联 shipment 的 app.shipment_syncs 行置为
 * SYNCED / SYNC_FAILED（含平台错误明细与 attempt_count），使「平台已受理发货结果」与
 * 本地发货同步状态一致（落点依据与幂等规则见 {@link #SYNC_SHIPMENTS_SQL}）。
 */
@Service
public class SourceReturnPushService {

    private static final Logger log = LoggerFactory.getLogger(SourceReturnPushService.class);

    static final String CLAIM_SQL = """
            UPDATE app.source_return_exports
            SET push_status='PUSHING', push_started_at=CURRENT_TIMESTAMP, pushed_by=?, push_error=NULL
            WHERE id=?
              AND (push_status IN ('NOT_PUSHED','FAILED')
                   OR (push_status='PUSHING'
                       AND (push_started_at IS NULL
                            OR push_started_at < CURRENT_TIMESTAMP - make_interval(secs => ?))))
            """;
    static final String SUCCESS_SQL = """
            UPDATE app.source_return_exports
            SET push_status='SUCCESS', pushed_at=CURRENT_TIMESTAMP, pushed_by=?,
                push_platform_ref=?, push_error=NULL
            WHERE id=?
            """;
    static final String FAILED_SQL = """
            UPDATE app.source_return_exports
            SET push_status='FAILED', push_error=?::jsonb, pushed_by=?
            WHERE id=?
            """;

    /**
     * P1（票 11 未闭环项）：推送成功 = 平台已受理发货结果 → 该回填文件来源批次关联的
     * shipment 同步状态置 SYNCED。
     *
     * <p>落点决策（schema 依据）：orders 表没有 sync_status / synced_at / last_error_* 列
     * （只有订单生命周期 order_status，含 SYNCED/SYNC_FAILED 枚举但无逐次明细、无错误落点）；
     * app.shipment_syncs 才是按 (shipment_id, source_channel) 的唯一同步状态表
     * （v_fulfillment_daily 的 awaiting_sync / synced / sync_failed 指标直接读它）。
     * 现有 JD 同步链路 ShipmentJdTrackingBackfillService 同样把同步/查询状态写进 per-shipment
     * 表（shipment_jd_outbounds）而不动 orders——故此处对齐为只更新 shipment_syncs，
     * 不写 order_events（基线仅有 SOURCE_SYNCED 事件类型、无 SYNC_FAILED 类型，本范围不能加迁移；
     * 审计日志 audit() 即事件轨迹）。
     *
     * <p>关联链：source_return_exports.import_batch_id → orders.source_import_batch_id
     * （data_scope='BUSINESS'）→ shipments.order_id → shipment_syncs.shipment_id，且
     * shipment_syncs.source_channel 与推送渠道一致。
     *
     * <p>幂等/重试：WHERE sync_status <> 'SYNCED'——已同步行不重写（防重）；PENDING 首推置
     * SYNCED，SYNC_FAILED 重推成功后恢复 SYNCED。attempt_count 每次尝试 +1（与 seed 语义
     * SYNCED=1 / SYNC_FAILED=3 一致）。CHECK 约束同步满足：
     * (sync_status='SYNCED')=(synced_at IS NOT NULL)；(last_error_code IS NULL)=(last_error_message IS NULL)。
     */
    static final String SYNC_SHIPMENTS_SQL = """
            UPDATE app.shipment_syncs ss
            SET sync_status='SYNCED', synced_at=CURRENT_TIMESTAMP,
                attempt_count=attempt_count+1, last_error_code=NULL, last_error_message=NULL
            WHERE ss.source_channel=?
              AND ss.sync_status <> 'SYNCED'
              AND ss.shipment_id IN (
                  SELECT s.id FROM app.shipments s
                  JOIN app.orders o ON o.id=s.order_id
                  WHERE o.source_import_batch_id=? AND o.data_scope='BUSINESS')
            """;

    /**
     * P1：推送失败（平台明确拒绝 rejected / 结果未知 unknown / 脚本错误）→ 批次内 shipment
     * 同步状态置 SYNC_FAILED 并记录平台 code/message；SYNCED 行不回退（防重），失败重试逐次
     * attempt_count+1、覆盖最近一次错误明细。
     */
    static final String SYNC_FAIL_SHIPMENTS_SQL = """
            UPDATE app.shipment_syncs ss
            SET sync_status='SYNC_FAILED', synced_at=NULL, attempt_count=attempt_count+1,
                last_error_code=?, last_error_message=?
            WHERE ss.source_channel=?
              AND ss.sync_status <> 'SYNCED'
              AND ss.shipment_id IN (
                  SELECT s.id FROM app.shipments s
                  JOIN app.orders o ON o.id=s.order_id
                  WHERE o.source_import_batch_id=? AND o.data_scope='BUSINESS')
            """;

    /** 推送渠道 → 脚本规格（脚本文件名 + 凭据文件名 + 凭据环境变量名）。 */
    private static final Map<String, ChannelScriptSpec> PUSH_SCRIPTS = Map.of(
            "CAISHIXIAN", new ChannelScriptSpec(
                    "caishixian_push_shipments.py", "csx-credentials.txt",
                    List.of("CSX_USERNAME", "CSX_PASSWORD", "CSX_SUPPLIER_CODE")),
            "JUFUBAO", new ChannelScriptSpec(
                    "jufubao_push_shipments.py", "jufubao-credentials.txt",
                    List.of("JFUBAO_USERNAME", "JFUBAO_PASSWORD")));

    private final JdbcTemplate jdbc;
    private final ContentAddressedFileStore fileStore;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final PlatformScriptRunner scriptRunner;
    private final TransactionTemplate requiresNew;

    private final Path scriptsDir;
    private final Path credentialsDir;
    private final Path workDir;
    private final Duration scriptTimeout;
    private final Duration pushStaleTimeout;

    SourceReturnPushService(
            JdbcTemplate jdbc,
            ContentAddressedFileStore fileStore,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            PlatformScriptRunner scriptRunner,
            PlatformTransactionManager transactionManager,
            @Value("${app.platform-pull.scripts-dir:${java.io.tmpdir}/zimu-platform-pull-scripts}") String scriptsDir,
            @Value("${app.platform-pull.credentials-dir:${java.io.tmpdir}/zimu-platform-pull-credentials}") String credentialsDir,
            @Value("${app.platform-pull.work-dir:${java.io.tmpdir}/zimu-platform-pull}") String workDir,
            @Value("${app.platform-pull.script-timeout:PT10M}") Duration scriptTimeout,
            @Value("${app.platform-pull.push-stale-timeout:PT15M}") Duration pushStaleTimeout) {
        this.jdbc = jdbc;
        this.fileStore = fileStore;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.scriptRunner = scriptRunner;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.scriptsDir = Path.of(scriptsDir);
        this.credentialsDir = Path.of(credentialsDir);
        this.workDir = Path.of(workDir);
        this.scriptTimeout = scriptTimeout;
        this.pushStaleTimeout = pushStaleTimeout;
    }

    /**
     * 人工触发推送回填文件到来源平台（A2：三段式，脚本执行期间不持有数据库事务/连接）。
     *
     * @param exportId 回填文件 ID
     */
    public Map<String, Object> push(long exportId, CommandContext context) {
        long started = System.nanoTime();
        PushIntent intent = claimPush(exportId, context);
        try {
            Map<String, Object> outcome = runPushScript(intent, context);
            return completePush(intent, outcome, context, started);
        } catch (RuntimeException ex) {
            failPush(intent, ex, context, started);
            throw ex;
        }
    }

    /**
     * 阶段一（REQUIRES_NEW）：读取回填文件与渠道，并原子抢占 PUSHING 意图。
     * 抢占条件（A3）：NOT_PUSHED/FAILED 可抢占；PUSHING 仅当 push_started_at 为空或早于
     * {@code app.platform-pull.push-stale-timeout}（默认 15 分钟）——进程崩溃遗留的 PUSHING
     * 超时后允许重新抢占，不再永久卡死。
     */
    PushIntent claimPush(long exportId, CommandContext context) {
        return requiresNew.execute(status -> {
            ReturnExportInfo info = load(exportId);
            if (info == null) {
                throw BusinessException.notFound("来源回填文件不存在: " + exportId);
            }
            String channel = info.channel();
            if (!PUSH_SCRIPTS.containsKey(channel)) {
                throw BusinessException.badRequest("PUSH_CHANNEL_UNSUPPORTED",
                        "该来源渠道（" + channel + "）回传尚未接入，当前支持彩食鲜、聚福宝");
            }
            // 幂等闸门：NOT_PUSHED / FAILED / 陈旧的 PUSHING 允许抢占；新鲜的 PUSHING / SUCCESS 拒绝。
            int claimed = jdbc.update(CLAIM_SQL, context.operator(), exportId, pushStaleTimeout.toSeconds());
            if (claimed != 1) {
                String pushStatus = jdbc.queryForObject(
                        "SELECT push_status FROM app.source_return_exports WHERE id=?", String.class, exportId);
                throw BusinessException.conflict("PUSH_ALREADY_CLAIMED",
                        "该回填文件当前推送状态为 " + pushStatus + "，不能重复推送");
            }
            return new PushIntent(exportId, info.fileRef(), channel, info.importBatchId());
        });
    }

    /**
     * 阶段二（无事务）：事务外执行平台推送脚本，不持有任何数据库连接。
     * 脚本只做平台登录 + 提交，返回 {"success":bool,"outcome":"accepted|rejected|unknown","platform_ref"?,"code"?,"message"?}。
     * 退出码非零但仍产出结果 JSON 时（平台明确拒绝）照常解析；未产出/不可解析/超时按「结果未知」处理（A4）。
     */
    private Map<String, Object> runPushScript(PushIntent intent, CommandContext context) {
        byte[] fileBytes;
        try {
            fileBytes = fileStore.read(intent.fileRef());
        } catch (Exception ex) {
            throw new IllegalStateException("读取回填文件失败: " + ex.getMessage(), ex);
        }
        ChannelScriptSpec spec = PUSH_SCRIPTS.get(intent.channel());
        Path outDir;
        try {
            outDir = scriptRunner.createTempDirectory(workDir, "push-" + intent.channel().toLowerCase() + "-");
        } catch (IOException ex) {
            throw new IllegalStateException("创建工作目录失败: " + ex.getMessage(), ex);
        }
        try {
            return runPushScriptInDir(intent, spec, fileBytes, outDir);
        } finally {
            // A6：payload.json 含收货人电话/地址（PII），执行后必须清理临时目录。
            scriptRunner.deleteRecursively(outDir);
        }
    }

    private Map<String, Object> runPushScriptInDir(
            PushIntent intent, ChannelScriptSpec spec, byte[] fileBytes, Path outDir) {
        Path script = scriptsDir.resolve(spec.scriptName());
        if (!Files.isRegularFile(script)) {
            throw BusinessException.badRequest("PUSH_SCRIPT_MISSING", "推送脚本不存在: " + script);
        }
        Path credentialFile = credentialsDir.resolve(spec.credentialFile());
        if (!Files.isRegularFile(credentialFile)
                && spec.credentialEnvNames().stream().noneMatch(key -> System.getenv(key) != null)) {
            throw BusinessException.badRequest("PUSH_CREDENTIALS_MISSING",
                    "凭据缺失: " + credentialFile + "（请配置 " + spec.credentialEnvNames()
                            + " 环境变量，或先在 data-local/ 配置对应凭据）");
        }

        Path input;
        Path output = outDir.resolve("result.json");
        try {
            input = outDir.resolve("return.xlsx");
            Files.write(input, fileBytes);
        } catch (IOException ex) {
            throw new IllegalStateException("写回填临时文件失败: " + ex.getMessage(), ex);
        }

        List<String> command;
        if ("CAISHIXIAN".equals(intent.channel())) {
            command = new ArrayList<>(List.of(
                    "python3", script.toString(), "--file", input.toString(), "--out", output.toString()));
        } else {
            // 聚福宝：Java 侧（POI）解析回填 xlsx → 结构化行 JSON → 脚本只做登录 + multi-send。
            Path payload = outDir.resolve("payload.json");
            try {
                Files.writeString(payload, writeJson(parseReturnRows(input)), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("解析聚福宝回填文件失败: " + ex.getMessage(), ex);
            }
            command = new ArrayList<>(List.of(
                    "python3", script.toString(), "--payload", payload.toString(), "--out", output.toString()));
        }
        log.info("执行平台回传: {}", command);
        Map<String, String> env;
        try {
            env = scriptRunner.readCredentials(credentialFile, spec.credentialEnvNames());
        } catch (IOException ex) {
            throw new IllegalStateException("读取凭据失败: " + ex.getMessage(), ex);
        }
        PlatformScriptRunner.ScriptExecution exec = scriptRunner.run(command, env, scriptTimeout);

        Map<String, Object> parsed = null;
        if (Files.isRegularFile(output)) {
            try {
                parsed = objectMapper.readValue(
                        Files.readString(output, StandardCharsets.UTF_8),
                        new TypeReference<Map<String, Object>>() {});
            } catch (IOException ignored) {
                // 半成品/损坏结果文件：按未知处理
            }
        }
        if (parsed == null) {
            parsed = new LinkedHashMap<>();
            parsed.put("success", false);
            parsed.put("outcome", "unknown");
            parsed.put("code", "SCRIPT_ERROR");
            parsed.put("message", exec.timedOut()
                    ? "回传脚本执行超时（" + scriptTimeout + "），结果未知"
                    : "回传脚本异常退出（exit=" + exec.exitCode() + "），未产出结果文件: "
                            + tail(exec.output(), 300));
        }
        parsed.put("script_output", tail(exec.output(), 300));
        return parsed;
    }

    /**
     * 阶段三（REQUIRES_NEW）：把脚本结果回写为 SUCCESS / FAILED（含操作人与错误明细），
     * 并同步批次内 shipment 的「已回传」同步状态（P1），最后返回带推送状态与提示 message 的视图。
     * A9：FAILED 回写同样记录 pushed_by，push_started_at 保留。
     */
    private Map<String, Object> completePush(PushIntent intent, Map<String, Object> outcome, CommandContext context, long started) {
        return requiresNew.execute(status -> {
            boolean success = Boolean.TRUE.equals(outcome.get("success"));
            String message;
            if (success) {
                jdbc.update(SUCCESS_SQL, context.operator(), outcome.get("platform_ref"), intent.exportId());
                // P1：同一 REQUIRES_NEW 事务内把批次关联 shipment 置 SYNCED（幂等防重见 SYNC_SHIPMENTS_SQL）。
                markSourceShipmentsSynced(intent);
                message = "推送成功";
            } else {
                Map<String, Object> error = failureError(outcome);
                jdbc.update(FAILED_SQL, writeJson(error), context.operator(), intent.exportId());
                // P1：失败（rejected/unknown/脚本错误）→ 批次关联 shipment 置 SYNC_FAILED 并记录平台错误。
                markSourceShipmentsSyncFailed(intent, outcome);
                message = String.valueOf(error.get("message"));
            }
            int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
            audit(context, intent.channel(), intent.exportId(), success, outcome, latencyMs);
            Map<String, Object> view = view(intent.exportId());
            view.put("message", message);
            return view;
        });
    }

    /** P1：推送成功 → 批次内 shipment 同步状态置 SYNCED（落点与幂等说明见 SYNC_SHIPMENTS_SQL）。 */
    private void markSourceShipmentsSynced(PushIntent intent) {
        jdbc.update(SYNC_SHIPMENTS_SQL, intent.channel(), intent.importBatchId());
    }

    /** P1：推送失败 → 批次内 shipment 同步状态置 SYNC_FAILED，记录平台/脚本 code 与 message。 */
    private void markSourceShipmentsSyncFailed(PushIntent intent, Map<String, Object> outcome) {
        String code = String.valueOf(outcome.getOrDefault("code", "PUSH_FAILED")).trim();
        if (code.isEmpty()) {
            code = "PUSH_FAILED";
        }
        String message = String.valueOf(outcome.getOrDefault("message", "推送失败"));
        // last_error_code 为 VARCHAR(64)：平台/脚本 code 一律截断防超长。
        jdbc.update(SYNC_FAIL_SHIPMENTS_SQL, truncate(code, 64), message, intent.channel(), intent.importBatchId());
    }

    /** A4：失败明细——平台明确拒绝保留 code/message；结果未知写 unknown_outcome 并提示人工核实。 */
    private Map<String, Object> failureError(Map<String, Object> outcome) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", String.valueOf(outcome.getOrDefault("code", "")));
        error.put("message", String.valueOf(outcome.getOrDefault("message", "推送失败")));
        // P2：聚福宝 multi-send 平台无逐行响应结构（全有全无受理），失败时透出平台原始响应全文
        // （code/message/request_id，脚本写入结果 JSON 的 platform_response 字段），
        // 供前端/人工按 request_id 在平台核对，避免只取 message 截断丢失关键字段。
        Object platformResponse = outcome.get("platform_response");
        if (platformResponse != null) {
            error.put("platform_response", platformResponse);
        }
        if ("unknown".equals(outcome.get("outcome"))) {
            error.put("unknown_outcome", true);
            error.put("message", "结果未知，请到平台核实是否已受理后再决定是否重推");
        }
        return error;
    }

    /** 脚本异常兜底（REQUIRES_NEW）：把 PUSHING 回写为 FAILED，避免进程崩溃/异常遗留卡死（配合 A3 超时回收）。 */
    private void failPush(PushIntent intent, RuntimeException ex, CommandContext context, long started) {
        try {
            requiresNew.executeWithoutResult(status -> {
                Map<String, Object> error = Map.of(
                        "code", "SCRIPT_ERROR",
                        "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                jdbc.update(FAILED_SQL, writeJson(error), context.operator(), intent.exportId());
                // P1：脚本错误同样把批次关联 shipment 置 SYNC_FAILED（记录 SCRIPT_ERROR 明细）。
                markSourceShipmentsSyncFailed(intent, error);
            });
        } catch (RuntimeException writeBackFailure) {
            log.error("推送失败状态回写失败: exportId={}", intent.exportId(), writeBackFailure);
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        audit(context, intent.channel(), intent.exportId(), false,
                Map.of("code", "SCRIPT_ERROR", "message", String.valueOf(ex.getMessage())), latencyMs);
    }

    /** 读取回填文件 + 来源渠道 + 来源导入批次 id（P1 同步 shipment_syncs 的关联键）。 */
    private ReturnExportInfo load(long exportId) {
        List<ReturnExportInfo> rows = jdbc.query(
                """
                SELECT sre.file_ref, ib.source_channel, sre.import_batch_id
                FROM app.source_return_exports sre
                JOIN app.import_batches ib ON ib.id=sre.import_batch_id
                WHERE sre.id=?
                """,
                (rs, rowNum) -> new ReturnExportInfo(
                        rs.getString("file_ref"), rs.getString("source_channel"), rs.getLong("import_batch_id")),
                exportId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 读回填文件视图（含推送状态）。 */
    private Map<String, Object> view(long exportId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT id, batch_no, push_status, pushed_at, pushed_by, push_platform_ref,
                       push_error::text push_error
                FROM app.source_return_exports sre
                JOIN app.import_batches ib ON ib.id=sre.import_batch_id
                WHERE sre.id=?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("batch_no", rs.getString("batch_no"));
                    m.put("push_status", rs.getString("push_status"));
                    m.put("pushed_at", rs.getTimestamp("pushed_at") == null
                            ? null : rs.getTimestamp("pushed_at").toInstant());
                    m.put("pushed_by", rs.getString("pushed_by"));
                    m.put("push_platform_ref", rs.getString("push_platform_ref"));
                    m.put("push_error", parseJson(rs.getString("push_error")));
                    return m;
                },
                exportId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /**
     * 用 POI 解析聚福宝来源回填 xlsx，产出结构化行（脚本 multi-send 输入）。
     *
     * <p>聚福宝模板列（拉取表头 + 回填列）：主单号/拆单号/收货人姓名/收货人电话/收货地址/
     * 商品ID/商品名称/商品规格ID/数量/发货数量/快递公司/快递单号。脚本只需这 12 个字段。
     *
     * <p>A7 数量校验：发货数量列缺失或为空时拒绝整单推送（不静默回退「数量」——部分发货场景
     * 回退会把下单量当发货量）；数量必须是正整数（>0、无小数），否则拒绝。
     */
    private List<Map<String, Object>> parseReturnRows(Path xlsx) {
        DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(xlsx)))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return rows;
            }
            Map<Integer, String> headers = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                headers.put(cell.getColumnIndex(), formatter.formatCellValue(cell).trim());
            }
            boolean shippedColumnPresent = headers.containsValue("发货数量");
            if (!shippedColumnPresent) {
                throw BusinessException.badRequest("PUSH_QUANTITY_COLUMN_MISSING",
                        "回填文件缺少「发货数量」列，无法确定实际发货数量，拒绝推送（请勿回退「数量」列）");
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                headers.forEach((index, name) -> {
                    Cell cell = row.getCell(index);
                    cells.put(name, cell == null ? "" : formatter.formatCellValue(cell).trim());
                });
                if (cells.values().stream().allMatch(String::isBlank)) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("main_order_id", first(cells, "主单号", "主订单编号"));
                out.put("sub_order_id", first(cells, "拆单号", "子订单编号"));
                out.put("receipt_username", value(cells, "收货人姓名"));
                out.put("receipt_phone_number", value(cells, "收货人电话", "收货人手机号"));
                out.put("address_detail", value(cells, "收货地址"));
                out.put("product_id", first(cells, "商品ID", "商品编号", "商品条码"));
                out.put("product_name", value(cells, "商品名称"));
                out.put("product_sku_id", first(cells, "商品规格ID", "商品编码"));
                String num = value(cells, "发货数量");
                out.put("num", validateShippedQuantity(num, value(cells, "主单号", "拆单号")));
                out.put("logistics_number", first(cells, "快递单号", "物流单号"));
                out.put("carrier_name", first(cells, "快递公司", "物流公司"));
                rows.add(out);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("解析聚福宝回填文件失败: " + ex.getMessage(), ex);
        }
        return rows;
    }

    /** A7：发货数量必须为正整数（无小数）；缺失/非法拒绝推送，不静默回退或置 0。 */
    private String validateShippedQuantity(String raw, String orderRef) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw BusinessException.badRequest("PUSH_QUANTITY_INVALID",
                    "「发货数量」为空（订单 " + (orderRef.isEmpty() ? "?" : orderRef) + "），拒绝推送");
        }
        String reject = "「发货数量」必须为正整数（>0，无小数），实际为 '" + trimmed + "'（订单 "
                + (orderRef.isEmpty() ? "?" : orderRef) + "），拒绝推送";
        try {
            BigDecimal value = new BigDecimal(trimmed);
            if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0
                    || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                throw BusinessException.badRequest("PUSH_QUANTITY_INVALID", reject);
            }
            value.longValueExact();
        } catch (NumberFormatException ex) {
            throw BusinessException.badRequest("PUSH_QUANTITY_INVALID", reject);
        }
        return trimmed;
    }

    private static String first(Map<String, String> cells, String... keys) {
        for (String key : keys) {
            String v = cells.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String value(Map<String, String> cells, String... keys) {
        return first(cells, keys);
    }

    private void audit(
            CommandContext context,
            String channel,
            long exportId,
            boolean success,
            Map<String, Object> outcome,
            int latencyMs) {
        auditLogService.record(PlatformScriptRunner.baseAuditCommand(context)
                .service("platform-push." + channel)
                .operation("source-return-export.push")
                .requestPayload(Map.of("export_id", exportId))
                .responsePayload(outcome)
                .httpStatus(200)
                .businessCode(success ? "OK" : "PUSH_FAILED")
                .latencyMs(latencyMs));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private Object parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return text;
        }
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 推送意图（阶段一提交后传入阶段二/三）；包可见便于测试。 */
    record PushIntent(long exportId, String fileRef, String channel, long importBatchId) {}

    record ReturnExportInfo(String fileRef, String channel, long importBatchId) {}

    /** 推送渠道脚本规格。 */
    private record ChannelScriptSpec(String scriptName, String credentialFile, List<String> credentialEnvNames) {}
}
