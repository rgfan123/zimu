package cn.zimu.fulfillment.followup;

import java.util.Set;

/** Narrow deterministic writer seam. Reconciliation is a read and never resubmits a write. */
public interface KehuzxWriteGateway {

    Set<String> WRITER_ADVERTISED_TOOLS = Set.of(
            "get_dashboard",
            "search_customers",
            "get_customer_detail",
            "search_demands",
            "search_templates",
            "get_template_detail",
            "search_suppliers",
            "search_orders",
            "get_order_detail",
            "get_order_logs",
            "get_entity_logs",
            "create_customer",
            "update_customer",
            "create_order_draft",
            "update_order_draft",
            "confirm_order",
            "create_sample_request",
            "update_sample",
            "update_business_status",
            "update_commercial_terms",
            "append_business_note",
            "link_business_entities",
            "get_mcp_write_request");

    Set<String> EXECUTABLE_WRITE_TOOLS = Set.of(
            "create_customer",
            "update_customer",
            "create_order_draft",
            "update_order_draft",
            "confirm_order",
            "create_sample_request",
            "update_sample",
            "update_business_status",
            "update_commercial_terms",
            "append_business_note",
            "link_business_entities");

    KehuzxWriteResult execute(KehuzxApprovalGrantSigner.Approval approval);

    KehuzxWriteResult reconcile(String requestId);
}
