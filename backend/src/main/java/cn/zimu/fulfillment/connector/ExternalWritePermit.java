package cn.zimu.fulfillment.connector;

/**
 * 外部写围栏许可。
 *
 * <p>Adapter 必须在每一次不可逆远端调用前重新调用 {@link #beforeExternalWrite()}，不能用一次校验
 * 覆盖多个平台效果（例如聚福宝的接单与发货）。</p>
 */
@FunctionalInterface
public interface ExternalWritePermit {

    void beforeExternalWrite();

}
