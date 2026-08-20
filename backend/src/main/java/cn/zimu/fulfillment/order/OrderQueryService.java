package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEvent;
import cn.zimu.fulfillment.common.event.OrderEventRepository;
import cn.zimu.fulfillment.common.version.OrderVersion;
import cn.zimu.fulfillment.common.version.OrderVersionRepository;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import cn.zimu.fulfillment.order.domain.OrderLineComponent;
import cn.zimu.fulfillment.order.domain.ProcessingHealth;
import cn.zimu.fulfillment.order.domain.ProcessingStage;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderEventDto;
import cn.zimu.fulfillment.order.dto.OrderSummaryDto;
import cn.zimu.fulfillment.order.dto.OrderVersionDto;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单查询：列表使用 v_order_progress_summary 投影，详情为实体聚合。 */
@Service
public class OrderQueryService {

    /** v_order_progress_summary 视图投影。 */
    public record ViewProjection(
            String stage, String health, int completedCount, int totalCount, String attentionReason) {

        static ViewProjection empty() {
            return new ViewProjection(ProcessingStage.NEED_REVIEW.name(), ProcessingHealth.BLUE.name(), 0, 0, null);
        }
    }

    private static final String SELECT_COLUMNS =
            """
            SELECT o.id, o.order_no,
                   COALESCE(source.effective_source_channel, o.source_channel) source_channel,
                   o.source_ref, o.customer_id, c.customer_name,
                   o.receiver_name, o.order_status, o.lock_version, o.created_at, o.updated_at,
                   v.processing_stage, v.processing_health, v.completed_count, v.total_count, v.attention_reason
            FROM app.orders o
            LEFT JOIN app.v_import_batch_effective_source source
              ON source.import_batch_id=o.source_import_batch_id
            LEFT JOIN app.customers c ON c.id = o.customer_id
            LEFT JOIN app.v_order_progress_summary v ON v.order_id = o.id
            """;

    private final OrderRepository orderRepository;
    private final OrderLineRepository lineRepository;
    private final OrderLineComponentRepository componentRepository;
    private final ReviewCaseRepository reviewCaseRepository;
    private final OrderEventRepository eventRepository;
    private final OrderVersionRepository versionRepository;
    private final CustomerRepository customerRepository;
    private final SkuRepository skuRepository;
    private final JdbcTemplate jdbcTemplate;
    private final OrderMapper orderMapper;

