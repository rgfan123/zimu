package cn.zimu.fulfillment.rawmaterial;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 原料库存写网关（出入库 MCP）：建入库/报废单 + 审批入账/出账。
 *
 * <p>审批即入账/出账（上游落批次与流水），不可逆——调用方必须把「审批」当终局动作对待。
 * 所有方法在写通道未开（{@link YuanliaokcGatewayProperties#isWriteReady()} 为 false）时
 * 抛 {@link RawMaterialWriteException.Code#RAW_MATERIAL_WRITE_DISABLED} 且不发任何网络包。
 */
public interface YuanliaokcWriteGateway {

    /**
     * 创建入库单（上游 InboundCreate：supplier_name?/warehouse_id/notes?/lines[]），
     * 建单即待审核（pending_approval）。payload 由调用方按上游契约构造并校验。
     */
    YuanliaokcInboundOrder createInboundOrder(JsonNode payload);

    /** 审批入库单：每行建采购批次 + 入库流水，状态转 posted，不可逆。 */
    YuanliaokcInboundOrder approveInboundOrder(long orderId);

    /**
     * 创建报废单（上游 ScrapCreate：batch_id/piece_count?/quantity_kg/reason），
     * 建单即冻结报废量、待审核。
     */
    YuanliaokcScrapOrder createScrapOrder(JsonNode payload);

    /** 审批报废单：扣减批次结存并记报废流水，状态转 posted，不可逆。 */
    YuanliaokcScrapOrder approveScrapOrder(long orderId);
}
