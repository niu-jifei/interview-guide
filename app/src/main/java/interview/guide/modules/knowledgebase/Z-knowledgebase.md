# knowledgebase 模块包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/`

## 文件清单

### Controller 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `KnowledgeBaseController.java` | 知识库管理入口（上传、查询、列表、删除、向量化） | `@RestController`，`@RateLimit` |
| `RagChatController.java` | RAG 聊天会话管理 | `@RestController` |

### Service 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `KnowledgeBaseUploadService.java` | 知识库上传编排（校验→存储→入队向量化） | `@Service` |
| `KnowledgeBaseQueryService.java` | RAG 查询服务（向量检索 + LLM 回答） | `@Service`，支持 SSE 流式 |
| `KnowledgeBaseVectorService.java` | 向量服务（pgvector 相似度搜索） | `@Service` |
| `KnowledgeBaseListService.java` | 知识库列表/详情/搜索/分类 | `@Service` |
| `KnowledgeBaseDeleteService.java` | 知识库删除（含向量数据） | `@Service` |
| `KnowledgeBaseCountService.java` | 统计服务（问题计数等） | `@Service` |
| `KnowledgeBaseParseService.java` | 知识库文档解析 | `@Service` |
| `KnowledgeBasePersistenceService.java` | 知识库数据持久化 | `@Service` |
| `KnowledgeBaseQueryProperties.java` | RAG 查询配置（topK、minScore 等） | `@ConfigurationProperties` |
| `RagChatSessionService.java` | RAG 聊天会话管理 | `@Service` |

