# interview 模块包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/modules/interview/`

## 文件清单

### Controller 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewController.java` | 模拟面试入口（创建会话、答题、报告、导出、删除） | `@RestController`，`@RateLimit` |

### Service 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewSessionService.java` | 面试会话生命周期管理（创建/恢复/答题/交卷/报告） | `@Service`，Redis 缓存优先 |
| `InterviewQuestionService.java` | AI 出题服务（Skill 驱动、并行出题、降级回退） | `@Service`，虚拟线程并行 |
| `AnswerEvaluationService.java` | 答案评估服务（单题即时反馈） | `@Service` |
| `InterviewPersistenceService.java` | 面试数据持久化（会话/答案/报告） | `@Service` |
| `InterviewHistoryService.java` | 面试历史查询、详情、PDF 导出 | `@Service` |
| `InterviewQuestionProperties.java` | Prompt 模板路径配置 | `@ConfigurationProperties` |

### Skill 子模块
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewSkillController.java` | Skill 管理 API（列表、详情、分类） | `@RestController` |
| `InterviewSkillService.java` | Skill 加载与分类管理（从 YAML 配置） | `@Service` |
| `InterviewSkillProperties.java` | Skill 配置属性 | `@ConfigurationProperties` |

### Listener（异步）
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `EvaluateStreamProducer.java` | 面试评估任务生产者 | 继承 `AbstractStreamProducer` |
| `EvaluateStreamConsumer.java` | 面试评估任务消费者（整体评估） | 继承 `AbstractStreamConsumer` |

### Model 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewSessionEntity.java` | 面试会话实体 | `@Entity` |
| `InterviewAnswerEntity.java` | 面试答案实体 | `@Entity` |
| `InterviewSessionDTO.java` | 会话 DTO（含问题列表） | `record` |
| `InterviewQuestionDTO.java` | 问题 DTO（含追问） | `record` |
| `InterviewReportDTO.java` | 面试报告 DTO | `record` |
| `SubmitAnswerRequest/Response.java` | 答题请求/响应 | `record` |
| `CreateInterviewRequest.java` | 创建面试请求 | `record` |

### Repository 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewSessionRepository.java` | 会话数据访问 | `JpaRepository` |
| `InterviewAnswerRepository.java` | 答案数据访问 | `JpaRepository` |

## 文件协作关系
- `InterviewController` → `InterviewSessionService`：会话 CRUD
- `InterviewSessionService` → `InterviewQuestionService`：生成面试题
- `InterviewSessionService` → `InterviewSessionCache`：Redis 缓存会话状态
- `InterviewSessionService` → `AnswerEvaluationService`：单题评估
- `InterviewSessionService` → `EvaluateStreamProducer`：异步整体评估
- `InterviewQuestionService` → `InterviewSkillService`：加载 Skill 配置
- `InterviewQuestionService` → `StructuredOutputInvoker` + `LlmProviderRegistry`：AI 出题
- `InterviewQuestionService` → 虚拟线程并行：简历题 60% + 方向题 40%
- `EvaluateStreamConsumer` → `UnifiedEvaluationService`：整体评估
- `InterviewHistoryService` → `PdfExportService`：导出面试报告

## 核心流程
```
创建会话 → 加载 Skill → 并行出题(简历题+方向题) → 缓存(Redis) + 持久化(DB)
    ↓
逐题答题 → 更新缓存 → 保存答案 → 最后一题 → 入队异步评估
    ↓
异步评估 → 整体评分 → 更新状态 → 生成报告 → 可导出 PDF
```

## Spring AI 专项标注
- **出题模式**：`getPlainChatClient()` 不带 Tools，确保结构化输出
- **并行策略**：`CompletableFuture.supplyAsync()` + `newVirtualThreadPerTaskExecutor()`
- **降级机制**：简历题失败→全方向题，方向题失败→全简历题，均失败→回退默认题
- **历史去重**：`buildHistoricalSection()` 避免重复出已考知识点
- **缓存策略**：会话状态优先 Redis，未命中时从 DB 恢复并回填缓存