    public OrderQueryService(
            OrderRepository orderRepository,
            OrderLineRepository lineRepository,
            OrderLineComponentRepository componentRepository,
            ReviewCaseRepository reviewCaseRepository,
            OrderEventRepository eventRepository,
            OrderVersionRepository versionRepository,
            CustomerRepository customerRepository,
            SkuRepository skuRepository,
            JdbcTemplate jdbcTemplate,
            OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.componentRepository = componentRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.eventRepository = eventRepository;
        this.versionRepository = versionRepository;
        this.customerRepository = customerRepository;
        this.skuRepository = skuRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.orderMapper = orderMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryDto> list(OrderListQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE o.data_scope = 'BUSINESS'");
        if (query.dateFrom() != null) {
            where.append(" AND o.created_at >= ?");
            args.add(query.dateFrom());
        }
        if (query.dateTo() != null) {
            where.append(" AND o.created_at < ?");
            args.add(query.dateTo());
        }
        if (query.sourceChannel() != null) {
            where.append(" AND COALESCE(source.effective_source_channel, o.source_channel) = ?");
            args.add(query.sourceChannel().name());
        }
        if (query.orderStatus() != null) {
            where.append(" AND o.order_status = ?");
            args.add(query.orderStatus().name());
        }
        if (query.processingStage() != null) {
            where.append(" AND v.processing_stage = ?");
            args.add(query.processingStage().name());
        }
        if (query.processingHealth() != null) {
            where.append(" AND v.processing_health = ?");
            args.add(query.processingHealth().name());
        }
        if (query.providerId() != null) {
            where.append("""
                     AND EXISTS (
                         SELECT 1
                         FROM app.order_lines provider_line
                         WHERE provider_line.order_id = o.id
                           AND provider_line.fulfillment_provider_id = ?
                     )
                    """);
            args.add(query.providerId());
        }
        if (query.query() != null && !query.query().isBlank()) {
            where.append(" AND (o.order_no ILIKE ? OR o.source_ref ILIKE ? OR c.customer_name ILIKE ?)");
            String like = "%" + query.query().trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) " + SELECT_COLUMNS.substring(SELECT_COLUMNS.indexOf("FROM")) + where,
                Long.class,
                args.toArray());
        String orderBy = buildOrderBy(query.sorts());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) query.page() * query.size());
        List<OrderSummaryDto> items = jdbcTemplate.query(
                SELECT_COLUMNS + where + orderBy + " LIMIT ? OFFSET ?", SUMMARY_ROW_MAPPER, pageArgs.toArray());
        return new PageResponse<>(items, query.page(), query.size(), total, (int) Math.ceil((double) total / query.size()));
    }

    @Transactional(readOnly = true)
    public OrderDetailDto getDetail(Long orderId) {
        Order order = requireBusinessOrder(orderId);
        return toDetail(order);
    }

    @Transactional(readOnly = true)
    public List<OrderEventDto> timeline(Long orderId) {
        requireBusinessOrder(orderId);
        return eventRepository.findByOrderIdOrderBySequenceNoAsc(orderId).stream()
                .map(orderMapper::toEvent)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderVersionDto> versions(Long orderId) {
        requireBusinessOrder(orderId);
        return versionRepository.findByOrderIdOrderByVersionNoAsc(orderId).stream()
                .map(orderMapper::toVersion)
                .toList();
    }

    OrderDetailDto toDetail(Order order) {
        List<OrderLine> lines = lineRepository.findByOrderIdOrderByLineNoAsc(order.getId());
        List<OrderLineComponent> components =
                componentRepository.findByOrderLineIdIn(lines.stream().map(OrderLine::getId).toList());
        List<ReviewCase> reviewCases = reviewCaseRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        String customerName = null;
        if (order.getCustomerId() != null) {
            customerName = customerRepository
                    .findById(order.getCustomerId())
                    .map(Customer::getCustomerName)
                    .orElse(null);
        }
        Map<Long, String> skuCodes = new LinkedHashMap<>();
        if (!lines.isEmpty()) {
            skuRepository.findAllById(collectSkuIds(lines, components)).forEach(sku -> skuCodes.put(sku.getId(), sku.getSkuCode()));
        }
        return orderMapper.toDetail(
                order,
                lines,
                components,
                reviewCases,
                customerName,
                skuCodes,
                effectiveSourceChannel(order),
                viewProjection(order.getId()));
    }

    private String effectiveSourceChannel(Order order) {
        if (order.getSourceImportBatchId() == null) {
            return order.getSourceChannel().name();
        }
        return jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(source.effective_source_channel, o.source_channel)
                FROM app.orders o
                LEFT JOIN app.v_import_batch_effective_source source
                  ON source.import_batch_id=o.source_import_batch_id
                WHERE o.id=?
                """,
                String.class,
                order.getId());
    }

    /** 供命令服务在事务内复用：同一事务内先 flush 再调用，保证视图查询可见。 */
    @Transactional(readOnly = true)
    public ViewProjection viewProjection(Long orderId) {
        return jdbcTemplate.query(
                """
                SELECT processing_stage, processing_health, completed_count, total_count, attention_reason
                FROM app.v_order_progress_summary WHERE order_id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return ViewProjection.empty();
                    }
                    return new ViewProjection(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getInt(3),
                            rs.getInt(4),
                            rs.getString(5));
                },
                orderId);
    }

    private Order requireBusinessOrder(Long orderId) {
        Order order = orderRepository
                .findById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在: " + orderId));
        if (order.getDataScope() != DataScope.BUSINESS) {
            throw BusinessException.notFound("订单不存在: " + orderId);
        }
        return order;
    }

    private List<Long> collectSkuIds(List<OrderLine> lines, List<OrderLineComponent> components) {
        List<Long> ids = new ArrayList<>();
        for (OrderLine line : lines) {
            if (line.getSkuId() != null) {
                ids.add(line.getSkuId());
            }
        }
        for (OrderLineComponent component : components) {
            ids.add(component.getSkuId());
        }
        return ids;
    }

    private String buildOrderBy(List<String> sorts) {
        Map<String, String> allowed = Map.of(
                "created_at", "o.created_at",
                "updated_at", "o.updated_at",
                "order_no", "o.order_no");
        List<String> clauses = new ArrayList<>();
        if (sorts != null) {
            for (int index = 0; index < sorts.size(); index++) {
                String sort = sorts.get(index);
                String[] parts = sort.split(",", 2);
                String field = parts[0].trim();
                String direction = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
                // Spring expands a request such as sort=created_at,desc into two List values.
                // Reassemble that documented field,direction pair before validating it.
                if (parts.length == 1 && index + 1 < sorts.size()) {
                    String candidateDirection = sorts.get(index + 1).trim().toLowerCase();
                    if (candidateDirection.equals("asc") || candidateDirection.equals("desc")) {
                        direction = candidateDirection;
                        index++;
                    }
                }
                String column = allowed.get(field);
                if (column == null || (!direction.equals("asc") && !direction.equals("desc"))) {
                    throw BusinessException.badRequest(
                            "INVALID_SORT", "不支持的排序字段或方向: " + sort);
                }
                clauses.add(column + " " + direction);
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY o.created_at DESC, o.id DESC";
        }
        clauses.add("o.id DESC");
        return " ORDER BY " + String.join(", ", clauses);
    }

    private static final RowMapper<OrderSummaryDto> SUMMARY_ROW_MAPPER = (rs, rowNum) -> new OrderSummaryDto(
            String.valueOf(rs.getLong("id")),
            rs.getString("order_no"),
            rs.getString("source_channel"),
            rs.getString("source_ref"),
            rs.getLong("customer_id") == 0 ? null : String.valueOf(rs.getLong("customer_id")),
            rs.getString("customer_name"),
            rs.getString("receiver_name"),
            rs.getString("order_status"),
            rs.getString("processing_stage"),
            rs.getString("processing_health"),
            rs.getInt("completed_count"),
            rs.getInt("total_count"),
            rs.getString("attention_reason"),
            toInstant(rs, "created_at"),
            toInstant(rs, "updated_at"),
            rs.getLong("lock_version"));

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
