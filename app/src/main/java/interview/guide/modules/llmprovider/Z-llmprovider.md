# LLM Provider 模块架构分析

## 一、模块定位

`llmprovider` 是平台的 **LLM 服务管理中枢**，负责多 LLM Provider 的 CRUD 管理、运行时动态切换、API Key 安全存储、连通性测试，以及语音面试 ASR/TTS 配置管理。

该模块对外暴露 REST API 供前端管理界面调用，对内通过 `LlmProviderRegistry`（位于 `common/ai/`）为所有业务模块提供 `ChatClient` 和 `EmbeddingModel` 实例。

---

## 二、包结构与职责

```
modules/llmprovider/
├── controller/
│   └── LlmProviderController.java      # REST API 路由层，纯委托
├── dto/
│   ├── CreateProviderRequest.java       # 创建 Provider 请求 (record)
│   ├── UpdateProviderRequest.java       # 更新 Provider 请求 (record)
│   ├── ProviderDTO.java                 # Provider 响应 (record, maskedApiKey)
│   ├── DefaultProviderDTO.java          # 默认 Provider 请求/响应 (record)
│   ├── ProviderTestResult.java          # 连通性测试结果 (record)
│   ├── AsrConfigDTO.java                # ASR 配置响应
│   ├── AsrConfigRequest.java            # ASR 配置更新请求 (record)
│   ├── TtsConfigDTO.java                # TTS 配置响应
│   └── TtsConfigRequest.java            # TTS 配置更新请求 (record)
├── model/
│   ├── LlmProviderEntity.java           # JPA 实体: llm_provider_config 表
│   └── LlmGlobalSettingEntity.java      # JPA 实体: llm_global_setting 表 (单例)
├── repository/
│   ├── LlmProviderRepository.java       # Provider CRUD
│   └── LlmGlobalSettingRepository.java  # 全局设置 CRUD
├── service/
│   ├── LlmProviderConfigService.java    # 核心业务逻辑：CRUD + 配置持久化 + 连通性测试
│   ├── LlmProviderBootstrapService.java # 启动时从 YAML 种子数据初始化 DB
│   └── ApiKeyEncryptionService.java     # AES-GCM 加密/解密 API Key
└── Z-llmprovider.md                     # 本文档
```

### 关联的 common 层组件

| 组件 | 路径 | 职责 |
|------|------|------|
| `LlmProviderRegistry` | `common/ai/` | ChatClient / EmbeddingModel 缓存与按需创建 |
| `LlmProviderProperties` | `common/config/` | `@ConfigurationProperties(prefix = "app.ai")` 配置绑定 |
| `ApiPathResolver` | `common/ai/` | OpenAI 兼容 API 路径自适应解析 |
| `LlmEmbeddingConfig` | `common/config/` | 全局 `EmbeddingModel` Bean（代理到 Registry） |

---

## 三、数据模型

### 3.1 llm_provider_config 表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(64) PK | Provider 标识 (如 `dashscope`, `glm`) |
| `base_url` | VARCHAR(512) | OpenAI 兼容 API 基础 URL |
| `api_key_ciphertext` | VARCHAR(4096) | AES-GCM 加密后的 API Key |
| `api_key_nonce` | VARCHAR(64) | 加密随机 Nonce (Base64) |
| `model` | VARCHAR(128) | 聊天模型名称 |
| `embedding_model` | VARCHAR(128) | 向量模型名称（可空） |
| `embedding_dimensions` | INTEGER | 向量维度（可空，回退到全局默认 1024） |
| `supports_embedding` | BOOLEAN | 是否支持向量嵌入 |
| `temperature` | DOUBLE | 生成温度 |
| `enabled` | BOOLEAN | 是否启用 |
| `builtin` | BOOLEAN | 是否内置（种子数据） |
| `created_at` / `updated_at` | TIMESTAMP | 时间戳 |

### 3.2 llm_global_setting 表（单例模式）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 固定为 `1` |
| `default_chat_provider_id` | VARCHAR(64) | 默认聊天 Provider ID |
| `default_embedding_provider_id` | VARCHAR(64) | 默认向量 Provider ID |

---

## 四、核心业务流程

### 4.1 启动初始化（种子数据播种）