### Listener（异步）
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VectorizeStreamProducer.java` | 向量化任务生产者 | 继承 `AbstractStreamProducer` |
| `VectorizeStreamConsumer.java` | 向量化任务消费者（Embedding + pgvector） | 继承 `AbstractStreamConsumer` |

### Model 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `KnowledgeBaseEntity.java` | 知识库实体 | `@Entity` |
| `RagChatSessionEntity.java` | RAG 聊天会话实体 | `@Entity` |
| `RagChatMessageEntity.java` | RAG 聊天消息实体 | `@Entity` |
| `QueryRequest/Response.java` | 查询请求/响应 | `record` |
| `VectorStatus.java` | 向量化状态枚举 | `enum` |

### Repository 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `KnowledgeBaseRepository.java` | 知识库数据访问 | `JpaRepository` |
| `RagChatSessionRepository.java` | RAG 会话数据访问 | `JpaRepository` |
| `RagChatMessageRepository.java` | RAG 消息数据访问 | `JpaRepository` |
| `VectorRepository.java` | 向量数据访问（Spring Data JPA + pgvector） | `@Query` 自定义向量查询 |

## 文件协作关系
- `KnowledgeBaseController` → `KnowledgeBaseUploadService`：上传文件
- `KnowledgeBaseController` → `KnowledgeBaseQueryService`：RAG 查询（同步/流式）
- `KnowledgeBaseController` → `KnowledgeBaseListService`：列表/搜索/分类
- `KnowledgeBaseUploadService` → `VectorizeStreamProducer`：入队向量化
- `VectorizeStreamConsumer` → `KnowledgeBaseVectorService` → Spring AI VectorStore：Embedding + pgvector
- `KnowledgeBaseQueryService` → `KnowledgeBaseVectorService.similaritySearch()`：向量检索
- `KnowledgeBaseQueryService` → `LlmProviderRegistry.getDefaultChatClient()`：LLM 回答
- `RagChatController` → `RagChatSessionService`：多轮聊天会话管理

## RAG 专项标注
- **Query Rewrite**：短查询改写（LLM 扩展语义），可配置开关
- **动态检索参数**：按查询长度动态调整 topK/minScore
  - 短查询（≤4 字符）：topK=20, minScore=0.18
  - 中等查询：topK=12, minScore=0.28
  - 长查询：topK=8, minScore=0.28
- **多候选查询**：改写后 + 原问题，逐个检索取有效结果
- **流式输出**：SSE 流式响应，探测窗口归一化（前 120 字符检测无信息模板）
- **向量存储**：Spring AI `PgVectorStore`，HNSW 索引，COSINE_DISTANCE，1024 维
- **反注入**：`PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION` 附加到系统提示词

---

# 包架构分析

## 一、整体架构图

```mermaid
graph TB
    subgraph "Presentation Layer (Controller)"
        KB[KnowledgeBaseController<br/>知识库管理入口]
        RC[RagChatController<br/>RAG聊天会话]
    end

    subgraph "Service Layer (Service)"
        UP[KnowledgeBaseUploadService<br/>上传编排]
        QS[KnowledgeBaseQueryService<br/>RAG查询]
        VS[KnowledgeBaseVectorService<br/>向量检索]
        LS[KnowledgeBaseListService<br/>列表/分类/搜索]
        DS[KnowledgeBaseDeleteService<br/>删除]
        CS[KnowledgeBaseCountService<br/>计数统计]
        PS[KnowledgeBaseParseService<br/>文档解析]
        PES[KnowledgeBasePersistenceService<br/>持久化]
        RSS[RagChatSessionService<br/>会话管理]
        QP[KnowledgeBaseQueryProperties<br/>查询配置]
    end

    subgraph "Async Layer (Listener)"
        PROD[VectorizeStreamProducer<br/>向量化生产者]
        CONS[VectorizeStreamConsumer<br/>向量化消费者]
    end

    subgraph "Persistence Layer (Repository)"
        KBR[KnowledgeBaseRepository]
        VECR[VectorRepository]
        RSR[RagChatSessionRepository]
        RMR[RagChatMessageRepository]
    end

    subgraph "Model Layer"
        KBE[KnowledgeBaseEntity]
        RSE[RagChatSessionEntity]
        RME[RagChatMessageEntity]
        KBDTO[KnowledgeBaseListItemDTO]
        KBSDTO[KnowledgeBaseStatsDTO]
        RCDTO[RagChatDTO]
        QR[QueryRequest/Response]
        VS_ENUM[VectorStatus enum]
    end

    subgraph "External Infrastructure"
        RS[Redis Stream]
        PG[PostgreSQL<br/>pgvector]
        RUSTFS[RustFS 文件存储]
        LLM[LLM Provider<br/>DashScope Qwen]
        EMB[Embedding API<br/>DashScope]
    end

    %% 依赖关系
    KB --> UP
    KB --> QS
    KB --> LS
    KB --> DS
    RC --> RSS
    RSS --> QS
    RSS --> RSR
    RSS --> RMR

    UP --> PS
    UP --> PES
    UP --> KBR
    UP --> PROD
    UP --> RUSTFS

    PROD --> RS
    CONS --> RS
    CONS --> VS
    CONS --> KBR

    VS --> VECR
    VS --> EMB
    VS --> PG

    QS --> VS
    QS --> LS
    QS --> CS
    QS --> LLM
    QS --> QP

    LS --> KBR
    LS --> RMR
    LS --> RUSTFS

    DS --> KBR
    DS --> RSR
    DS --> VS
    DS --> RUSTFS

    CS --> KBR
    PES --> KBR

    KBR --> KBE
    VECR --> PG
    RSR --> RSE
    RMR --> RME
