package cn.zimu.fulfillment.connector;

/** Connector 可用能力；来源渠道身份由实现固定，不接受运行时切换。 */
public record ConnectorCapabilities(
        boolean fileImport,
        boolean fileExport,
        boolean onlinePull,
        boolean onlinePush,
        boolean callback) {}
