package cn.zimu.fulfillment.common.error;

/** 统一错误模型中的字段级错误。 */
public record FieldErrorItem(String field, String code, String message) {
}
