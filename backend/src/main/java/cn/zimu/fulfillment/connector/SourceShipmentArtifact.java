package cn.zimu.fulfillment.connector;

/** 平台写所需的可选二进制产物（例如彩食鲜单 Shipment xlsx）；聚福宝使用 empty。 */
public record SourceShipmentArtifact(
        String fileName,
        String contentType,
        byte[] content,
        String sha256) {

    public SourceShipmentArtifact {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public boolean present() {
        return content.length > 0;
    }

    public static SourceShipmentArtifact empty() {
        return new SourceShipmentArtifact(null, null, new byte[0], null);
    }
}