```

---

## 二、核心业务流程图

### 2.1 知识库上传流程

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Ctrl as KnowledgeBaseController
    participant UploadSvc as KnowledgeBaseUploadService
    participant ValidateSvc as FileValidationService
    participant HashSvc as FileHashService
    participant ParseSvc as KnowledgeBaseParseService
    participant StorageSvc as FileStorageService
    participant PersistSvc as KnowledgeBasePersistenceService
    participant Producer as VectorizeStreamProducer
    participant Redis as Redis Stream
    participant DB as PostgreSQL

    Client->>Ctrl: POST /api/knowledgebase/upload (MultipartFile)
    Ctrl->>UploadSvc: uploadKnowledgeBase(file, name, category)
    
    UploadSvc->>ValidateSvc: validateFile(file, 50MB)
    ValidateSvc-->>UploadSvc: OK
    
    UploadSvc->>ParseSvc: detectContentType(file)
    ParseSvc-->>UploadSvc: contentType
    
    UploadSvc->>HashSvc: calculateHash(file)
    HashSvc-->>UploadSvc: fileHash (SHA-256)
    
    UploadSvc->>DB: findByFileHash(fileHash)
    alt 已存在（重复文件）
        DB-->>UploadSvc: existingKb
        UploadSvc->>PersistSvc: handleDuplicateKnowledgeBase()
        PersistSvc->>DB: incrementAccessCount()
        UploadSvc-->>Ctrl: {duplicate: true, existingKb}
        Ctrl-->>Client: 返回已有记录
    else 新文件
        UploadSvc->>ParseSvc: parseContent(file)
        ParseSvc-->>UploadSvc: textContent
        
        UploadSvc->>StorageSvc: uploadKnowledgeBase(file)
        StorageSvc-->>UploadSvc: fileKey, fileUrl
        
        UploadSvc->>PersistSvc: saveKnowledgeBase(...)
        PersistSvc->>DB: INSERT INTO knowledge_bases
        PersistSvc-->>UploadSvc: savedKb (status=PENDING)
        
        UploadSvc->>Producer: sendVectorizeTask(kbId, content)
        Producer->>Redis: XADD kb:vectorize:stream
        Producer-->>UploadSvc: OK
        
        UploadSvc-->>Ctrl: {duplicate: false, kbInfo}
        Ctrl-->>Client: 上传成功，向量化异步处理中
    end
```

### 2.2 向量化异步处理流程

```mermaid
sequenceDiagram
    participant Redis as Redis Stream
    participant Consumer as VectorizeStreamConsumer
    participant VectorSvc as KnowledgeBaseVectorService
    participant TextSplitter as TokenTextSplitter
    participant Embedding as DashScope Embedding API
    participant VectorStore as PgVectorStore
    participant DB as PostgreSQL (knowledge_bases)

    Note over Redis,DB: 异步消费，由 AbstractStreamConsumer 循环拉取
    
    Redis-->>Consumer: 消费消息 (kbId, content)
    
    Consumer->>Consumer: markProcessing()
    Consumer->>DB: UPDATE vectorStatus=PROCESSING
    
    Consumer->>VectorSvc: vectorizeAndStore(kbId, content)
    
    VectorSvc->>VectorSvc: deleteByKnowledgeBaseId(kbId)
    VectorSvc->>VectorSvc: clear old vectors
    
    VectorSvc->>TextSplitter: split(content)
    TextSplitter-->>VectorSvc: List<Document> chunks (~800 tokens/chunk)
    
    VectorSvc->>VectorSvc: 为每个chunk添加 metadata(kb_id)
    
    loop 每批 ≤ 10 个chunks
        VectorSvc->>Embedding: batch embedding
        Embedding-->>VectorSvc: 1024维向量
        VectorSvc->>VectorStore: vectorStore.add(batch)
        VectorStore->>VectorStore: HNSW索引入库
    end
    
    VectorSvc-->>Consumer: 完成
    
    Consumer->>Consumer: markCompleted()
    Consumer->>DB: UPDATE vectorStatus=COMPLETED, chunkCount=N
    
    alt 处理失败
        Consumer->>Consumer: markFailed()
        Consumer->>DB: UPDATE vectorStatus=FAILED, vectorError=msg
        Note over Consumer: 自动重试（最多3次）
    end
```

