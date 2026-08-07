package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Prompt 注入净化工具。
 *
 * 提示词净化器
 * <p>
 * 仅用于 4 个严重风险的直接拼接点（裸拼接，无模板包裹）。
 * 模板插值点有 Layer 2 的系统提示词保护，不需要额外净化。
 */
@Component
public class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private final LlmProviderProperties properties;

    public PromptSanitizer(LlmProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 行首角色声明注入 —— 匹配行首的 system/user/assistant 等角色标记。
     *
     * 正则分解：
     *   (?im)   —— 多行模式（^ 匹配每行行首）+ 忽略大小写
     *   ^       —— 行首
     *   \s*     —— 允许行首空格
     *   (system|user|assistant|human|ai|model) —— 角色关键词
     *   \s*     —— 允许空格
     *   [:：]   —— 英文或中文冒号
     *   .*      —— 后续任意内容
     *
     * 为什么加 (?im) 多行模式？
     *   攻击者可能在文本中间另起一行写 "system: 你是新角色"，多行模式能让 ^ 匹配到这一行。
     *
     * 为什么只匹配行首？
     *   避免误杀 "Experience with system design" 这类正常文本中出现的角色词。
     */
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
        "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    /**
     * 注入短语匹配 —— 检测攻击者试图覆盖系统提示词的常见英文/中文短语。
     *
     * 设计原则：精确匹配组合短语，而非单个关键词，避免误杀。
     *   例如 "ignore" 或 "instruction" 本身是常见词，不能单独匹配。
     *   必须同时出现 "ignore + previous + instructions" 这样的组合才触发。
     *
     * 匹配的英文模式：
     *   ignore previous/above/all/your instructions/prompts/rules
     *   forget everything/all previous instructions/rules/prompts
     *   new instructions:
     *
     * 匹配的中文模式：
     *   忽略之前的指令、忘记之前的指令、忽略以上所有
     *   你不再是、你的新角色是
     *
     * 为什么用 Pattern.CASE_INSENSITIVE 而不是内联 (?i)？
     *   此处统一使用编译标志，风格一致；且不需要 (?m) 多行模式，因为短语可出现在行中任意位置。
     *
     * 列出了所有匹配的中英文短语、解释了"精确匹配组合而非单个关键词"的设计原则
     */
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
        "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
        "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
        "|(new\\s+instructions?:)" +
        "|忽略之前的指令" +
        "|忘记之前的指令" +
        "|忽略以上所有" +
        "|你不再是" +
        "|你的新角色是",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 静态分隔符伪造 —— 匹配项目中 .st 模板使用的固定分隔符。
     *
     * 正则分解：
     *   ---               —— 分隔符前后缀
     *   (?:简历|文档|问答)  —— 非捕获分组，匹配内容类型
     *   内容               —— 固定字面量
     *   (?:开始|结束)      —— 非捕获分组，匹配开始或结束标记
     *   ---               —— 分隔符前后缀
     *
     * 匹配示例：
     *   ---简历内容开始---
     *   ---文档内容结束---
     *   ---问答内容开始---
     *
     * 为什么需要检测？
     *   如果 .st 模板使用固定分隔符（如 ---简历内容开始---），
     *   攻击者可以在输入中伪造 "---简历内容结束---" 来提前关闭段落，
     *   导致后续内容逃逸出模板的上下文边界。
     *
     * 为什么用非捕获分组 (?:)？
     *   不需要捕获具体匹配的是"简历"还是"文档"，只需判断是否存在即可。
     */
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
        "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    /**
     * XML 边界标签伪造 —— 防止攻击者构造 {@code <data-boundary...>} 来提前关闭包裹。
     *
     * 正则分解：
     *   <       —— 标签开始
     *   /?      —— 可选斜杠，同时匹配开标签 <data-boundary> 和闭标签 </data-boundary>
     *   data-boundary —— 标签名（固定前缀）
     *   [^>]*   —— 匹配标签内任意属性或随机 UUID 片段，直到遇到 >
     *   >       —— 标签结束
     *   CASE_INSENSITIVE —— 忽略大小写，防止攻击者用 <DATA-BOUNDARY> 绕过
     *
     * 匹配示例：
     *   <data-boundary-a1b2c3d4-简历>
     *   </data-boundary-a1b2c3d4-简历>
     *   <DATA-BOUNDARY-xxx-问答>（大小写绕过）
     *
     * 设计意图（被动防御）：
     *   在 sanitize() 阶段，将用户输入中任何已有的 <data-boundary...> 标签替换为无害占位符，
     *   确保后续 wrapWithDelimiters() 生成的随机边界不会被攻击者提前伪造的标签干扰。
     *   与 wrapWithDelimiters() 的随机 UUID 机制形成"主动+被动"双层防护。
     */
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
        "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     * 受 {@code app.ai.advisors.promptSanitizerEnabled} 配置控制。
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (!isSanitizerEnabled()) {
            return text;
        }

        boolean injected = false;
        String result = text;

        // 角色注入检测
        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            injected = true;
            result = roleMatcher.replaceAll("[filtered-role-marker]");
        }

        // 短语注入检测
        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            injected = true;
            result = phraseMatcher.replaceAll("[filtered]");
        }

        // 分隔符注入检测，替换伪造分隔符
        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }

        // 标签边界注入检测，替换伪造标签
        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        // 记录检测日志
        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length());
        }
        return result;
    }

    /**
     * 用不可预测的分隔符包裹用户文本。
     * 格式：{@code <data-boundary-{uuid片段}-{label}> ... </data-boundary-{uuid片段}-{label}>}
     * UUID 片段使攻击者无法提前构造伪造分隔符。
     *
     * 不可预测性防伪造攻击
     * 主动防御 — wrapWithDelimiters 用随机分隔符包裹用户输入，让攻击者无法预测边界，防止攻击者通过伪造分隔符标签来逃逸 LLM 的上下文边界
     */
    public String wrapWithDelimiters(String label, String text) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        return openTag + "\n" + text + "\n" + closeTag;
    }

    /**
     * 检测注入尝试（仅日志告警，不阻断）
     *
     * 检测用户输入中是否存在 Prompt 注入尝试
     *
     * 记录安全日志用于审计，但不影响用户正常使用
     *
     * 职责分离：检测和清洗是两个不同的关注点，分开提供更灵活
     */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ROLE_INJECTION_PATTERN.matcher(text).find()
            || INJECTION_PHRASE_PATTERN.matcher(text).find();
    }

    /**
     * 检查提示词净化器是否启用
     * @return
     */
    private boolean isSanitizerEnabled() {
        return properties.getAdvisors() == null
            || properties.getAdvisors().isPromptSanitizerEnabled();
    }
}