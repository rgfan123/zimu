package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.order.OrderDraft;
import cn.zimu.fulfillment.order.OrderDraftLine;
import cn.zimu.fulfillment.order.OrderDraftLineRepository;
import cn.zimu.fulfillment.order.OrderDraftRepository;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.card.OrderDraftCardEnqueuer;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CUSTOMER_ORDER 解释结果的订单草稿工厂（票 04）。
 *
 * <p>在同一 Worker 事务内：从模型结构化输出中只接收白名单原始描述，忽略任何内部 ID → 按收货信息
 * 分组创建版本化 OrderDraft 与草稿行 → 确定性 Customer/SKU 候选（唯一命中形成候选，零/多
 * 命中标记待处理）→ 计算缺失项 → 为每个草稿创建恰好一个 ORDER_OPS/OPEN 复核事项。
 * 不同消息相同内容只提示疑似重复，不自动追加、合并、拒绝或删除。
 */
@Component
public class WecomOrderDraftFactory implements OrderDraftFactory {

    public static final String REASON_CODE = "WECOM_ORDER_DRAFT";
    public static final String CASE_TYPE = "WECOM_DRAFT";
    /** 与 V74 共用：草稿候选读取到草稿落库期间持共享锁，伪映射迁移持排他锁。 */
    static final long WECOM_DRAFT_MAPPING_LOCK_KEY = 756426269157L;

    private static final String SKU_CANDIDATE_SQL =
            """
            SELECT s.id sku_id, s.sku_code, p.product_name, s.specification, s.unit,
                   s.fulfillment_provider_id, scs.source_sku_ref, scs.quantity_multiplier
            FROM app.source_channel_skus scs
            JOIN app.skus s ON s.id = scs.sku_id
            JOIN app.products p ON p.id = s.product_id
            WHERE scs.source_channel = 'WECOM' AND scs.active = true
              AND s.active = true
              AND (?::text IS NULL OR scs.source_sku_ref = ?)
              AND (?::text IS NULL OR scs.source_product_name = ?)
            ORDER BY scs.id
            """;

    private final OrderDraftRepository drafts;
    private final OrderDraftLineRepository lines;
    private final ReviewCaseRepository cases;
    private final MessageSubmissionRepository submissions;
    private final CustomerRepository customers;
    private final CustomerSourceRefRepository customerSourceRefs;
    private final JdbcTemplate jdbc;
    private final OrderDraftCardEnqueuer cardEnqueuer;

    public WecomOrderDraftFactory(
            OrderDraftRepository drafts,
            OrderDraftLineRepository lines,
            ReviewCaseRepository cases,
            MessageSubmissionRepository submissions,
            CustomerRepository customers,
            CustomerSourceRefRepository customerSourceRefs,
            JdbcTemplate jdbc,
            OrderDraftCardEnqueuer cardEnqueuer) {
        this.drafts = drafts;
        this.lines = lines;
        this.cases = cases;
        this.submissions = submissions;
        this.customers = customers;
        this.customerSourceRefs = customerSourceRefs;
        this.jdbc = jdbc;
        this.cardEnqueuer = cardEnqueuer;
    }

