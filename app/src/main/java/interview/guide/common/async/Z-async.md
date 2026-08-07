# common/async 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/common/async/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `AbstractStreamProducer.java` | Redis Stream 生产者模板基类，统一消息发送骨架 | 抽象类，`sendTask()` 模板方法 |
| `AbstractStreamConsumer.java` | Redis Stream 消费者模板基类，自动启停、重试、ACK | 抽象类，`@PostConstruct` 自动启动 |

## 文件协作关系
- 各模块 Producer（如 `AnalyzeStreamProducer`）→ 继承 `AbstractStreamProducer`：实现 `buildMessage()` 等方法
- 各模块 Consumer（如 `AnalyzeStreamConsumer`）→ 继承 `AbstractStreamConsumer`：实现 `processBusiness()` 等方法
- `AbstractStreamConsumer` → `RedisService`：调用 `streamConsumeMessages()` 消费消息
- `AbstractStreamProducer` → `RedisService`：调用 `streamAdd()` 发送消息

## Redis Stream 专项标注
- **线程模型**：每个 Consumer 独立 `ThreadPoolExecutor`（单线程），守护线程
- **重试机制**：最大重试 3 次（`AsyncTaskStreamConstants.MAX_RETRY_COUNT`），超过后标记 FAILED
- **ACK 机制**：处理成功/失败后均调用 `streamAck()` 确认消息
- **实体删除保护**：消费前校验实体是否存在，不存在直接 ACK 丢弃
- **三个管道**：
  - 简历分析：`AnalyzeStreamProducer/Consumer`
  - 知识库向量化：`VectorizeStreamProducer/Consumer`
  - 面试评估：`EvaluateStreamProducer/Consumer`
  - 语音面试评估：`VoiceEvaluateStreamProducer/Consumer`
