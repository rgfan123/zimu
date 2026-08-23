package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.OrderDraftService;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.ConfirmOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftLineDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Rebuilds the existing human-confirm use case only from current persisted draft facts. */
@Service
public class OrderDraftCardConfirmationService {

    private final OrderDraftQueryService drafts;
    private final OrderDraftService orderDrafts;

    public OrderDraftCardConfirmationService(
            OrderDraftQueryService drafts, OrderDraftService orderDrafts) {
        this.drafts = drafts;
        this.orderDrafts = orderDrafts;
    }

    public CardConfirmationResult confirm(
            long draftId, String eventMessageId, String requestId, String actorUserid) {
        OrderDraftDetailDto draft = drafts.detail(draftId);
        String actor = "wecom:" + actorUserid;
        if ("CONFIRMED".equals(draft.status())) {
            return result(
                    CardConfirmationStatus.ALREADY_CONFIRMED,
                    draft,
                    List.of(),
                    "ORDER_DRAFT_ALREADY_CONFIRMED",
                    actor);
        }
        if (!"OPEN".equals(draft.status())) {
            return result(
                    CardConfirmationStatus.REJECTED,
                    draft,
                    List.of(),
                    "ORDER_DRAFT_NOT_OPEN",
                    actor);
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>(draft.missingFields());
        String customerId = draft.customerId() == null
                ? uniqueCandidate(draft.customerCandidates(), "customer_id")
                : draft.customerId();
        if (blank(customerId)) {
            missing.add("customer");
        }
        if (blank(draft.receiverName())) {
            missing.add("receiver_name");
        }
        if (blank(draft.receiverPhone())) {
            missing.add("receiver_phone");
        }
        if (blank(draft.receiverAddress())) {
            missing.add("receiver_address");
        }
        SettlementMethod settlementMethod = settlementMethod(draft.settlementMethod());
        if (settlementMethod == null) {
            missing.add("settlement_method");
        }
        if (draft.settlementTime() == null) {
            missing.add("settlement_time");
        }
        if (draft.reviewCaseVersion() == null) {
            missing.add("review_case");
        }
        if (draft.lines().isEmpty()) {
            missing.add("items");
        }

        List<ConfirmOrderDraftCommand.ConfirmItem> items = new ArrayList<>();
        for (OrderDraftLineDto line : draft.lines()) {
            String skuId = line.skuId() == null
                    ? uniqueCandidate(line.skuCandidates(), "sku_id")
                    : line.skuId();
            if (blank(skuId)) {
                missing.add("line_" + line.lineNo() + "_sku");
            }
            if (blank(line.quantity())) {
                missing.add("line_" + line.lineNo() + "_quantity");
            }
            if (!blank(skuId) && !blank(line.quantity())) {
                items.add(new ConfirmOrderDraftCommand.ConfirmItem(line.lineNo(), skuId, line.quantity()));
            }
        }
        if (!missing.isEmpty()) {
            return result(
                    CardConfirmationStatus.MISSING_INFORMATION,
                    draft,
                    List.copyOf(missing),
                    "ORDER_DRAFT_CARD_MISSING_INFORMATION",
                    actor);
        }

        ConfirmOrderDraftCommand command = new ConfirmOrderDraftCommand(
                draft.revision(),
                draft.reviewCaseVersion(),
                new ConfirmOrderDraftCommand.CustomerChoice(customerId, null),
                new Receiver(
                        draft.receiverName(),
                        draft.receiverPhone(),
                        "",
                        "",
                        "",
                        "",
                        draft.receiverAddress()),
                new Settlement(settlementMethod, draft.settlementTime()),
                items,
                null);
        CommandContext context = new CommandContext(requestId, requestId, actor, actor);
        try {
            orderDrafts.confirm(
                    draftId,
                    command,
                    "wecom-card-confirm:" + eventMessageId,
                    context);
        } catch (BusinessException ex) {
            // A second click can race after the initial read. Re-read after the losing transaction
            // rolls back and report the already-confirmed terminal state instead of a false failure.
            if ("DRAFT_NOT_OPEN".equals(ex.getBusinessCode())
                    || "VERSION_CONFLICT".equals(ex.getBusinessCode())) {
                OrderDraftDetailDto latest = drafts.detail(draftId);
                if ("CONFIRMED".equals(latest.status())) {
                    return result(
                            CardConfirmationStatus.ALREADY_CONFIRMED,
                            latest,
                            List.of(),
                            "ORDER_DRAFT_ALREADY_CONFIRMED",
                            actor);
                }
            }
            throw ex;
        }
        return result(
                CardConfirmationStatus.CONFIRMED,
                draft,
                List.of(),
                "ORDER_DRAFT_CONFIRMED",
                actor);
    }

    private static CardConfirmationResult result(
            CardConfirmationStatus status,
            OrderDraftDetailDto draft,
            List<String> missing,
            String businessCode,
            String actor) {
        return new CardConfirmationResult(
                status, draft.draftNo(), missing, businessCode, actor, Instant.now());
    }

    private static String uniqueCandidate(List<Map<String, Object>> candidates, String key) {
        if (candidates == null || candidates.size() != 1) {
            return null;
        }
        Object value = candidates.getFirst().get(key);
        return value == null ? null : String.valueOf(value).strip();
    }

    private static SettlementMethod settlementMethod(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            SettlementMethod parsed = SettlementMethod.valueOf(value);
            return parsed == SettlementMethod.UNSPECIFIED ? null : parsed;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