```
Spring 容器启动
  │
  ├─ LlmProviderBootstrapService.@PostConstruct
  │    ├─ providerRepository.count() == 0 ?
  │    │    └─ YES → 从 LlmProviderProperties (YAML) 读取种子配置
  │    │              → ApiKeyEncryptionService.encrypt(apiKey)
  │    │              → providerRepository.save(entity)  (builtin=true)
  │    │
  │    └─ ensureGlobalSetting()
  │         └─ globalSettingRepository 无记录 ?
  │              └─ 创建单例: defaultChatProviderId / defaultEmbeddingProviderId
  │
  ├─ ApiKeyEncryptionService.@PostConstruct
  │    └─ 读取 security.apiKeyEncryptionKey 配置
  │         → 未配置 + requireEncryptionKey=true → 启动失败
  │         → 未配置 + requireEncryptionKey=false → 使用开发回退密钥
  │         → SHA-256 派生 AES-256 密钥
  │
  └─ LlmProviderConfigService.@PostConstruct
       └─ 校验 YAML / .env 文件路径可写
```

### 4.2 Provider CRUD 操作

**双模式持久化**：系统支持两种存储后端，通过 `isDatabaseBacked()` 判断：

- **数据库模式**（生产）：通过 JPA 存储到 `llm_provider_config` 表，API Key 使用 AES-GCM 加密
- **文件模式**（Legacy）：直接修改 YAML + `.env` 文件，保留注释和格式

每次写操作后都触发 `registry.reload()` 清空缓存。

```
前端请求 → LlmProviderController
                │
                ▼
      LlmProviderConfigService (rwLock 加锁)
                │
        ┌───────┴────────┐
        ▼                ▼
   isDatabaseBacked   Legacy 模式
   = true             = false
        │                │
   ┌────┴────┐     ┌─────┴─────┐
   ▼         ▼     ▼           ▼
  JPA 操作  加密   修改 YAML   修改 .env
  + 解密    API    文件        文件
        │        Key          │
        └────┬───┘            │
             ▼                │
      registry.reload()  ◄───┘
```

### 4.3 API Key 安全

`ApiKeyEncryptionService` 使用 **AES-256-GCM** 对称加密：

```
加密流程:
  plainText → SecureRandom(12 bytes nonce) → AES/GCM/NoPadding → Base64(nonce, ciphertext)

密钥派生:
  配置密钥 → Base64 解码(32 bytes) 或 SHA-256 哈希 → AES-256 SecretKeySpec

安全边界:
  - 返回前端: maskApiKey() → "sk-***-xyz" (首3末3)
  - 数据库存储: nonce + ciphertext (均为 Base64)
  - 开发环境: 回退密钥 "interview-guide-dev-only-provider-api-key-encryption"
```

### 4.4 连通性测试

```
testProvider(id)
  │
  ├─ 加载 Provider 配置 (解密 API Key)
  ├─ 构建 RestClient (Bearer Token 认证, connectTimeout=5s, readTimeout=10s)
  ├─ 构建测试请求体: { model, messages: [{role:user, content:"Reply with OK only."}], max_tokens: 1 }
  │
  ├─ 候选 URL 列表:
  │    ├─ baseUrl + "/chat/completions"
  │    └─ (若 baseUrl 不含版本号) baseUrl + "/v1/chat/completions"
  │
  └─ 逐个尝试 POST → 成功返回 success=true，全部失败返回最后错误信息
```

### 4.5 ASR / TTS 配置管理

语音面试的 ASR（语音识别）和 TTS（语音合成）配置与 LLM Provider 共享同一管理入口：

```
updateAsrConfig / updateTtsConfig
  │
  ├─ 修改 VoiceInterviewProperties 内存对象
  ├─ 同步修改 YAML 文件 (writeAsrConfigToYaml / writeTtsConfigToYaml)
  ├─ 若 apiKey 变更 → 同步更新 .env 的 AI_BAILIAN_API_KEY
  │                   → ASR 和 TTS 共享同一个 API Key
  └─ 调用 asrService.reload() / ttsService.reload() 热更新
```

---

## 五、LlmProviderRegistry — 运行时客户端工厂

`LlmProviderRegistry`（位于 `common/ai/`）是整个平台 AI 能力的 **运行时入口**，负责按需创建和缓存 `ChatClient` 和 `EmbeddingModel`。

