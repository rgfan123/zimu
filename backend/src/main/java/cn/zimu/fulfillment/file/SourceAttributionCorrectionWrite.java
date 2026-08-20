package cn.zimu.fulfillment.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 追加式来源归因纠正输入；公共契约只接受中文业务显示名。 */
public record SourceAttributionCorrectionWrite(
        @NotBlank(message = "来源渠道不能为空") String sourceChannelDisplayName,
        @NotBlank(message = "纠正原因不能为空") @Size(max = 500, message = "纠正原因超长") String reason,
        Map<String, Object> evidence) {}
