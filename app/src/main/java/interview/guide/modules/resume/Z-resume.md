# resume 模块包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/modules/resume/`

## 文件清单

### Controller 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ResumeController.java` | 简历管理入口（上传、列表、详情、导出、删除、重试、健康检查） | `@RestController`，`@RateLimit` |

### Service 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ResumeUploadService.java` | 简历上传编排（校验→解析→存储→入队异步分析） | `@Service`，`@Transactional` |
| `ResumeParseService.java` | 简历文本解析（委托 DocumentParseService） | `@Service` |
| `ResumeGradingService.java` | AI 简历评分（LLM 结构化输出） | `@Service`，`BeanOutputConverter` |
| `ResumePersistenceService.java` | 简历数据持久化（CRUD） | `@Service` |
| `ResumeHistoryService.java` | 简历历史查询、PDF 导出 | `@Service` |
| `ResumeDeleteService.java` | 简历删除（含关联分析结果、面试会话、存储文件） | `@Service` |
| `ResumeAnalysisProperties.java` | Prompt 模板路径配置 | `@ConfigurationProperties` |

### Listener（异步）
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `AnalyzeStreamProducer.java` | 简历分析任务生产者 | 继承 `AbstractStreamProducer` |
| `AnalyzeStreamConsumer.java` | 简历分析任务消费者（调用 AI 评分） | 继承 `AbstractStreamConsumer` |

### Model 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ResumeEntity.java` | 简历持久化实体（含去重 hash、分析状态） | `@Entity`，`@Table(name = "resumes")` |
| `ResumeAnalysisEntity.java` | 简历分析结果实体（评分维度、JSON 字段） | `@Entity`，`@Table(name = "resume_analyses")` |
| `ResumeListItemDTO.java` | 简历列表项 DTO（含最新分数、面试次数） | `record` |
| `ResumeDetailDTO.java` | 简历详情 DTO（含分析历史、面试历史） | `record`，内嵌 `AnalysisHistoryDTO` |

### Repository 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ResumeRepository.java` | 简历数据访问（按 hash 去重查询） | `JpaRepository` |
| `ResumeAnalysisRepository.java` | 分析结果数据访问（按 resume 查询） | `JpaRepository` |

### 其他
| 文件名 | 职责 |
|--------|------|
| `package-info.java` | 模块包描述 |

---

## 分层架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Controller 层                                │
│                  ResumeController (@RestController)                  │
│   uploadAndAnalyze() | getAllResumes() | getResumeDetail()          │
│   exportAnalysisPdf() | deleteResume() | reanalyze() | health()     │
└──────────────┬──────────────────────────────────┬───────────────────┘
               │                                  │
    ┌──────────┴──────────┐        ┌──────────────┴──────────────┐
    │     upload +         │        │    query + export + delete  │
    │     reanalyze        │        │                             │
    ▼                     ▼        ▼                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Service 层 编排                               │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ ResumeUploadService│  │ ResumeHistoryService│ │ResumeDeleteService│  │
│  │  · 文件校验       │  │  · 列表查询       │  │  · 删除存储文件   │  │
│  │  · 去重检查       │  │  · 详情查询       │  │  · 删除面试会话   │  │
│  │  · 解析编排       │  │  · PDF 导出       │  │  · 删除 DB 记录   │  │
│  │  · 存储 + 入库    │  └────────┬─────────┘  └────────┬─────────┘  │
│  │  · 发送异步任务    │           │                      │           │
│  └────────┬─────────┘            │                      │           │
│           │                      │                      │           │
│           ▼                      ▼                      ▼           │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    ResumePersistenceService                    │   │
│  │   · 文件去重(hash)  · 保存简历   · 保存分析结果               │   │
│  │   · 查询简历/分析   · 实体转DTO  · 级联删除                   │   │
│  └──────────┬───────────────────────────────────────────────────┘   │
│             │                                                       │
│             ▼                                                       │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐    │
│  │   ResumeParseService  │  │     ResumeGradingService         │    │
│  │   委托 Tika 解析文本  │  │  · 构建 Prompt 模板             │    │
│  │   下载存储文件重解析  │  │  · LLM 结构化输出               │    │
│  └──────────────────────┘  │  · DTO 转业务对象                │    │
│                            └──────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
         │                        │                       │
         ▼                        ▼                       ▼