    @Override
    @Transactional
    public List<Long> createDrafts(MessageSubmission submission, InterpretationResult result) {
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(" + WECOM_DRAFT_MAPPING_LOCK_KEY + ")");
        Map<String, Object> output = result.structuredOutput() == null ? Map.of() : result.structuredOutput();

        // 票 06：只有系统草稿号或稳定父消息 ID 才追加到既有草稿；企微引用类型/内容只作证据，
        // 禁止按文字相似或时间窗口合并。目标草稿已关闭时走正常新建路径，绝不吞单。
        Optional<OrderDraft> appendTarget = explicitAppendTarget(output);
        if (appendTarget.isPresent() && appendTarget.get().getStatus() == OrderDraft.Status.OPEN) {
            appendToDraft(submission, result, output, appendTarget.get());
            submission.setStatus(MessageSubmission.Status.DRAFTED);
            submissions.save(submission);
            return List.of();
        }

        List<Long> created = new ArrayList<>();
        List<Map<String, Object>> itemGroups = groupByReceiver(output);
        int seq = nextSeq(submission.getId());
        for (Map<String, Object> group : itemGroups) {
            OrderDraft draft = newDraft(submission, output, group, seq++);
            List<OrderDraftLine> draftLineList = new ArrayList<>();
            int lineNo = 1;
            for (Map<String, Object> item : groupItems(group)) {
                draftLineList.add(newLine(item, lineNo++));
            }
            // OrderDraft initializes its @Version field, so Spring Data treats it as detached and
            // save() may return a managed copy. Use that copy so child rows and the ReviewCase get
            // the generated draft id instead of a null foreign key.
            draft = drafts.save(draft);
            for (OrderDraftLine draftLine : draftLineList) {
                draftLine.setOrderDraftId(draft.getId());
                lines.save(draftLine);
            }
            draft.setMissingFields(missingFields(draft, draftLineList));
            // saveAndFlush 而非 save：revision 是 @Version，只在 flush 时递增。
            // 卡片入队走 JdbcTemplate（不触发 Hibernate auto-flush），若此处不 flush，
            // 卡片行会记下「递增前」的 revision，事务提交后草稿 revision 前进一位，
            // OrderDraftCardRunner 便判定「草稿已被修订取代」→ 卡片 100% 发不出去。
            // 该 off-by-one 在 missingFields 非空（实体被弄脏）时必现，即「需补资料」的
            // 确认卡片——恰恰是这个功能存在的意义。实测：修复前卡片恒 SUPERSEDED 从未投递过。
            draft = drafts.saveAndFlush(draft);
            cases.save(buildCase(submission, result, output, draft, draftLineList));
            cardEnqueuer.enqueue(draft.getId(), draft.getRevision());
            created.add(draft.getId());
        }
        if (!created.isEmpty()) {
            submission.setStatus(MessageSubmission.Status.DRAFTED);
            submissions.save(submission);
        }
        return created;
    }

    // ------------------------------------------------------------------
    // 新建草稿
    // ------------------------------------------------------------------

    private OrderDraft newDraft(
            MessageSubmission submission, Map<String, Object> output, Map<String, Object> group, int seq) {
        Map<String, Object> receiver = groupReceiver(group);
        OrderDraft draft = new OrderDraft();
        draft.setDraftNo("OD-" + submission.getId() + "-" + seq);
        draft.setSubmissionId(submission.getId());
        draft.setSourceOrderNo("WECOM-" + submission.getSubmissionNo() + "-" + seq);
        draft.setCustomerNameRaw(customerRaw(output));
        draft.setCustomerCandidates(customerCandidates(output));
        draft.setReceiverName(receiver == null ? null : stringValue(receiver.get("name")));
        draft.setReceiverPhone(receiver == null ? null : stringValue(receiver.get("phone")));
        draft.setReceiverAddress(receiver == null ? null : stringValue(receiver.get("address")));
        draft.setSettlementMethod(stringValue(output.get("settlement_method")));
        draft.setSettlementTime(instantValue(output.get("settlement_time")));
        return draft;
    }

    private OrderDraftLine newLine(Map<String, Object> item, int lineNo) {
        OrderDraftLine line = new OrderDraftLine();
        line.setLineNo(lineNo);
        line.setProductNameRaw(stringValue(item.get("product")));
        line.setSpecRaw(stringValue(item.get("spec")));
        line.setUnitRaw(stringValue(item.get("unit")));
        line.setQuantity(quantity(item.get("quantity")));
        line.setSkuCandidates(skuCandidates(stringValue(item.get("source_sku_ref")), stringValue(item.get("product"))));
        return line;
    }

    /** 模型原值中的客户描述：字符串或 {name} 对象。 */
    private static String customerRaw(Map<String, Object> output) {
        Object customer = output.get("customer");
        if (customer instanceof Map<?, ?> map) {
            return stringValue(map.get("name"));
        }
        return stringValue(customer);
    }