### 5.1 客户端类型

| 方法 | 缓存 Key | Advisor 配置 | 使用场景 |
|------|----------|-------------|---------|
| `getChatClient(id)` | `{id}` | SkillsTool + ToolCall + Memory + Logger + SafeGuard | 通用对话（面试出题） |
| `getPlainChatClient(id)` | `{id}:plain` | SafeGuard only | 结构化输出（JSON 解析） |
| `getVoiceChatClient(id)` | `{id}:voice` | SkillsTool + ToolCall(stream) + SafeGuard | 语音面试实时对话 |
| `getEmbeddingModel(id)` | `{id}` | — | 知识库向量化 |
| `getDefaultChatClient()` | 自动解析默认 ID | 同 getChatClient | 未指定 Provider 时 |
| `getDefaultEmbeddingModel()` | 自动解析默认 ID | — | 全局 EmbeddingModel Bean |

### 5.2 配置加载优先级

```
loadProviderOrThrow(providerId)
  │
  ├─ providerRepository != null ?
  │    ├─ YES → 数据库查找 (findById + filter(enabled))
  │    │         → 解密 API Key (ApiKeyEncryptionService.decrypt)
  │    │
  │    └─ NO → 从 LlmProviderProperties (YAML) 加载
  │
  └─ 均未找到 → throw IllegalArgumentException
```

### 5.3 默认 Provider 解析

```
resolveDefaultChatProviderId()
  │
  ├─ globalSettingRepository 可用 ?
  │    ├─ YES → llm_global_setting.default_chat_provider_id
  │    │         → 为空则回退到 properties.defaultProvider
  │    └─ NO → properties.defaultProvider
  │
  └─ 最终返回 providerId 字符串
```

### 5.4 Advisor 链（拦截器）

根据 `AdvisorConfig` 配置动态组装：

```
ChatClient 请求流:
  → SafeGuardAdvisor (order=100, 敏感词拦截, Prompt 注入防护)
  → ToolCallAdvisor (工具调用自动执行, 可选流式响应)
  → MessageChatMemoryAdvisor (对话记忆窗口, 默认关闭)
  → SimpleLoggerAdvisor (请求日志, 默认关闭)
  → OpenAiChatModel (实际 HTTP 调用)
```

### 5.5 API 路径自适应

`ApiPathResolver` 处理不同厂商的 API 路径差异：

```
baseUrl 以 /v1, /v2 等结尾 → 自动设置 completionsPath="/chat/completions"
baseUrl 不含版本号          → Spring AI 默认追加 /v1/chat/completions
```

---

## 六、业务调用链路（被谁调用）

`LlmProviderRegistry` 被平台所有 AI 业务模块依赖：

```
┌─────────────────────────────────────────────────────────────────┐
│                    LlmProviderRegistry                          │
│  (ChatClient 缓存 + EmbeddingModel 缓存 + reload)              │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────────────────┐
        │                   │                               │
   ┌────┴─────┐      ┌─────┴──────┐              ┌────────┴────────┐
   │ interview │      │   resume   │              │  knowledgebase  │
   │  模块     │      │   模块     │              │     模块        │
   ├──────────┤      ├────────────┤              ├─────────────────┤
   │Evaluate  │      │ResumeGrad- │              │KB QueryService  │
   │Stream    │      │ingService  │              │→getDefaultChat  │
   │Consumer  │      │→getDefault │              │                 │
   │→getChat  │      │ ChatClient │              │KB Vectorize     │
   │ClientOr  │      │→结构化输出  │              │→getDefaultEmbed │
   │Default   │      └────────────┘              └─────────────────┘
   ├──────────┤
   │Interview │      ┌──────────────────┐    ┌──────────────────┐
   │SkillSvc  │      │ interviewschedule│    │ voiceinterview   │
   │→getChat  │      ├──────────────────┤    ├──────────────────┤
   │ClientOr  │      │InterviewParse    │    │DashscopeLlmSvc   │
   │Default   │      │Service           │    │→getVoiceChat     │
   └──────────┘      │→getChatClientOr  │    │→SkillsTool+流式   │
                     │ Default          │    ├──────────────────┤
                     │→AI解析面试邀请    │    │VoiceEvaluationSvc│
                     └──────────────────┘    │→getChatClientOr  │
                                             │ Default          │
                                             ├──────────────────┤
                                             │VoiceInterviewSvc │
                                             │→getChatClientOr  │
                                             │ Default          │
                                             └──────────────────┘
```

