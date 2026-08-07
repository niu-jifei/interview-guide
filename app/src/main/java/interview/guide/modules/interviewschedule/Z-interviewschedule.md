# interviewschedule 模块包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/modules/interviewschedule/`

## 文件清单

### Controller 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewScheduleController.java` | 面试日程管理入口（CRUD、AI 解析） | `@RestController` |

### Service 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewScheduleService.java` | 日程 CRUD 编排 | `@Service` |
| `InterviewParseService.java` | AI 解析面试邀请邮件/文本 | `@Service`，LLM 结构化输出 |
| `ScheduleStatusUpdater.java` | 日程状态定时更新 | `@Service`，`@Scheduled` |

### Model 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewScheduleEntity.java` | 日程实体 | `@Entity` |
| `InterviewScheduleDTO.java` | 日程 DTO | `record` |
| `InterviewStatus.java` | 日程状态枚举 | `enum` |
| `ParseRequest/Response.java` | AI 解析请求/响应 | `record` |
| `CreateInterviewRequest.java` | 创建日程请求 | `record` |

### Repository 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `InterviewScheduleRepository.java` | 日程数据访问 | `JpaRepository` |

## 文件协作关系
- `InterviewScheduleController` → `InterviewScheduleService`：日程 CRUD
- `InterviewScheduleController` → `InterviewParseService`：AI 解析面试邀请
- `InterviewParseService` → `LlmProviderRegistry` + `StructuredOutputInvoker`：LLM 解析
- `ScheduleStatusUpdater` → `InterviewScheduleService`：定时更新过期/即将开始的日程状态

## 核心功能
- 日历视图展示面试日程（前端 react-big-calendar）
- AI 解析面试邀请文本，自动提取时间、公司、岗位等信息
- 定时任务自动更新日程状态（即将开始、已过期等）