┌──────────────────┐  ┌──────────────────────┐  ┌────────────────────┐
│  Infrastructure  │  │  Infrastructure      │  │  Infrastructure    │
│  FileValidation  │  │  FileStorageService  │  │  PdfExportService  │
│  ContentTypeDetect│  │  DocumentParse      │  │  Mapper(JSON)     │
│  FileHashService │  │  (Tika)              │  │  Mapper(MapStruct)│
└──────────────────┘  └──────────────────────┘  └────────────────────┘
         │                        │                       │
         ▼                        ▼                       │
┌──────────────────────────────────────────────────────────┘
│  Redis Stream (异步管道)
│  ┌──────────────────────┐    ┌────────────────────────┐
│  │ AnalyzeStreamProducer│───▶│  AnalyzeStreamConsumer │
│  │ 发送分析任务到Stream  │    │  · 解析消息            │
│  │                      │    │  · 调用 AI 评分        │
│  └──────────────────────┘    │  · 保存分析结果         │
│                              │  · 更新状态(处理中/完成)│
│                              │  · 重试机制(最多3次)    │
│                              └────────────────────────┘
└─────────────────────────────────────────────────────────────────┘
         │                        │
         ▼                        ▼
┌──────────────────────┐  ┌──────────────────────────────┐
│  LLM Provider        │  │  PostgreSQL + pgvector       │
│  · LlmProviderRegis  │  │  · resumes 表                │
│  · StructuredOutput  │  │  · resume_analyses 表        │
│  · BeanOutputConv    │  │  · interview_sessions 表     │
└──────────────────────┘  └──────────────────────────────┘
         │
         ▼
┌──────────────────────┐
│  S3 (RustFS/MinIO)   │
│  文件存储/删除       │
└──────────────────────┘
```

---

## 实体关系图

```
┌─────────────────────────┐        ┌──────────────────────────────┐
│      ResumeEntity       │        │     ResumeAnalysisEntity     │
├─────────────────────────┤        ├──────────────────────────────┤
│ id (PK)                 │◄───────┤ id (PK)                      │
│ fileHash (UK)           │  1:N   │ resume_id (FK)               │
│ originalFilename        │        │ overallScore                 │
│ fileSize                │        │ contentScore (0-25)          │
│ contentType             │        │ structureScore (0-20)        │
│ storageKey              │        │ skillMatchScore (0-25)       │
│ storageUrl              │        │ expressionScore (0-15)       │
│ resumeText (TEXT)       │        │ projectScore (0-15)          │
│ uploadedAt              │        │ summary (TEXT)               │
│ lastAccessedAt          │        │ strengthsJson (TEXT)         │
│ accessCount             │        │ suggestionsJson (TEXT)       │
│ analyzeStatus (ENUM)    │        │ analyzedAt                   │
│ analyzeError            │        └──────────────────────────────┘
└─────────────────────────┘
```

- **ResumeEntity** 与 **ResumeAnalysisEntity** 为 1:N 关系（同份简历可多次重试分析）
- `fileHash` 唯一索引用于文件去重（SHA-256）
- `analyzeStatus` 状态流转：`PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`

---

## 核心调用链路

### 1. 上传简历（同步 + 异步）

```
用户上传文件
  │
  ▼
ResumeController.uploadAndAnalyze(file)
  │ @RateLimit(GLOBAL=5, IP=5)
  │
  ▼