---

## 七、API 端点总览

| 方法 | 路径 | 限流 | 说明 |
|------|------|------|------|
| GET | `/api/llm-provider/list` | 30/interval | 列出所有 Provider |
| GET | `/api/llm-provider/{id}` | 30/interval | 获取单个 Provider 详情 |
| POST | `/api/llm-provider` | 5/interval | 创建 Provider |
| PUT | `/api/llm-provider/{id}` | 5/interval | 更新 Provider |
| DELETE | `/api/llm-provider/{id}` | 5/interval | 删除 Provider（默认不可删） |
| POST | `/api/llm-provider/{id}/test` | 10/interval | 连通性测试 |
| POST | `/api/llm-provider/reload` | 5/interval | 手动重新加载缓存 |
| GET | `/api/llm-provider/default-provider` | 30/interval | 获取默认 Provider |
| PUT | `/api/llm-provider/default-provider` | 5/interval | 设置默认聊天 Provider |
| PUT | `/api/llm-provider/default-embedding-provider` | 5/interval | 设置默认向量 Provider |
| GET | `/api/llm-provider/voice/asr` | 30/interval | 获取 ASR 配置 |
| PUT | `/api/llm-provider/voice/asr` | 5/interval | 更新 ASR 配置 |
| GET | `/api/llm-provider/voice/tts` | 30/interval | 获取 TTS 配置 |
| PUT | `/api/llm-provider/voice/tts` | 5/interval | 更新 TTS 配置 |
| POST | `/api/llm-provider/voice/asr/test` | 10/interval | ASR WebSocket 连通性测试 |

---

## 八、错误码

| 错误码 | 枚举值 | 说明 |
|--------|--------|------|
| 11001 | `PROVIDER_NOT_FOUND` | Provider 不存在 |
| 11002 | `PROVIDER_ALREADY_EXISTS` | Provider 已存在 |
| 11004 | `PROVIDER_CONFIG_READ_FAILED` | 读取配置失败 |
| 11005 | `PROVIDER_CONFIG_WRITE_FAILED` | 写入配置失败 |
| 11006 | `PROVIDER_TEST_FAILED` | 连通性测试失败 |
| 11007 | `PROVIDER_DEFAULT_CANNOT_DELETE` | 默认 Provider 不可删除 |
| 11009 | `VOICE_CONFIG_READ_FAILED` | 读取语音服务配置失败 |
| 11010 | `VOICE_CONFIG_WRITE_FAILED` | 写入语音服务配置失败 |
| 11011 | `VOICE_CONFIG_TEST_FAILED` | 语音服务连通性测试失败 |

---

## 九、关键设计决策

### 9.1 双模式持久化（DB + Legacy YAML）

- `isDatabaseBacked()` 通过 Repository 是否注入判断运行模式
- Legacy 模式直接操作 YAML 文本（`YamlTextEditor` 内部类），保留注释和格式
- 生产环境使用数据库 + AES-GCM 加密，更安全

### 9.2 读写锁（ReentrantReadWriteLock）

- 读操作（list/get/test）使用读锁，支持并发
- 写操作（create/update/delete/reload）使用写锁，互斥
- 防止并发修改导致缓存与存储不一致

### 9.3 缓存失效策略

- 每次写操作后调用 `registry.reload()` 清空所有缓存
- 下次 `getChatClient()` 时通过 `computeIfAbsent` 懒加载重建
- 避免维护复杂的增量缓存更新逻辑

### 9.4 Embedding 模型校验

- `validateEmbeddingConfig()` 防止将聊天模型误填为 Embedding 模型
- `looksLikeChatModel()` 通过前缀匹配检测（glm-、deepseek、qwen 等）
- 推荐各厂商正确的 Embedding 模型名

### 9.5 三种 ChatClient 变体

- **标准**：完整 Advisor 链，用于交互式对话（面试出题含工具调用）
- **Plain**：无工具，仅 SafeGuard，用于需要纯 JSON 输出的结构化场景
- **Voice**：SkillsTool + 流式 ToolCall，语音面试专用（手动管理对话历史）
