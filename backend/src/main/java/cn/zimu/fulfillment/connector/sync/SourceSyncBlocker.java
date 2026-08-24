package cn.zimu.fulfillment.connector.sync;

/** 确定性检查阻断；不承载姓名、电话、地址等 PII。 */
public record SourceSyncBlocker(String code, String field, String message) {}