### 2.3 RAG 查询流程（流式）

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Ctrl as KnowledgeBaseController
    participant QuerySvc as KnowledgeBaseQueryService
    participant CountSvc as KnowledgeBaseCountService
    participant VectorSvc as KnowledgeBaseVectorService
    participant LLM as LLM Provider (Qwen)
    participant VectorStore as PgVectorStore

    Client->>Ctrl: POST /api/knowledgebase/query/stream (SSE)
    Ctrl->>QuerySvc: answerQuestionStream(kbIds, question)
    
    QuerySvc->>CountSvc: updateQuestionCounts(kbIds)
    CountSvc->>CountSvc: 验证知识库存在
    CountSvc->>CountSvc: UPDATE questionCount +1
    
    %% 1. Query Rewrite（可选）
    alt rewriteEnabled=true
        QuerySvc->>LLM: rewritePrompt + question
        LLM-->>QuerySvc: rewrittenQuery
        Note over QuerySvc: 多候选查询：改写后 + 原问题
    end
    
    %% 2. 动态检索参数
    Note over QuerySvc: 根据 query.length() 决定 topK/minScore
    Note over QuerySvc: ≤4字符 → topK=20, minScore=0.25
    Note over QuerySvc: 5-20字符 → topK=12, minScore=0.28
    Note over QuerySvc: >20字符 → topK=8, minScore=0.28
    
    %% 3. 向量检索
    QuerySvc->>VectorSvc: similaritySearch(query, kbIds, topK, minScore)
    VectorSvc->>VectorStore: SearchRequest (filter: kb_id in [...])
    VectorStore-->>VectorSvc: List<Document>
    VectorSvc-->>QuerySvc: 有效文档列表
    
    alt 无有效命中
        QuerySvc-->>Ctrl: Flux.just("未检索到相关信息")
        Ctrl-->>Client: SSE 返回无结果消息
    else 有命中
        %% 4. 构建上下文
        Note over QuerySvc: 拼接文档 → context
        
        %% 5. 调用 LLM
        QuerySvc->>LLM: systemPrompt + antiInjection + context + question
        LLM-->>QuerySvc: Flux<String> streaming
        
        %% 6. 探测窗口归一化
        Note over QuerySvc: 前 120 字符检测无信息模板
        Note over QuerySvc: 若匹配则替换为友好提示
        
        QuerySvc-->>Ctrl: Flux<String> (归一化后)
        Ctrl-->>Client: SSE event stream (text/event-stream)
    end
```

### 2.4 RAG 聊天会话流程（多轮对话）

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant RCC as RagChatController
    participant RSS as RagChatSessionService
    participant QuerySvc as KnowledgeBaseQueryService
    participant DB as PostgreSQL

    Note over Client,DB: === 1. 创建会话 ===
    Client->>RCC: POST /api/rag-chat/sessions
    RCC->>RSS: createSession({kbIds, title?})
    RSS->>DB: 验证知识库存在
    RSS->>DB: INSERT INTO rag_chat_sessions
    RSS->>DB: INSERT INTO rag_session_knowledge_bases
    RSS-->>RCC: SessionDTO
    RCC-->>Client: 创建成功

    Note over Client,DB: === 2. 发送消息（流式） ===
    Client->>RCC: POST /api/rag-chat/sessions/{id}/messages/stream (SSE)
    RCC->>RSS: prepareStreamMessage(sessionId, question)
    RSS->>DB: INSERT 用户消息 (type=USER, completed=true)
    RSS->>DB: INSERT AI占位消息 (type=ASSISTANT, completed=false)
    RSS->>DB: UPDATE session.messageCount
    RSS-->>RCC: messageId (AI占位ID)
    
    RCC->>RSS: getStreamAnswer(sessionId, question)
    RSS->>RSS: 加载会话关联的 kbIds
    RSS->>RSS: loadHistoryMessages() ← 最近N条已完成消息
    RSS->>QuerySvc: answerQuestionStream(kbIds, question, history)
    
    QuerySvc-->>RSS: Flux<String> (流式响应)
    RSS-->>RCC: Flux<String>
    RCC-->>Client: SSE 流式输出 (ServerSentEvent)
    
    Note over RCC,Client: 流式完成回调
    RCC->>RSS: completeStreamMessage(messageId, fullContent)
    RSS->>DB: UPDATE message.content, completed=true
    RCC-->>Client: 流式结束

    Note over Client,DB: === 3. 管理操作 ===
    Client->>RCC: PUT /api/rag-chat/sessions/{id}/title
    Client->>RCC: PUT /api/rag-chat/sessions/{id}/pin
    Client->>RCC: PUT /api/rag-chat/sessions/{id}/knowledge-bases
    Client->>RCC: DELETE /api/rag-chat/sessions/{id}
    Client->>RCC: GET /api/rag-chat/sessions
    Client->>RCC: GET /api/rag-chat/sessions/{id}
```

