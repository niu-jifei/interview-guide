package interview.guide.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    /**
     * 严格 JSON 指令
     */
    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    //======================指标埋点（Micrometer）========================
    /**
     * 调用次数指标
     */
    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";

    /**
     * 尝试次数指标
     */
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";

    /**
     * 响应时间指标
     *
     * latency 延迟指标
     */
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";


    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";

    /**
     * 最大上下文标签长度
     */
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    /**
     * 非字母数字字符替换正则
     */
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");

    /**
     * 多个连续下划线替换正则
     */
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.meterRegistry = meterRegistry;
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 构建重试系统提示
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);

            try {
                String content = chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

                // 转换并修复
                T result = convertWithRepair(content, outputConverter, logContext, log);
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage());
                }
            }
        }

        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    /**
     * 转换并修复
     *
     * 尝试修复未转义引号后解析 JSON
     * @param content
     * @param outputConverter
     * @param logContext
     * @param log
     * @return
     * @param <T>
     */
    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);

            // 有变化再次尝试解析
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    // 修复失败，保留原始错误信息
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 修复 JSON 字符串中的未转义引号
     * <p>
     *  JSON 字符串中的引号必须转义，否则会导致 JSON 解析失败。
     *  正确示例：
     *  {"comment": "候选人提到"高并发"经验，但缺乏细节"}
     *
     *  {"comment": "候选人提到\"高并发\"经验，但缺乏细节"}
     *
     * </p>
     *
     * @param content
     * @return
     */
    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        // 逃离
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    /**
     * 检查是否为 JSON 字符串的终止符
     * @param content
     * @param start
     * @return
     */
    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    /**
     * 构建重试系统提示词
     * @param systemPromptWithFormat
     * @param lastError
     * @return
     */
    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }


    /**
     * 修复错误消息中的换行符和制表符，并截断过长的消息
     * @param message
     * @return
     */
    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    /**
     * 记录尝试指标
     *
     * 每次尝试的成败计数
     *
     * @param contextTag
     * @param status
     */
    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    /**
     * 记录调用指标
     * @param contextTag
     * @param status
     * @param startNanos
     */
    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    /**
     * 归一化上下文标签
     * 转成安全的 Prometheus 标签值，防止标签基数爆炸和非法字符
     *
     * @param raw
     * @return
     */
    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        // 转成小写，去掉前后空格，将空格替换为下划线
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        // 去掉非法字符
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        // 去掉连续的下划线
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        // 去掉前导和尾随的下划线
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        // 连续多个 _ 合并成一个
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        // 去掉前导和尾随的下划线
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        // 截断过长的标签
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
