package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/** Narrow application seam: only approved read tools can cross into Kehuzx. */
public interface KehuzxReadGateway {

    Set<String> APPROVED_TOOLS = Set.of(
            "search_customers",
            "search_demands",
            "search_orders",
            "get_customer_detail",
            "get_order_detail");

    JsonNode call(String toolName, Map<String, Object> arguments);
}