### 2.5 知识库删除流程

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant Ctrl as KnowledgeBaseController
    participant DeleteSvc as KnowledgeBaseDeleteService
    participant VectorSvc as KnowledgeBaseVectorService
    participant VectorRepo as VectorRepository
    participant StorageSvc as FileStorageService
    participant SessionRepo as RagChatSessionRepository
    participant DB as PostgreSQL

    Client->>Ctrl: DELETE /api/knowledgebase/{id}
    Ctrl->>DeleteSvc: deleteKnowledgeBase(id)
    
    DeleteSvc->>DB: 查询 KnowledgeBaseEntity
    DB-->>DeleteSvc: kb
    
    DeleteSvc->>SessionRepo: findByKnowledgeBaseIds([id])
    SessionRepo-->>DeleteSvc: 关联的会话列表
    
    loop 每个关联会话
        DeleteSvc->>DeleteSvc: session.knowledgeBases.remove(kb)
        DeleteSvc->>SessionRepo: sessionRepository.save(session)
    end
    
    DeleteSvc->>VectorSvc: deleteByKnowledgeBaseId(id)
    VectorSvc->>VectorRepo: SQL DELETE FROM vector_store WHERE metadata->>'kb_id' = ?
    VectorRepo-->>VectorSvc: deletedRows
    
    DeleteSvc->>StorageSvc: deleteKnowledgeBase(storageKey)
    StorageSvc->>StorageSvc: 删除 RustFS 文件
    
    DeleteSvc->>DB: knowledgeBaseRepository.deleteById(id)
    DB-->>DeleteSvc: OK
    
    DeleteSvc-->>Ctrl: void
    Ctrl-->>Client: 删除成功