ResumeUploadService.uploadAndAnalyze(file)
  │
  ├── 1. FileValidationService.validateFile(file, 10MB, "简历")
  │     └── 校验：文件大小、内容为空、扩展名
  │
  ├── 2. ResumeParseService.detectContentType(file)
  │     └── ContentTypeDetectionService.detectContentType(file)
  │
  ├── 3. FileValidationService.validateContentTypeByList(...)
  │     └── 校验：MIME 类型是否在允许列表内
  │
  ├── 4. ResumePersistenceService.findExistingResume(file)
  │     ├── FileHashService.calculateHash(file)        ← SHA-256
  │     ├── ResumeRepository.findByFileHash(hash)      ← 去重查询
  │     └── 如果存在 → 返回历史分析结果（duplicate=true）
  │
  ├── 5. ResumeParseService.parseResume(file)
  │     └── DocumentParseService.parseContent(file)     ← Apache Tika
  │
  ├── 6. FileStorageService.uploadResume(file)          ← 上传至 S3 (RustFS)
  │     └── FileStorageService.getFileUrl(fileKey)
  │
  ├── 7. ResumePersistenceService.saveResume(file, text, fileKey, fileUrl)
  │     ├── FileHashService.calculateHash(file)
  │     ├── ResumeEntity 构建（status = PENDING）
  │     └── ResumeRepository.save(resume)
  │
  ├── 8. AnalyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText)
  │     └── 发送消息到 Redis Stream (RESUME_ANALYZE_STREAM_KEY)
  │           消息字段: resumeId, content, retryCount=0
  │
  └── 9. 返回 Map{ resume, storage, duplicate=false }
        前端根据 resume.id 轮询 analyzeStatus
  │
  │  ─ ─ ─ ─ ─ ─ ─ ─ ─ 异步消费 ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
  │
  ▼
AnalyzeStreamConsumer.processBusiness(payload)
  │
  ├── 1. 校验 ResumeRepository.existsById(resumeId)    ← 实体是否被删除
  │
  ├── 2. markProcessing: 设置 status = PROCESSING
  │
  ├── 3. ResumeGradingService.analyzeResume(content)
  │     ├── 加载 System Prompt (classpath:prompts/resume-analysis-system.st)
  │     ├── 加载 User Prompt + 填充 resumeText 变量
  │     ├── LlmProviderRegistry.getDefaultChatClient()  ← 获取默认 LLM Provider
  │     ├── 构建 ChatClient + 附加格式指令 (outputConverter.getFormat())
  │     ├── StructuredOutputInvoker.invoke(...)         ← 带重试的结构化输出
  │     │     └── BeanOutputConverter<ResumeAnalysisResponseDTO>
  │     └── 转换 DTO → ResumeAnalysisResponse
  │
  ├── 4. ResumePersistenceService.saveAnalysis(resume, response)
  │     ├── ResumeMapper.toAnalysisEntity(response)     ← MapStruct 映射基础字段
  │     ├── ObjectMapper 手动序列化 strengthsJson / suggestionsJson
  │     └── ResumeAnalysisRepository.save(entity)
  │
  └── 5. markCompleted: 设置 status = COMPLETED
         （失败时 markFailed: status = FAILED + error 信息）
```

### 2. 查询简历列表

```
ResumeController.getAllResumes()
  │
  ▼
ResumeHistoryService.getAllResumes()
  │
  ├── ResumePersistenceService.findAllResumes()
  │     └── ResumeRepository.findAll()
  │
  └── 遍历简历列表：
        ├── ResumePersistenceService.getLatestAnalysis(id)
        │     └── ResumeAnalysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(id)
        │         → 获取最新分数、分析时间
        │
        └── InterviewPersistenceService.findByResumeId(id)
              → 获取面试次数
```

### 3. 查询简历详情

```
ResumeController.getResumeDetail(id)
  │
  ▼