    /** 确定性客户候选只按来源客户引用命中；模型输出中的渠道身份永远不是可信绑定依据。 */
    private List<Map<String, Object>> customerCandidates(Map<String, Object> output) {
        Set<Long> matchedIds = new LinkedHashSet<>();
        String customerRef = stringValue(output.get("customer_ref"));
        if (customerRef != null && !customerRef.isBlank()) {
            customerSourceRefs
                    .findBySourceChannelAndSourceCustomerRef(SourceChannel.WECOM, customerRef.trim())
                    .ifPresent(mapping -> matchedIds.add(mapping.getCustomerId()));
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Long customerId : matchedIds) {
            customers
                    .findById(customerId)
                    .filter(customer -> customer.getDataScope() == DataScope.BUSINESS)
                    .filter(customer -> customer.getStatus() == CustomerStatus.ACTIVE)
                    .ifPresent(customer -> candidates.add(Map.of(
                            "customer_id", String.valueOf(customer.getId()),
                            "customer_code", customer.getCustomerCode(),
                            "customer_name", customer.getCustomerName(),
                            "matched_by", "deterministic-mapping")));
        }
        return candidates;
    }

    /**
     * 确定性 SKU 候选：优先按来源 SKU 引用精确匹配，否则按来源商品名称精确匹配；
     * 唯一命中形成候选，零/多命中标记待处理，不自动确认。
     */
    private List<Map<String, Object>> skuCandidates(String sourceSkuRef, String productName) {
        String ref = sourceSkuRef == null || sourceSkuRef.isBlank() ? null : sourceSkuRef.trim();
        String name = productName == null || productName.isBlank() ? null : productName.trim();
        if (ref == null && name == null) {
            return List.of();
        }
        return jdbc.query(
                SKU_CANDIDATE_SQL,
                (rs, row) -> {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("sku_id", String.valueOf(rs.getLong("sku_id")));
                    candidate.put("sku_code", rs.getString("sku_code"));
                    candidate.put("product_name", rs.getString("product_name"));
                    candidate.put("specification", rs.getString("specification"));
                    candidate.put("unit", rs.getString("unit"));
                    candidate.put("provider_id", String.valueOf(rs.getLong("fulfillment_provider_id")));
                    candidate.put("source_sku_ref", rs.getString("source_sku_ref"));
                    candidate.put("quantity_multiplier", rs.getObject("quantity_multiplier", Integer.class));
                    return candidate;
                },
                ref,
                ref,
                name,
                name);
    }

    // ------------------------------------------------------------------
    // 缺失项与疑似重复
    // ------------------------------------------------------------------

    /**
     * 必填项：客户（无唯一候选）、收货、结账、逐行 SKU 与数量。
     *
     * <p>公开供 order 包的人工补充/确认用例复用，保证草稿创建与人工修订后的缺失清单口径一致。
     */
    public static List<String> missingFields(OrderDraft draft, List<OrderDraftLine> draftLines) {
        List<String> missing = new ArrayList<>();
        if (draft.getCustomerCandidates().size() != 1) {
            missing.add("customer");
        }
        if (isBlank(draft.getReceiverName())) {
            missing.add("receiver_name");
        }
        if (isBlank(draft.getReceiverPhone())) {
            missing.add("receiver_phone");
        }
        if (isBlank(draft.getReceiverAddress())) {
            missing.add("receiver_address");
        }
        if (isBlank(draft.getSettlementMethod())) {
            missing.add("settlement_method");
        }
        if (draft.getSettlementTime() == null) {
            missing.add("settlement_time");
        }
        if (draftLines.isEmpty()) {
            missing.add("items");
        }
        for (OrderDraftLine line : draftLines) {
            if (line.getQuantity() == null) {
                missing.add("line_" + line.getLineNo() + "_quantity");
            }
            if (line.getSkuCandidates().size() != 1) {
                missing.add("line_" + line.getLineNo() + "_sku");
            }
        }
        return missing;
    }