```

---

## 三、类关系图

### 3.1 分层类依赖关系

```mermaid
classDiagram
    %% ===== Controller =====
    class KnowledgeBaseController {
        +KnowledgeBaseUploadService uploadService
        +KnowledgeBaseQueryService queryService
        +KnowledgeBaseListService listService
        +KnowledgeBaseDeleteService deleteService
        +getAllKnowledgeBases() Result
        +getKnowledgeBase(id) Result
        +deleteKnowledgeBase(id) Result
        +queryKnowledgeBase(request) Result
        +queryKnowledgeBaseStream(request) Flux
        +uploadKnowledgeBase(file,name,category) Result
        +downloadKnowledgeBase(id) ResponseEntity
        +search(keyword) Result
        +getStatistics() Result
        +revectorize(id) Result
        +getAllCategories() Result
        +getByCategory(category) Result
        +updateCategory(id,body) Result
    }

    class RagChatController {
        +RagChatSessionService sessionService
        +createSession(request) Result
        +listSessions() Result
        +getSessionDetail(sessionId) Result
        +updateSessionTitle(sessionId,request) Result
        +togglePin(sessionId) Result
        +updateSessionKnowledgeBases(sessionId,request) Result
        +deleteSession(sessionId) Result
        +sendMessageStream(sessionId,request) Flux
    }

    %% ===== Service =====
    class KnowledgeBaseUploadService {
        +KnowledgeBaseParseService parseService
        +KnowledgeBasePersistenceService persistenceService
        +FileStorageService storageService
        +KnowledgeBaseRepository knowledgeBaseRepository
        +FileValidationService fileValidationService
        +FileHashService fileHashService
        +VectorizeStreamProducer vectorizeStreamProducer
        +uploadKnowledgeBase(file,name,category) Map
        +revectorize(kbId) void
    }

    class KnowledgeBaseQueryService {
        +LlmProviderRegistry llmProviderRegistry
        +KnowledgeBaseVectorService vectorService
        +KnowledgeBaseListService listService
        +KnowledgeBaseCountService countService
        +PromptTemplate systemPromptTemplate
        +PromptTemplate userPromptTemplate
        +PromptTemplate rewritePromptTemplate
        +answerQuestion(kbIds,question) String
        +answerQuestionStream(kbIds,question,history) Flux
        +queryKnowledgeBase(request) QueryResponse
    }

    class KnowledgeBaseVectorService {
        +VectorStore vectorStore
        +TextSplitter textSplitter
        +VectorRepository vectorRepository
        +vectorizeAndStore(kbId,content) void
        +similaritySearch(query,kbIds,topK,minScore) List~Document~
        +deleteByKnowledgeBaseId(kbId) void
    }

    class KnowledgeBaseListService {
        +KnowledgeBaseRepository knowledgeBaseRepository
        +RagChatMessageRepository ragChatMessageRepository
        +KnowledgeBaseMapper knowledgeBaseMapper
        +FileStorageService fileStorageService
        +listKnowledgeBases(status,sortBy) List~DTO~
        +getKnowledgeBase(id) Optional~DTO~
        +getKnowledgeBaseNames(ids) List~String~
        +search(keyword) List~DTO~
        +getStatistics() KnowledgeBaseStatsDTO
        +downloadFile(id) byte[]
        +getAllCategories() List~String~
        +listByCategory(category) List~DTO~
        +updateCategory(id,category) void
    }

    class KnowledgeBaseDeleteService {
        +KnowledgeBaseRepository knowledgeBaseRepository
        +RagChatSessionRepository sessionRepository
        +KnowledgeBaseVectorService vectorService
        +FileStorageService storageService
        +deleteKnowledgeBase(id) void
    }

    class KnowledgeBaseCountService {
        +KnowledgeBaseRepository knowledgeBaseRepository
        +updateQuestionCounts(kbIds) void
    }

    class KnowledgeBaseParseService {
        +DocumentParseService documentParseService
        +ContentTypeDetectionService contentTypeDetectionService
        +FileStorageService storageService
        +parseContent(file) String
        +parseContent(bytes,fileName) String
        +downloadAndParseContent(storageKey,filename) String
        +detectContentType(file) String
    }

    class KnowledgeBasePersistenceService {
        +KnowledgeBaseRepository knowledgeBaseRepository
        +handleDuplicateKnowledgeBase(kb,hash) Map
        +saveKnowledgeBase(file,name,category,key,url,hash) KnowledgeBaseEntity
        +updateVectorStatusToPending(kbId) void
    }

    class RagChatSessionService {
        +RagChatSessionRepository sessionRepository
        +RagChatMessageRepository messageRepository
        +KnowledgeBaseRepository knowledgeBaseRepository
        +KnowledgeBaseQueryService queryService
        +RagChatMapper ragChatMapper
        +KnowledgeBaseMapper knowledgeBaseMapper
        +KnowledgeBaseQueryProperties queryProperties
        +createSession(request) SessionDTO
        +listSessions() List~SessionListItemDTO~
        +getSessionDetail(sessionId) SessionDetailDTO
        +prepareStreamMessage(sessionId,question) Long
        +completeStreamMessage(messageId,content) void
        +getStreamAnswer(sessionId,question) Flux
        +updateSessionTitle(sessionId,title) void
        +togglePin(sessionId) void
        +deleteSession(sessionId) void
    }

    class KnowledgeBaseQueryProperties {
        +Rewrite rewrite
        +Search search
        +History history
        +String systemPromptPath
        +String userPromptPath
        +String rewritePromptPath
    }

    %% ===== Listener =====
    class VectorizeStreamProducer {
        +KnowledgeBaseRepository knowledgeBaseRepository
        +sendVectorizeTask(kbId,content) void
    }
    class VectorizeStreamConsumer {
        +KnowledgeBaseVectorService vectorService
        +KnowledgeBaseRepository knowledgeBaseRepository
    }

    %% ===== Repository =====
    class KnowledgeBaseRepository {
        <<interface>>
        +findByFileHash(hash) Optional~Entity~
        +findAllByOrderByUploadedAtDesc() List~Entity~
        +findAllCategories() List~String~
        +findByCategoryOrderByUploadedAtDesc(cat) List~Entity~
        +searchByKeyword(keyword) List~Entity~
        +incrementQuestionCountBatch(ids) int
        +countByVectorStatus(status) long
        +sumAccessCount() long
        +findByVectorStatusOrderByUploadedAtDesc(status) List~Entity~
    }

    class VectorRepository {
        +JdbcTemplate jdbcTemplate
        +deleteByKnowledgeBaseId(kbId) int
    }

    class RagChatSessionRepository {
        <<interface>>
        +findAllOrderByPinnedAndUpdatedAtDesc() List~Entity~
        +findByKnowledgeBaseIds(kbIds) List~Entity~
        +findByIdWithKnowledgeBases(id) Optional~Entity~
    }

    class RagChatMessageRepository {
        <<interface>>
        +findBySessionIdOrderByMessageOrderAsc(sessionId) List~Entity~
        +findRecentCompletedBySessionId(sessionId,pageable) List~Entity~
        +countByType(type) long
    }

    %% ===== Model =====
    class KnowledgeBaseEntity {
        +Long id
        +String fileHash
        +String name
        +String category
        +String originalFilename
        +Long fileSize
        +String contentType
        +String storageKey
        +String storageUrl
        +LocalDateTime uploadedAt
        +Integer accessCount
        +Integer questionCount
        +VectorStatus vectorStatus
        +String vectorError
        +Integer chunkCount
    }

    class RagChatSessionEntity {
        +Long id
        +String title
        +SessionStatus status
        +Set~KnowledgeBaseEntity~ knowledgeBases
        +List~RagChatMessageEntity~ messages
        +Integer messageCount
        +Boolean isPinned
        +getKnowledgeBaseIds() List~Long~
    }

    class RagChatMessageEntity {
        +Long id
        +RagChatSessionEntity session
        +MessageType type
        +String content
        +Integer messageOrder
        +Boolean completed
    }

    class VectorStatus {
        <<enum>>
        PENDING
        PROCESSING
        COMPLETED
        FAILED
    }

    class QueryRequest {
        <<record>>
        +List~Long~ knowledgeBaseIds
        +String question
    }

    class QueryResponse {
        <<record>>
        +String answer
        +Long knowledgeBaseId
        +String knowledgeBaseName
    }

    class KnowledgeBaseListItemDTO {
        <<record>>
        +Long id
        +String name
        +String category
        +VectorStatus vectorStatus
        +Integer chunkCount
    }

    class KnowledgeBaseStatsDTO {
        <<record>>
        +long totalCount
        +long totalQuestionCount
        +long totalAccessCount
        +long completedCount
        +long processingCount
    }

    class RagChatDTO {
        <<record>>
        +CreateSessionRequest
        +SendMessageRequest
        +SessionDTO
        +SessionListItemDTO
        +SessionDetailDTO
        +MessageDTO
    }

    %% ===== Relationships =====
    KnowledgeBaseController --> KnowledgeBaseUploadService
    KnowledgeBaseController --> KnowledgeBaseQueryService
    KnowledgeBaseController --> KnowledgeBaseListService
    KnowledgeBaseController --> KnowledgeBaseDeleteService

    RagChatController --> RagChatSessionService

    RagChatSessionService --> KnowledgeBaseQueryService
    RagChatSessionService --> KnowledgeBaseQueryProperties
    RagChatSessionService --> RagChatSessionRepository
    RagChatSessionService --> RagChatMessageRepository
    RagChatSessionService --> KnowledgeBaseRepository

    KnowledgeBaseUploadService --> KnowledgeBaseParseService
    KnowledgeBaseUploadService --> KnowledgeBasePersistenceService
    KnowledgeBaseUploadService --> KnowledgeBaseRepository
    KnowledgeBaseUploadService --> VectorizeStreamProducer

    KnowledgeBaseQueryService --> KnowledgeBaseVectorService
    KnowledgeBaseQueryService --> KnowledgeBaseListService
    KnowledgeBaseQueryService --> KnowledgeBaseCountService
    KnowledgeBaseQueryService --> KnowledgeBaseQueryProperties

    KnowledgeBaseVectorService --> VectorRepository

    KnowledgeBaseListService --> KnowledgeBaseRepository
    KnowledgeBaseListService --> RagChatMessageRepository

    KnowledgeBaseDeleteService --> KnowledgeBaseVectorService
    KnowledgeBaseDeleteService --> KnowledgeBaseRepository
    KnowledgeBaseDeleteService --> RagChatSessionRepository

    KnowledgeBaseCountService --> KnowledgeBaseRepository

    VectorizeStreamProducer --> KnowledgeBaseRepository
    VectorizeStreamConsumer --> KnowledgeBaseVectorService
    VectorizeStreamConsumer --> KnowledgeBaseRepository

    KnowledgeBaseRepository --> KnowledgeBaseEntity
    RagChatSessionRepository --> RagChatSessionEntity
    RagChatMessageRepository --> RagChatMessageEntity
    VectorRepository --> VectorStore