ResumeHistoryService.getResumeDetail(id)
  │
  ├── ResumePersistenceService.findById(id) ← 查不到抛 RESUME_NOT_FOUND
  │
  ├── ResumePersistenceService.findAnalysesByResumeId(id)
  │     └── ResumeAnalysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id)
  │     └── ResumeMapper.toAnalysisHistoryDTOList(entities, strengthsExtractor, suggestionsExtractor)
  │           ├── ObjectMapper 解析 strengthsJson → List<String>
  │           └── ObjectMapper 解析 suggestionsJson → List<Object>
  │
  ├── InterviewPersistenceService.findByResumeId(id)
  │     └── InterviewMapper.toInterviewHistoryList(...)
  │
  └── 组装 ResumeDetailDTO{ analyses, interviews }
```

### 4. 导出 PDF

```
ResumeController.exportAnalysisPdf(id)
  │
  ▼
ResumeHistoryService.exportAnalysisPdf(resumeId)
  │
  ├── ResumePersistenceService.findById(id)              ← 查不到抛异常
  ├── ResumePersistenceService.getLatestAnalysisAsDTO(id) ← 无分析结果抛异常
  │
  └── PdfExportService.exportResumeAnalysis(resume, analysisDTO)
        └── 使用 iText 8 生成 PDF 字节流
             → 返回 ExportResult{ pdfBytes, filename }
```

### 5. 删除简历

```
ResumeController.deleteResume(id)
  │
  ▼
ResumeDeleteService.deleteResume(id)
  │
  ├── 1. ResumePersistenceService.findById(id)           ← 查不到抛 RESUME_NOT_FOUND
  │
  ├── 2. FileStorageService.deleteResume(storageKey)    ← 删除 S3 存储文件
  │                          失败仅 warn，不阻止级联删除
  │
  ├── 3. InterviewPersistenceService.deleteSessionsByResumeId(id)
  │     └── 级联删除面试会话（会自动删除面试答案）
  │
  └── 4. ResumePersistenceService.deleteResume(id)
        ├── 删除所有 ResumeAnalysisEntity
        └── 删除 ResumeEntity
```

### 6. 重新分析（重试）

```
ResumeController.reanalyze(id)
  │ @RateLimit(GLOBAL=2, IP=2)
  │
  ▼
ResumeUploadService.reanalyze(resumeId)
  │ @Transactional
  │
  ├── ResumeRepository.findById(id)                     ← 查不到抛 RESUME_NOT_FOUND
  │
  ├── 如果有缓存的 resumeText，直接使用
  │    否则 → ResumeParseService.downloadAndParseContent(storageKey, filename)
  │           → DocumentParseService.downloadAndParseContent(...)
  │
  ├── 重置状态：status = PENDING, analyzeError = null
  │    ResumeRepository.save(resume)
  │
  └── AnalyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText)
        └── 重新发送到 Redis Stream
```

---

## 异步任务管道（Redis Stream）

### 生产-消费模型

```
          AnalyzeStreamProducer                   AnalyzeStreamConsumer
                │                                       │
                │  sendAnalyzeTask(id, text)            │
                │                                       │
                ▼                                       │
        ┌──────────────────┐                            │
        │  Redis Stream    │                            │
        │  resume:analyze  │───────────────────────────▶│
        │  stream          │  XREADGROUP                │
        └──────────────────┘                            │
                                                        ▼
                                              ┌──────────────────┐
                                              │  parsePayload()  │ → AnalyzePayload(resumeId, content)
                                              │                  │
                                              │  markProcessing()│ → status = PROCESSING
                                              │                  │
                                              │  processBusiness │ → AI 评分 + 保存
                                              │                  │
                                              │  markCompleted() │ → status = COMPLETED
                                              │     /            │
                                              │  markFailed()    │ → status = FAILED
                                              │                  │
                                              │  retryMessage()  │ → 重新入队（最多 3 次）
                                              └──────────────────┘
