package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";

    /**
     * 流式输出探针字符数
     */
    private static final int STREAM_PROBE_CHARS = 120;
    /**
     * 重写历史记录最大字符数
     */
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        // 构建查询上下文
        QueryContext queryContext = buildQueryContext(question, List.of());
        // 检索相关文档
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        // 检查是否有有效结果
        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建查询上下文（包含候选查询列表 + 检索参数）
     * @param originalQuestion 原始问题
     * @param history 历史对话消息
     * @return
     */
    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        // 1. 规范化原始问题
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        // 2. 问题改写， 添加候选查询
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // 3.根据问题长度动态设置搜索参数
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    /**
     * 清洗历史对话消息
     *
     * @param history
     * @return
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

    // 清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /**
     * 向量检索相关文档（多候选降级策略）。
     * <p>
     * 按顺序尝试候选查询（改写后问题优先，原始问题兜底），对每个候选执行
     * pgvector 相似度检索（使用动态解析的 topK / minScore），一旦命中立即返回，
     * 避免多余的检索开销；全部候选无命中时返回空列表，由上层返回"无结果"话术。
     *
     * @param queryContext 查询上下文（候选查询列表 + 检索参数）
     * @param knowledgeBaseIds 目标知识库ID列表
     * @return 命中的文档片段，无命中返回空列表
     */
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    /**
     * 根据问题的紧凑长度（去除空白后）动态设置向量检索参数（topK + minScore）。
     * <p>
     * 核心思路：问题越短 → 语义信息越少 → 召回越宽松。
     * <ul>
     *   <li>短问题（≤ shortQueryLength，默认 4 字，如"JVM"）：关键词式查询，向量语义模糊、
     *       相似度普遍偏低，因此多召回（topkShort=20）且降低阈值（minScoreShort=0.18），
     *       宁可多拿片段让 LLM 筛选，也不漏召回</li>
     *   <li>中问题（≤ 12 字）：topkMedium=12 + minScoreDefault=0.28</li>
     *   <li>长问题（> 12 字，完整句子）：语义充足、匹配精准，少召回（topkLong=8）即可，
     *       减少无关噪音进入 prompt，也节省 token</li>
     * </ul>
     * 具体数值由 {@link KnowledgeBaseQueryProperties.Search}（app.ai.rag.search）配置。
     *
     * 设计思路
     * 核心逻辑是：问题越短 → 语义信息越少 → 召回越宽松
     * 短问题（如"JVM"、"索引"）：关键词式查询，向量语义模糊、相似度分数普遍偏低。因此多召回（topK=20）且降低阈值（0.25），宁可多拿一些片段让LLM 自己筛选，也不要漏召回。
     * 长问题（如完整的一句话提问）：语义信息充足，向量匹配更精准，少召回（topK=8）即可，减少无关噪音进入 prompt，也节省 token。
     *
     * @param question 已规范化的用户问题
     * @return 向量检索参数
     */
    private SearchParams resolveSearchParams(String question) {
        // 替换所有空白字符为空格字符，计算紧凑长度
        int compactLength = question.replaceAll("\\s+", "").length();
        // 根据问题长度动态设置搜索参数
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

    /**
     * 改写问题
     */
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    /**
     * 规范化答案，去除前导空格并检查是否为无信息响应。
     *
     * 归一化
     * @param answer
     * @return
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    /**
     * 判断文本是否为无信息响应。
     * @param text
     * @return
     */
    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     *
     * 探测窗口归一化
     *
     * 先缓冲观察 LLM 流式回答的开头一小段（探测窗口），判断它是不是"无信息拒答"，再决定是替换成固定话术还是原样透传
     *
     *
     * 非流式调用里有 normalizeAnswer —— 拿到完整回答后检查是否包含"没有找到相关信息"等拒答特征，命中就换成统一的 NO_RESULT_RESPONSE。
     * 但流式（SSE）场景拿不到完整回答：内容一块一块往前端推，等发现是拒答时，用户已经看到 LLM 长篇大论地解释"抱歉，根据提供的资料无法……"了，而且各次拒答措辞不一，体验不统一。
     *
     * 为什么叫"归一化"
     * 无论 LLM 用什么措辞拒答（"信息不足"、"超出知识库范围"、"无法根据提供内容回答"……），最终前端看到的都是同一句标准话术——把多样的拒答输出"归一"成统一响应，和非流式路径行为保持一致。
     * 代价与权衡
     * 首字延迟：正常回答的前 120 字符会被缓冲，用户感知的"首包时间"略微变长（通常也就零点几秒，可接受）
     * 探测窗口盲区：如果拒答特征出现在 120 字之后（比如 LLM 先客套一段再拒答），会漏检、原样透传——这是用窗口大小换实时性的固有权衡，120 字覆盖了绝大多数拒答开头的场景
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            // 探测缓冲区：透传前暂存开头内容，用于识别拒答特征
            StringBuilder probeBuffer = new StringBuilder();
            // 是否已进入透传模式（探测通过后，后续 chunk 不再缓冲，直接实时下发）
            AtomicBoolean passthrough = new AtomicBoolean(false);
            // 是否已提前结束（命中拒答后置为 true，忽略上游残余 chunk）
            AtomicBoolean completed = new AtomicBoolean(false);
            // 持有上游订阅句柄，便于在命中拒答/下游取消时主动断开上游（掐断 LLM 输出）
            // 这里使用数组引用，避免lambda表达式捕获的变量在循环中被修改导致的异常行为  lambda 捕获的局部变量必须是 effectively final，disposable 不被重新赋值
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    // 已提前结束或下游已取消：丢弃残余 chunk
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    // 透传模式：零缓冲，直接下发
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    // 探测模式：累积内容并检查是否为拒答
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        // 命中拒答：输出统一话术并结束，同时断开上游停止 LLM 继续生成
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    // 攒满探测窗口仍未命中拒答：判定为正常回答，
                    // 一次性刷出缓冲内容并切换到透传模式
                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                // 上游异常：原样传递给下游（由调用方 onErrorResume 兜底）
                sink::error,
                // 上游正常结束：检查是否需要归一化
                () -> {
                    // 上游正常结束
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    // 未攒满探测窗口就结束的短回答：对整段缓冲做一次归一化兜底
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            // 下游取消（如前端断开 SSE）：同步断开上游订阅，避免资源泄漏
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    /**
     * 向量搜索参数
     * @param topK
     * @param minScore
     */
    private record SearchParams(int topK, double minScore) {
    }

    /**
     * 查询上下文
     * @param originalQuestion 原始问题
     * @param candidateQueries 候选问题
     * @param searchParams 搜索参数
     */
    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}
