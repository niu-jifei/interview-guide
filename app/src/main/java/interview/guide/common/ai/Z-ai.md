# common/ai 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/common/ai/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `LlmProviderRegistry.java` | LLM Provider 注册中心，管理 ChatClient/EmbeddingModel 缓存，支持多 Provider 动态路由 | `@Component`，`ConcurrentHashMap` 缓存 |
| `StructuredOutputInvoker.java` | 结构化输出调用封装，支持重试、JSON 修复、指标采集 | `@Component`，`BeanOutputConverter` |
| `ApiPathResolver.java` | 构建 OpenAiApi 实例，处理 BaseUrl 拼接 | 工具类 |
| `PromptSanitizer.java` | Prompt 安全过滤，防止注入攻击 | 工具类 |
| `PromptSecurityConstants.java` | Prompt 安全常量定义（反注入指令） | 常量类 |
| `AgentUtilsConfiguration.java` | Agent Utils 配置类，注册 Skills ToolCallback | `@Configuration` |
| `AgentUtilsProperties.java` | Agent Utils 配置属性 | `@ConfigurationProperties` |
| `StructuredOutputProperties.java` | 结构化输出配置属性（重试次数、指标开关等） | `@ConfigurationProperties` |

## 文件协作关系
- `LlmProviderRegistry` → `ApiPathResolver`：构建 OpenAiApi 实例
- `LlmProviderRegistry` → `ApiKeyEncryptionService`：解密 Provider API Key
- `StructuredOutputInvoker` → `StructuredOutputProperties`：读取重试/指标配置
- `AgentUtilsConfiguration` → `AgentUtilsProperties`：读取 Skills 根路径配置
- 业务模块 Service → `LlmProviderRegistry.getChatClientOrDefault()`：获取 ChatClient
- 业务模块 Service → `StructuredOutputInvoker.invoke()`：执行结构化输出

## Spring AI 专项标注
- **ChatClient 类型**：`LlmProviderRegistry` 提供 3 种 ChatClient：
  - `getChatClient()`：默认带 SkillsTool + 全部 Advisor
  - `getPlainChatClient()`：不带 Tools，用于结构化输出场景
  - `getVoiceChatClient()`：SkillsTool + ToolCallAdvisor（流式），用于语音面试
- **Advisor 链**：ToolCallAdvisor → SafeGuardAdvisor → SimpleLoggerAdvisor → MessageChatMemoryAdvisor
- **EmbeddingModel**：通过 `getEmbeddingModel()` 获取，支持多 Provider 缓存
- **配置隔离**：Provider 配置从数据库（`LlmProviderRepository`）或 `application.yml` 加载，动态写入 `${user.home}/.interview-guide/`