```

### 常量定义（AsyncTaskStreamConstants）
| 常量 | 值 | 说明 |
|------|-----|------|
| `RESUME_ANALYZE_STREAM_KEY` | `resume:analyze:stream` | Stream Key |
| `RESUME_ANALYZE_GROUP_NAME` | `resume:analyze:group` | Consumer Group |
| `RESUME_ANALYZE_CONSUMER_PREFIX` | `resume:analyze:consumer` | Consumer 前缀 |
| `FIELD_RESUME_ID` | `resumeId` | 消息字段：简历 ID |
| `FIELD_CONTENT` | `content` | 消息字段：简历文本 |
| `FIELD_RETRY_COUNT` | `retryCount` | 消息字段：重试计数 |
| `STREAM_MAX_LEN` | `100000` | Stream 最大长度 |

### 重试机制
1. 失败时调用 `retryMessage()` → 将消息重新入队，`retryCount+1`
2. 最大重试 3 次，超过后调用 `markFailed()` → `status = FAILED`
3. 实体删除保护：`processBusiness()` 和 `saveAnalysis()` 前校验 `existsById()`，已删除则直接 ACK 丢弃

---

## 依赖关系矩阵

| 类 | 依赖注入 | 外部服务 |
|----|---------|---------|
| `ResumeUploadService` | `ResumeParseService`, `FileStorageService`, `ResumePersistenceService`, `AppConfigProperties`, `FileValidationService`, `AnalyzeStreamProducer`, `ResumeRepository` | S3, Redis |
| `ResumeParseService` | `DocumentParseService`, `ContentTypeDetectionService`, `FileStorageService` | Apache Tika |
| `ResumeGradingService` | `LlmProviderRegistry`, `StructuredOutputInvoker`, `ResumeAnalysisProperties`, `ResourceLoader` | LLM (DashScope) |
| `ResumePersistenceService` | `ResumeRepository`, `ResumeAnalysisRepository`, `ObjectMapper`, `ResumeMapper`, `FileHashService` | — |
| `ResumeHistoryService` | `ResumePersistenceService`, `InterviewPersistenceService`, `PdfExportService`, `ObjectMapper`, `ResumeMapper`, `InterviewMapper` | iText 8 |
| `ResumeDeleteService` | `ResumePersistenceService`, `InterviewPersistenceService`, `FileStorageService` | S3 |
| `AnalyzeStreamProducer` | `RedisService`, `ResumeRepository` | Redis |
| `AnalyzeStreamConsumer` | `RedisService`, `ResumeGradingService`, `ResumePersistenceService`, `ResumeRepository` | Redis, LLM |

---

## API 接口总览

| 方法 | 路径 | 限流 | 描述 |
|------|------|------|------|
| `POST` | `/api/resumes/upload` | GLOBAL=5, IP=5 | 上传简历 |
| `GET` | `/api/resumes` | — | 获取简历列表 |
| `GET` | `/api/resumes/{id}/detail` | — | 获取简历详情 |
| `GET` | `/api/resumes/{id}/export` | — | 导出 PDF 报告 |
| `DELETE` | `/api/resumes/{id}` | — | 删除简历 |
| `POST` | `/api/resumes/{id}/reanalyze` | GLOBAL=2, IP=2 | 重新分析（重试） |
| `GET` | `/api/resumes/health` | — | 健康检查 |

---

## 评分维度体系

| 维度 | 字段 | 满分 | 说明 |
|------|------|------|------|
| 内容完整性 | `contentScore` | 25 | 是否包含必要信息（个人信息、工作经历、教育背景等） |
| 结构清晰度 | `structureScore` | 20 | 排版布局、层次分明 |
| 技能匹配度 | `skillMatchScore` | 25 | 技能关键词与岗位匹配 |
| 表达专业性 | `expressionScore` | 15 | 语言表达、专业术语使用 |
| 项目经验 | `projectScore` | 15 | 项目描述的深度和效果 |
| **总分** | **overallScore** | **100** | |