```

### 3.2 实体关系（ER 图）

```mermaid
erDiagram
    knowledge_bases {
        bigint id PK
        varchar fileHash UK "SHA-256"
        varchar name
        varchar category
        varchar originalFilename
        bigint fileSize
        varchar contentType
        varchar storageKey "RustFS key"
        varchar storageUrl "RustFS URL"
        timestamp uploadedAt
        timestamp lastAccessedAt
        int accessCount
        int questionCount
        varchar vectorStatus "PENDING/PROCESSING/COMPLETED/FAILED"
        varchar vectorError
        int chunkCount
    }

    rag_chat_sessions {
        bigint id PK
        varchar title
        varchar status "ACTIVE/ARCHIVED"
        int messageCount
        boolean isPinned
        timestamp createdAt
        timestamp updatedAt
    }

    rag_chat_messages {
        bigint id PK
        bigint session_id FK
        varchar type "USER/ASSISTANT"
        text content
        int messageOrder
        boolean completed
        timestamp createdAt
        timestamp updatedAt
    }

    rag_session_knowledge_bases {
        bigint session_id FK
        bigint knowledge_base_id FK
    }

    vector_store {
        uuid id PK
        text content
        vector embedding "1024d, COSINE_DISTANCE"
        jsonb metadata "包含 kb_id, kb_id_long"
    }

    rag_chat_sessions ||--o{ rag_chat_messages : "has"
    rag_chat_sessions }o--o{ knowledge_bases : "via rag_session_knowledge_bases"
    rag_session_knowledge_bases }o--|| rag_chat_sessions : "belongs to"
    rag_session_knowledge_bases }o--|| knowledge_bases : "belongs to"
    vector_store }o--|| knowledge_bases : "references via kb_id metadata"
