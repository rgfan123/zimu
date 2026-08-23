package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 中汇 PMS 批量上传批次的逐商品结果行（PENDING → SUCCESS / FAILED）。 */
@Entity
@Table(name = "zhonghui_pms_upload_batch_items")
public class ZhonghuiPmsUploadBatchItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "goods_name")
    private String goodsName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ZhonghuiPmsUploadBatchItemStatus status = ZhonghuiPmsUploadBatchItemStatus.PENDING;

    @Column(name = "business_code")
    private String businessCode;

    @Column(name = "message")
    private String message;

    /** 商品列表校验后确认的 PMS goodsId；未确认时为 null。 */
    @Column(name = "goods_id")
    private Long goodsId;

    /** PMS 商品审核/上架状态文本（如 待平台审核 / 待上架）。 */
    @Column(name = "pms_status")
    private String pmsStatus;

    /** 非阻断提示（如 商品缺少主图）。 */
    @Column(name = "warning")
    private String warning;

    public Long getId() {
        return id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getGoodsName() {
        return goodsName;
    }

    public void setGoodsName(String goodsName) {
        this.goodsName = goodsName;
    }

    public ZhonghuiPmsUploadBatchItemStatus getStatus() {
        return status;
    }

    public void setStatus(ZhonghuiPmsUploadBatchItemStatus status) {
        this.status = status;
    }

    public String getBusinessCode() {
        return businessCode;
    }

    public void setBusinessCode(String businessCode) {
        this.businessCode = businessCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }

    public String getPmsStatus() {
        return pmsStatus;
    }

    public void setPmsStatus(String pmsStatus) {
        this.pmsStatus = pmsStatus;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }
}