    /** 复核事项：白名单 detail，不包含原始协议载荷与内部秘密。 */
    private ReviewCase buildCase(
            MessageSubmission submission,
            InterpretationResult result,
            Map<String, Object> output,
            OrderDraft draft,
            List<OrderDraftLine> draftLines) {
        ReviewCase reviewCase = new ReviewCase();
        reviewCase.setCaseNo("RC-WECOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        reviewCase.setCaseType(CASE_TYPE);
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setResponsibleTeam(IntentRouter.RESPONSIBLE_TEAM);
        reviewCase.setReasonCode(REASON_CODE);
        reviewCase.setOrderDraftId(draft.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message_submission_id", String.valueOf(submission.getId()));
        detail.put("intent", result.intent().name());
        detail.put("provider", result.provider());
        detail.put("model", result.model());
        detail.put("prompt_version", result.promptVersion());
        detail.put("model_output", whitelistedModelOutput(output));
        detail.put("customer_candidates", draft.getCustomerCandidates());
        detail.put("customer_candidates_status", candidateStatus(draft.getCustomerCandidates()));
        List<Map<String, Object>> lineCandidates = new ArrayList<>();
        for (OrderDraftLine line : draftLines) {
            Map<String, Object> lineCandidate = new LinkedHashMap<>();
            lineCandidate.put("line_no", line.getLineNo());
            lineCandidate.put("product_name_raw", line.getProductNameRaw());
            lineCandidate.put("sku_candidates", line.getSkuCandidates());
            lineCandidate.put("sku_candidates_status", candidateStatus(line.getSkuCandidates()));
            lineCandidates.add(lineCandidate);
        }
        detail.put("line_candidates", lineCandidates);
        detail.put("missing_fields", draft.getMissingFields());
        String suspected = suspectedDuplicate(submission.getId(), output);
        if (suspected != null) {
            detail.put("suspected_duplicate_of", suspected);
        }
        reviewCase.setDetail(detail);
        return reviewCase;
    }

    /**
     * ReviewCase 只保存人工复核所需的模型原值。内部 ID、渠道身份、草稿号、秘密及任何未知
     * JSON 字段即使由模型返回也会在这里被丢弃，不能进入公共复核 API。
     */
    private static Map<String, Object> whitelistedModelOutput(Map<String, Object> output) {
        Map<String, Object> safe = new LinkedHashMap<>();
        putText(safe, "customer", customerRaw(output));

        Map<String, Object> rawReceiver = receiver(output);
        if (rawReceiver != null) {
            Map<String, Object> safeReceiver = new LinkedHashMap<>();
            for (String field : List.of("name", "phone", "province", "city", "district", "town", "address")) {
                putText(safeReceiver, field, rawReceiver.get(field));
            }
            if (!safeReceiver.isEmpty()) {
                safe.put("receiver", safeReceiver);
            }
        }

        List<Map<String, Object>> safeItems = new ArrayList<>();
        for (Map<String, Object> rawItem : items(output)) {
            Map<String, Object> safeItem = new LinkedHashMap<>();
            for (String field : List.of("product", "spec", "unit", "quantity", "source_sku_ref")) {
                putText(safeItem, field, rawItem.get(field));
            }
            if (rawItem.get("receiver") instanceof Map<?, ?> rawItemReceiver) {
                Map<String, Object> safeReceiver = new LinkedHashMap<>();
                for (String field : List.of("name", "phone", "province", "city", "district", "town", "address")) {
                    putText(safeReceiver, field, rawItemReceiver.get(field));
                }
                if (!safeReceiver.isEmpty()) {
                    safeItem.put("receiver", safeReceiver);
                }
            }
            safeItems.add(safeItem);
        }
        if (!safeItems.isEmpty()) {
            safe.put("items", safeItems);
        }
        return safe;
    }

    private static void putText(Map<String, Object> target, String field, Object value) {
        String text = stringValue(value);
        if (text != null) {
            target.put(field, text);
        }
    }

    private static String candidateStatus(List<Map<String, Object>> candidates) {
        return candidates.size() == 1 ? "UNIQUE_HIT" : candidates.isEmpty() ? "ZERO_HIT" : "MULTI_HIT";
    }

    /** 不同消息相同内容：只提示疑似重复，不自动合并、拒绝或删除。 */
    private String suspectedDuplicate(long submissionId, Map<String, Object> output) {
        String fingerprint = fingerprint(
                customerRaw(output), receiver(output), groupItems(output));
        if (fingerprint == null) {
            return null;
        }
        List<Map<String, Object>> candidates = jdbc.query(
                """
                SELECT d.id draft_id, d.draft_no, d.customer_name_raw, d.receiver_name, d.receiver_phone, d.receiver_address,
                       l.line_no, l.product_name_raw, l.spec_raw, l.unit_raw, l.quantity
                FROM app.order_drafts d
                LEFT JOIN app.order_draft_lines l ON l.order_draft_id = d.id
                WHERE d.status = 'OPEN' AND d.submission_id <> ?
                ORDER BY d.id DESC
                """,
                (rs, row) -> rowMap(rs),
                submissionId);
        Map<Long, Map<String, Object>> draftsByLine = new LinkedHashMap<>();
        for (Map<String, Object> row : candidates) {
            Long draftId = ((Number) row.get("draft_id")).longValue();
            draftsByLine.computeIfAbsent(draftId, key -> {
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("draft_no", row.get("draft_no"));
                draft.put("customer_name_raw", row.get("customer_name_raw"));
                draft.put("receiver_name", row.get("receiver_name"));
                draft.put("receiver_phone", row.get("receiver_phone"));
                draft.put("receiver_address", row.get("receiver_address"));
                draft.put("lines", new ArrayList<Map<String, Object>>());
                return draft;
            });
            if (row.get("line_no") != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> draftLines =
                        (List<Map<String, Object>>) draftsByLine.get(draftId).get("lines");
                draftLines.add(row);
            }
        }
        for (Map<String, Object> candidate : draftsByLine.values()) {
            String candidateFingerprint = fingerprint(
                    (String) candidate.get("customer_name_raw"),
                    Map.of(
                            "name", Objects.toString(candidate.get("receiver_name"), ""),
                            "phone", Objects.toString(candidate.get("receiver_phone"), ""),
                            "address", Objects.toString(candidate.get("receiver_address"), "")),
                    (List<Map<String, Object>>) candidate.get("lines"));
            if (Objects.equals(candidateFingerprint, fingerprint)) {
                return String.valueOf(candidate.get("draft_no"));
            }
        }
        return null;
    }

    /**
     * 归一化指纹：客户描述 + 收货 + 商品行（排序后），用于疑似重复的确定性判定。
     *
     * <p>每个片段先折叠连续空白并去掉首尾空白，数量按数值归一（2 与 2.000 视为相同），
     * 使同一内容的轻微排版差异也判为疑似重复；不同消息仍各自生成草稿，只提示不合并。
     */
    private static String fingerprint(
            String customerRaw, Map<String, Object> receiver, List<Map<String, Object>> items) {
        List<Map<String, Object>> sortedItems = items.stream()
                .sorted(Comparator.comparing(item -> normalize(item.get("product"))
                        + "|" + normalizeQuantity(item.get("quantity"))))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append(normalize(customerRaw));
        sb.append('|').append(receiver == null ? "" : normalize(receiver.get("name")));
        sb.append('|').append(receiver == null ? "" : normalize(receiver.get("phone")));
        sb.append('|').append(receiver == null ? "" : normalize(receiver.get("address")));
        for (Map<String, Object> item : sortedItems) {
            sb.append('|').append(normalize(itemText(item, "product_name_raw", "product")))
                    .append('~').append(normalize(itemText(item, "spec_raw", "spec")))
                    .append('~').append(normalize(itemText(item, "unit_raw", "unit")))
                    .append('~').append(normalizeQuantity(item.get("quantity")));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 疑似重复的近似归一化：折叠连续空白并去首尾空白。 */
    private static String normalize(Object value) {
        String text = value == null ? "" : value.toString();
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 数量归一化：只接受整数 JSON 数值；非法原值保留作待复核指纹。 */
    private static String normalizeQuantity(Object value) {
        Integer quantity = quantity(value);
        return quantity == null ? normalize(value) : quantity.toString();
    }

    /** 模型输出与数据库行共用同一字段归一化，保证两侧指纹可比。 */
    private static String itemText(Map<String, Object> item, String rawKey, String outputKey) {
        Object value = item.get(rawKey);
        if (value == null) {
            value = item.get(outputKey);
        }
        return value == null ? "" : value.toString();
    }

    private static Map<String, Object> rowMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("draft_id", rs.getLong("draft_id"));
        row.put("draft_no", rs.getString("draft_no"));
        row.put("customer_name_raw", rs.getString("customer_name_raw"));
        row.put("receiver_name", rs.getString("receiver_name"));
        row.put("receiver_phone", rs.getString("receiver_phone"));
        row.put("receiver_address", rs.getString("receiver_address"));
        row.put("line_no", rs.getObject("line_no"));
        row.put("product_name_raw", rs.getString("product_name_raw"));
        row.put("spec_raw", rs.getString("spec_raw"));
        row.put("unit_raw", rs.getString("unit_raw"));
        row.put("quantity", rs.getObject("quantity", Integer.class));
        return row;
    }

    // ------------------------------------------------------------------
    // 结构化输出解析
    // ------------------------------------------------------------------

    /**
     * 按收货快照分组商品行：优先取每行自带的 receiver，缺省使用顶层收货快照；
     * 不同收货人或地址拆分为多个草稿，收货缺失时所有行归入单一草稿。
     */
    private List<Map<String, Object>> groupByReceiver(Map<String, Object> output) {
        List<Map<String, Object>> items = items(output);
        if (items.isEmpty()) {
            return List.of(group(output, receiver(output), items));
        }
        Map<String, Map<String, Object>> receiverByKey = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> receiver = itemReceiver(item, output);
            String key = receiverKey(receiver);
            receiverByKey.putIfAbsent(key, receiver);
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byKey.entrySet()) {
            groups.add(group(output, receiverByKey.get(entry.getKey()), entry.getValue()));
        }
        return groups;
    }

    /** 行级收货快照优先；未携带时回退顶层收货快照。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemReceiver(Map<String, Object> item, Map<String, Object> output) {
        Object value = item.get("receiver");
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            return (Map<String, Object>) map;
        }
        return receiver(output);
    }

    private static Map<String, Object> group(
            Map<String, Object> output, Map<String, Object> receiver, List<Map<String, Object>> items) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("receiver", receiver);
        group.put("items", items);
        return group;
    }

    private static String receiverKey(Map<String, Object> receiver) {
        if (receiver == null) {
            return "";
        }
        return normalize(receiver.get("name")) + "|"
                + normalize(receiver.get("phone")) + "|"
                + normalize(receiver.get("address"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> output) {
        Object value = output.get("items");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                items.add((Map<String, Object>) map);
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receiver(Map<String, Object> output) {
        Object value = output.get("receiver");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, Object> groupReceiver(Map<String, Object> group) {
        Object value = group.get("receiver");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupItems(Map<String, Object> group) {
        Object value = group.get("items");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Integer quantity(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger)) {
            return null;
        }
        java.math.BigInteger raw = value instanceof java.math.BigInteger bigInteger
                ? bigInteger
                : java.math.BigInteger.valueOf(((Number) value).longValue());
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromPositiveJsonInteger(raw);
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException ex) {
            return null;
        }
    }

    private int nextSeq(long submissionId) {
        Long max = jdbc.queryForObject(
                """
                SELECT max(CAST(substring(draft_no from '([0-9]+)$') AS BIGINT))
                FROM app.order_drafts WHERE submission_id = ?
                """,
                Long.class,
                submissionId);
        return (int) (max == null ? 0L : max) + 1;
    }

    // ------------------------------------------------------------------
    // 显式追加
    // ------------------------------------------------------------------

    /**
     * 显式追加目标：结构化输出携带系统草稿号（draft_no），或通道明确提供稳定父消息 ID
     * （parent_message_id）且父提交恰好有一个 OPEN 草稿。企微引用类型/内容不参与判定，
     * 伪造或失效的草稿号一律视为新建请求。
     */
    private Optional<OrderDraft> explicitAppendTarget(Map<String, Object> output) {
        String draftNo = stringValue(output.get("draft_no"));
        if (draftNo != null) {
            return drafts.findByDraftNo(draftNo);
        }
        String parentMessageId = stringValue(output.get("parent_message_id"));
        if (parentMessageId == null) {
            return Optional.empty();
        }
        Long parentSubmissionId = jdbc.query(
                """
                SELECT ms.id FROM app.channel_messages cm
                JOIN app.message_submissions ms ON ms.source_message_id = cm.id
                WHERE cm.message_id = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                parentMessageId);
        if (parentSubmissionId == null) {
            return Optional.empty();
        }
        List<OrderDraft> parentDrafts = drafts.findBySubmissionIdOrderByIdAsc(parentSubmissionId).stream()
                .filter(draft -> draft.getStatus() == OrderDraft.Status.OPEN)
                .toList();
        // 父提交存在多个开放草稿时无法确定目标，宁可新建也不猜测拼单
        return parentDrafts.size() == 1 ? Optional.of(parentDrafts.getFirst()) : Optional.empty();
    }

    /**
     * 把新消息的商品行追加到既有开放草稿：行号续排、重算缺失项、复核事项记录追加证据。
     *
     * <p>收货与结账资料不自动覆盖，仍由人工复核；追加不创建新草稿、不合并消息。
     */
    private void appendToDraft(
            MessageSubmission submission,
            InterpretationResult result,
            Map<String, Object> output,
            OrderDraft appendTarget) {
        OrderDraft draft = drafts.findByIdForUpdate(appendTarget.getId())
                .orElseThrow(() -> new IllegalStateException("追加目标草稿不存在: " + appendTarget.getId()));
        List<OrderDraftLine> existing = lines.findByOrderDraftIdOrderByLineNoAsc(draft.getId());
        int lineNo = existing.stream().mapToInt(OrderDraftLine::getLineNo).max().orElse(0) + 1;
        List<OrderDraftLine> appended = new ArrayList<>();
        for (Map<String, Object> item : items(output)) {
            OrderDraftLine line = newLine(item, lineNo++);
            line.setOrderDraftId(draft.getId());
            lines.save(line);
            appended.add(line);
        }
        List<OrderDraftLine> allLines = new ArrayList<>(existing);
        allLines.addAll(appended);
        draft.setMissingFields(missingFields(draft, allLines));
        // 追加只新增子行时草稿自身属性可能无变化，Hibernate 会跳过 UPDATE 导致 @Version 不递增；
        // 追加仍是草稿内容变更，必须提升版本让复核人员刷新并重新确认。
        jdbc.update(
                """
                UPDATE app.order_drafts
                SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                draft.getId());
        drafts.save(draft);

        List<ReviewCase> openCases = cases.findOpenByOrderDraftId(draft.getId(), ReviewCaseStatus.OPEN);
        if (openCases.size() == 1) {
            ReviewCase reviewCase = openCases.getFirst();
            Map<String, Object> detail = new LinkedHashMap<>(reviewCase.getDetail());
            List<Map<String, Object>> appendEvents = appendEvents(detail);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("message_submission_id", String.valueOf(submission.getId()));
            event.put("submission_no", submission.getSubmissionNo());
            event.put("appended_line_count", appended.size());
            event.put("appended_at", java.time.Instant.now().toString());
            event.put("model_output", whitelistedModelOutput(output));
            appendEvents.add(event);
            detail.put("append_events", appendEvents);
            reviewCase.setDetail(detail);
            cases.save(reviewCase);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> appendEvents(Map<String, Object> detail) {
        Object value = detail.get("append_events");
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object event : list) {
            if (event instanceof Map<?, ?> map) {
                events.add((Map<String, Object>) map);
            }
        }
        return events;
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static String stringValue(Object value) {
        if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static java.time.Instant instantValue(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return java.time.Instant.parse(text);
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }
}
