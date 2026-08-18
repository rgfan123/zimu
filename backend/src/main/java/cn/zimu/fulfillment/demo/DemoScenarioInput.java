package cn.zimu.fulfillment.demo;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DemoScenarioInput(
        @JsonProperty("scenario_code")
                @NotBlank(message = "演示场景不能为空")
                @Size(max = 64, message = "演示场景编码超长")
                String scenarioCode) {}
