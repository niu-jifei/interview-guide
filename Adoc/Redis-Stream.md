
# Redis Stream Producer 生产者
```text
Spring容器启动
    ↓
创建AnalyzeStreamProducer Bean
    ↓
执行构造方法
    ├─ 调用super(redisService) → AbstractStreamProducer构造方法
    └─ 保存resumeRepository依赖
    ↓
应用层调用sendAnalyzeTask()
    ↓
sendTask()方法执行 (在调用线程中)
    ├─ 调用buildMessage()构建消息Map
    ├─ 调用redisService.streamAdd()发送到Redis Stream
    │   ├─ 指定streamKey()
    │   ├─ 设置消息最大长度限制
    │   └─ 返回messageId
    ├─ 记录成功日志
    └─ 如果发生异常 → 调用onSendFailed()处理失败情况

```
关键组件关系
AnalyzeStreamProducer 继承自 AbstractStreamProducer<AnalyzeTaskPayload>
AbstractStreamProducer 是通用的 Redis Stream 生产者抽象类
AnalyzeStreamProducer 实现了具体的业务逻辑（构建简历分析任务消息）

# Redis Stream Consumer

```text
Spring容器启动
    ↓
创建AnalyzeStreamConsumer Bean
    ↓
执行构造方法
    ├─ 调用super(redisService) → AbstractStreamConsumer构造方法
    └─ 保存gradingService, persistenceService, resumeRepository依赖
    ↓
执行@PostConstruct init()方法 (在主线程中)
    ├─ 生成唯一消费者名称 (consumerPrefix + UUID)
    ├─ 创建单线程线程池 (ThreadPoolExecutor)
    │   └─ 1个核心线程，1个最大线程，LinkedBlockingQueue作为工作队列
    ├─ 设置running = true
    ├─ 向线程池提交startConsumer任务
    └─ 记录启动日志
    ↓
startConsumer 方法执行 (在工作线程中)
    ├─ 创建Redis Stream消费者组
    └─ 调用consumeLoop()
    ↓
consumeLoop循环执行 (在工作线程中)
    ├─ 检查running状态 (while(running.get()))
    ├─ 调用redisService.streamConsumeMessages()
    │   └─ 从Redis Stream拉取消息
    ├─ 解析消息 → parsePayload()
    ├─ 标记处理中 → markProcessing()
    ├─ 处理业务逻辑 → processBusiness()
    └─ 确认消息处理完成

```
关键组件关系
- AnalyzeStreamConsumer 继承自 AbstractStreamConsumer<AnalyzePayload>
- AbstractStreamConsumer 是通用的 Redis Stream 消费者抽象类
- AnalyzeStreamConsumer 实现了具体的业务逻辑（简历分析）

线程模型
主线程：Spring 容器启动时执行构造方法和 @PostConstruct 方法
工作线程：由线程池创建，执行 startConsumer 和 consumeLoop，持续监听和处理 Redis Stream 消息

生命周期管理
当 Spring 容器关闭时，@PreDestroy 方法会被调用
shutdown() 方法会设置 running = false，使消费循环停止
线程池会被优雅关闭

这种设计实现了异步消息处理，避免阻塞主程序流程，同时提供了良好的生命周期管理和线程安全保证。


生产者与消费者对比
组件类型	启动时机	执行线程	主要职责
生产者	应用层调用时	调用方线程	构建消息并发送到 Redis Stream
消费者	Spring 启动时	工作线程	持续从 Redis Stream 拉取消息并处理


消息流向
```text
应用层触发 → AnalyzeStreamProducer.sendAnalyzeTask() → AbstractStreamProducer.sendTask() → RedisService.streamAdd() → Redis Stream → AnalyzeStreamConsumer.consumeLoop() → processBusiness()

```