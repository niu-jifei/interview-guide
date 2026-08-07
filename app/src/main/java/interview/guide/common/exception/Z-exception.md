# common/exception 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/common/exception/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ErrorCode.java` | 错误码枚举，分域设计（1xxx-11xxx） | `@Getter`，`@AllArgsConstructor` |
| `BusinessException.java` | 业务异常基类，携带 code + message | `@Getter`，继承 `RuntimeException` |
| `GlobalExceptionHandler.java` | 全局异常处理器，统一返回 `Result.error()` | `@RestControllerAdvice` |
| `RateLimitExceededException.java` | 限流异常，独立异常类型 | 继承 `RuntimeException` |

## 文件协作关系
- 所有业务 Service → `throw new BusinessException(ErrorCode.XXX, "描述")`：抛出业务异常
- `GlobalExceptionHandler` → `BusinessException`：捕获并转换为 HTTP 200 + `Result.error(code, message)`
- `GlobalExceptionHandler` → `RateLimitExceededException`：捕获限流异常
- `RateLimitAspect` → `RateLimitExceededException`：限流触发时抛出
- `StructuredOutputInvoker` → `BusinessException`：结构化输出重试耗尽后抛出

## 错误码分域规则
| 域 | 范围 | 示例 |
|----|------|------|
| 通用 | 1xxx | BAD_REQUEST(400)、NOT_FOUND(404) |
| 简历 | 2xxx | RESUME_NOT_FOUND(2001) |
| 面试 | 3xxx | INTERVIEW_SESSION_NOT_FOUND(3001) |
| 存储 | 4xxx | STORAGE_UPLOAD_FAILED(4001) |
| 导出 | 5xxx | EXPORT_PDF_FAILED(5001) |
| 知识库 | 6xxx | KNOWLEDGE_BASE_NOT_FOUND(6001) |
| AI 服务 | 7xxx | AI_SERVICE_TIMEOUT(7002) |
| 限流 | 8xxx | RATE_LIMIT_EXCEEDED(8001) |
| 面试日程 | 9xxx | INTERVIEW_SCHEDULE_NOT_FOUND(9001) |
| 语音面试 | 10xxx | VOICE_SESSION_NOT_FOUND(10001) |
| Provider 管理 | 11xxx | PROVIDER_NOT_FOUND(11001) |