```

---

## 四、核心设计模式与架构决策

| 维度 | 选型 | 说明 |
|------|------|------|
| **架构风格** | 分层架构（Controller → Service → Repository） | 清晰的职责分离，便于单元测试 |
| **异步处理** | 生产者-消费者模式（Redis Stream） | 文件上传后异步向量化，不阻塞上传响应 |
| **向量检索** | Spring AI PgVectorStore + HNSW 索引 | 基于 PostgreSQL 的 COSINE_DISTANCE 相似度搜索 |
| **流式响应** | SSE (Server-Sent Events) + Project Reactor | 实时流式输出 LLM 回答，支持多轮上下文 |
| **查询优化** | Query Rewrite + 动态检索参数 | 短查询用 LLM 扩展语义，按长度动态调整 topK/minScore |
| **去重机制** | SHA-256 文件哈希 | 相同内容文件只存储一次，仅更新访问计数 |
| **文件存储** | RustFS (分布式文件系统) | 文件内容与元数据分离，存储层解耦 |
| **配置管理** | @ConfigurationProperties + 嵌套静态类 | 按 Rewrite/Search/History 分组，类型安全 |
| **DTO 转换** | MapStruct (KnowledgeBaseMapper) | 编译期生成映射代码，避免运行时反射开销 |
| **数据一致性** | @Transactional 声明式事务 | 删除操作保证关联数据的事务一致性 |
| **限流保护** | @RateLimit 注解 | 全局和 IP 级别的请求限流，防止滥用 |