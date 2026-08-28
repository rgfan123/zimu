package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.PlatformScriptRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * A2（外部脚本不持有事务）/ A3（PUSHING 超时回收）/ A4（结果未知格）/ A6（PII 临时目录清理）
 * 单元测试；Mockito 风格，不启动 Spring 上下文。
 */
class SourceReturnPushServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContentAddressedFileStore fileStore = mock(ContentAddressedFileStore.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final PlatformScriptRunner scriptRunner = mock(PlatformScriptRunner.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus txStatus = mock(TransactionStatus.class);

    private final CommandContext context = new CommandContext("req", "trace", "ops");

    private SourceReturnPushService service(Path tempDir) {
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        Path scripts = tempDir.resolve("scripts");
        Path credentials = tempDir.resolve("credentials");
        try {
            Files.createDirectories(scripts);
            Files.createDirectories(credentials);
            Files.writeString(scripts.resolve("caishixian_push_shipments.py"), "#!/usr/bin/env python3\n");
            Files.writeString(credentials.resolve("csx-credentials.txt"), "CSX_USERNAME=u\nCSX_PASSWORD=p\n");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return new SourceReturnPushService(
                jdbc, fileStore, auditLogService, new ObjectMapper(), scriptRunner, txManager,
                scripts.toString(), credentials.toString(), tempDir.resolve("work").toString(),
                Duration.ofMinutes(10), Duration.ofMinutes(15));
    }

    private void stubClaim() {
        when(jdbc.<SourceReturnPushService.ReturnExportInfo>query(
                startsWith("SELECT sre.file_ref"), any(RowMapper.class), anyLong()))
                .thenReturn(List.of(new SourceReturnPushService.ReturnExportInfo(
                        "ref-1", "CAISHIXIAN", 42L, "caishixian-deliver-2026-08-28.xlsx", 1, "1")));
        when(jdbc.update(eq(SourceReturnPushService.CLAIM_SQL), any(), any(), any())).thenReturn(1);
    }

    private void stubView(String pushStatus, Object pushError) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "7");
        row.put("batch_no", "IMP-1");
        row.put("push_status", pushStatus);
        row.put("pushed_at", null);
        row.put("pushed_by", "ops");
        row.put("push_platform_ref", null);
        row.put("push_error", pushError);
        when(jdbc.<Map<String, Object>>query(contains("batch_no"), any(RowMapper.class), anyLong()))
                .thenReturn(List.of(row));
    }

    @Test
    void scriptRunsBetweenClaimAndCompletionTransactions(@TempDir Path tempDir) throws Exception {
        stubClaim();
        when(fileStore.read("ref-1")).thenReturn(new byte[] {1, 2, 3});
        Path outDir = Files.createDirectories(tempDir.resolve("work").resolve("push-caishixian-1"));
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), anyList()))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(anyList(), anyMap(), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 0, "ok"));
        Files.writeString(outDir.resolve("result.json"),
                "{\"success\": true, \"outcome\": \"accepted\", \"platform_ref\": \"REQ-1\", \"message\": \"回传成功\"}",
                StandardCharsets.UTF_8);
        when(jdbc.update(eq(SourceReturnPushService.SUCCESS_SQL), any(), any(), any())).thenReturn(1);
        stubView("SUCCESS", null);

        Map<String, Object> response = service(tempDir).push(7L, context);

        // A2 核心：抢占（REQUIRES_NEW）→ 脚本（事务外）→ 回写（REQUIRES_NEW），脚本执行期间无数据库事务。
        InOrder order = inOrder(txManager, scriptRunner);
        order.verify(txManager).getTransaction(any());
        order.verify(scriptRunner).run(anyList(), anyMap(), any(Duration.class));
        order.verify(txManager).getTransaction(any());
        // A6：临时目录（含回填 xlsx）执行后清理
        verify(scriptRunner).deleteRecursively(outDir);
        assertThat(response).containsEntry("push_status", "SUCCESS").containsEntry("message", "推送成功");
    }

    @Test
    void stalePushingIsReclaimable(@TempDir Path tempDir) {
        // A3：PUSHING 超时后允许重新抢占（模拟 UPDATE 命中陈旧的 PUSHING 行）
        stubClaim();
        when(jdbc.update(eq(SourceReturnPushService.CLAIM_SQL), any(), any(), any())).thenReturn(1);

        SourceReturnPushService.PushIntent intent = service(tempDir).claimPush(7L, context);

        assertThat(intent.exportId()).isEqualTo(7L);
        assertThat(intent.channel()).isEqualTo("CAISHIXIAN");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(), any(), any());
        // 抢占 SQL 必须包含超时回收条件（push_started_at 早于 stale timeout 可抢占）
        assertThat(sql.getValue())
                .contains("push_started_at <")
                .contains("make_interval")
                .contains("push_status IN ('NOT_PUSHED','FAILED')");
    }

    @Test
    void freshPushingIsRejectedWithConflict(@TempDir Path tempDir) {
        // A3 反向：未超时的 PUSHING 不能被抢占
        stubClaim();
        when(jdbc.update(eq(SourceReturnPushService.CLAIM_SQL), any(), any(), any())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), anyLong())).thenReturn("PUSHING");

        assertThatThrownBy(() -> service(tempDir).claimPush(7L, context))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("PUSH_ALREADY_CLAIMED"));
    }

    @Test
    void unknownOutcomePersistsManualVerificationHint(@TempDir Path tempDir) throws Exception {
        // A4：脚本异常退出但结果文件标注 outcome=unknown → push_error 写 unknown_outcome + 响应提示人工核实
        stubClaim();
        when(fileStore.read("ref-1")).thenReturn(new byte[] {1, 2, 3});
        Path outDir = Files.createDirectories(tempDir.resolve("work").resolve("push-caishixian-2"));
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), anyList()))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(anyList(), anyMap(), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 1, "boom"));
        Files.writeString(outDir.resolve("result.json"),
                "{\"success\": false, \"outcome\": \"unknown\", \"code\": \"SCRIPT_ERROR\", \"message\": \"网络中断\"}",
                StandardCharsets.UTF_8);
        stubView("FAILED", Map.of("code", "SCRIPT_ERROR", "message", "网络中断"));

        Map<String, Object> response = service(tempDir).push(7L, context);

        ArgumentCaptor<Object> errorJson = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(eq(SourceReturnPushService.FAILED_SQL), errorJson.capture(), eq("ops"), eq(7L));
        assertThat((String) errorJson.getValue()).contains("\"unknown_outcome\":true");
        assertThat(response)
                .containsEntry("push_status", "FAILED")
                .containsEntry("message", "结果未知，请到平台核实是否已受理后再决定是否重推");
        // A6：即使失败也清理临时目录（payload.json 含 PII）
        verify(scriptRunner).deleteRecursively(outDir);
    }

    // ---------- P1：回传结果与订单/发货同步状态同步 ----------

    @Test
    void successfulPushMarksBatchShipmentsSynced(@TempDir Path tempDir) throws Exception {
        // P1：推送成功（平台已受理）→ 同一 REQUIRES_NEW 事务内把批次内 PENDING/SYNC_FAILED 的
        // shipment_syncs 置 SYNCED（synced_at=CURRENT_TIMESTAMP），last_error 清空（防重不碰 SYNCED 行）。
        stubClaim();
        when(fileStore.read("ref-1")).thenReturn(new byte[] {1, 2, 3});
        Path outDir = Files.createDirectories(tempDir.resolve("work").resolve("push-caishixian-3"));
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), anyList()))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(anyList(), anyMap(), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 0, "ok"));
        Files.writeString(outDir.resolve("result.json"),
                "{\"success\": true, \"outcome\": \"accepted\", \"platform_ref\": \"REQ-1\", \"message\": \"回传成功\"}",
                StandardCharsets.UTF_8);
        when(jdbc.update(eq(SourceReturnPushService.SUCCESS_SQL), any(), any(), any())).thenReturn(1);
        stubView("SUCCESS", null);

        service(tempDir).push(7L, context);

        // 成功分支：SUCCESS 回写后，按（渠道, 来源批次 id）把批次关联 shipment 置 SYNCED。
        verify(jdbc).update(eq(SourceReturnPushService.SYNC_SHIPMENTS_SQL), eq("CAISHIXIAN"), eq(42L));
        // 成功分支绝不写 SYNC_FAILED。
        verify(jdbc, never()).update(eq(SourceReturnPushService.SYNC_FAIL_SHIPMENTS_SQL), any(), any(), any(), any());
    }

    @Test
    void rejectedPushMarksShipmentsSyncFailedAndCarriesPlatformResponse(@TempDir Path tempDir) throws Exception {
        // P1 失败分支 + P2：平台明确拒绝 → push_error 并入平台原始响应全文（platform_response 含
        // request_id，不再只截取 message），且批次关联 shipment 置 SYNC_FAILED 记录平台 code/message。
        stubClaim();
        when(fileStore.read("ref-1")).thenReturn(new byte[] {1, 2, 3});
        Path outDir = Files.createDirectories(tempDir.resolve("work").resolve("push-caishixian-4"));
        when(scriptRunner.createTempDirectory(any(Path.class), anyString())).thenReturn(outDir);
        when(scriptRunner.readCredentials(any(Path.class), anyList()))
                .thenReturn(Map.of("CSX_USERNAME", "u", "CSX_PASSWORD", "p"));
        when(scriptRunner.run(anyList(), anyMap(), any(Duration.class)))
                .thenReturn(new PlatformScriptRunner.ScriptExecution(false, 1, "rejected"));
        Files.writeString(outDir.resolve("result.json"),
                "{\"success\": false, \"outcome\": \"rejected\", \"code\": \"InvalidArgument\","
                        + " \"message\": \"快递单号只允许包含字母和数字\", \"request_id\": \"REQ-42\","
                        + " \"platform_response\": {\"code\": \"InvalidArgument\","
                        + " \"message\": \"快递单号只允许包含字母和数字\", \"request_id\": \"REQ-42\"}}",
                StandardCharsets.UTF_8);
        stubView("FAILED", Map.of("code", "InvalidArgument", "message", "快递单号只允许包含字母和数字"));

        service(tempDir).push(7L, context);

        // push_error 透出平台原始响应全文（含 request_id）。
        ArgumentCaptor<Object> errorJson = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(eq(SourceReturnPushService.FAILED_SQL), errorJson.capture(), eq("ops"), eq(7L));
        assertThat((String) errorJson.getValue())
                .contains("\"platform_response\"")
                .contains("\"request_id\":\"REQ-42\"");
        // P1 失败分支：批次关联 shipment 置 SYNC_FAILED + 平台 code/message（P4：FAILED 回写同时记录 pushed_by）。
        verify(jdbc).update(eq(SourceReturnPushService.SYNC_FAIL_SHIPMENTS_SQL),
                eq("InvalidArgument"), eq("快递单号只允许包含字母和数字"), eq("CAISHIXIAN"), eq(42L));
    }

    @Test
    void scriptExceptionFailPushMarksShipmentsSyncFailed(@TempDir Path tempDir) {
        // P1 失败分支（脚本错误兜底）：failPush 的 REQUIRES_NEW 回写同样把批次关联 shipment 置 SYNC_FAILED。
        stubClaim();
        when(fileStore.read("ref-1")).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service(tempDir).push(7L, context))
                .isInstanceOf(IllegalStateException.class);

        verify(jdbc).update(eq(SourceReturnPushService.SYNC_FAIL_SHIPMENTS_SQL),
                eq("SCRIPT_ERROR"), startsWith("读取回填文件失败"), eq("CAISHIXIAN"), eq(42L));
        // 失败路径绝不写 SYNCED。
        verify(jdbc, never()).update(eq(SourceReturnPushService.SYNC_SHIPMENTS_SQL), any(), any());
    }
}
