package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /**
     * 结构化输出最大尝试试次数
     */
    private int structuredMaxAttempts = 2;
    /**
     * 结构化输出是否包含最后一次错误信息
     */
    private boolean structuredIncludeLastError = true;
    /**
     * 结构化输出是否使用修复提示
     */
    private boolean structuredRetryUseRepairPrompt = true;
    /**
     * 结构化输出是否在错误消息中附加严格JSON指令
     */
    private boolean structuredRetryAppendStrictJsonInstruction = true;
    /**
     * 结构化输出错误消息最大长度
     */
    private int structuredErrorMessageMaxLength = 200;
    /**
     * 结构化输出指标是否启用
     */
    private boolean structuredMetricsEnabled = true;
}
