# infrastructure/redis 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/infrastructure/redis/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `RedisService.java` | Redis 操作封装（键值、Hash、锁、Stream、计数器） | `@Service`，Redisson 4.0 |
| `InterviewSessionCache.java` | 面试会话缓存管理 | `@Service` |

## 文件协作关系
- `RedisService` → `RedissonClient`：底层 Redis 操作
- `AbstractStreamConsumer/Producer` → `RedisService`：Stream 消息收发
- `RateLimitAspect` → `RedissonClient`：Lua 脚本限流
- `InterviewSessionCache` → `RedisService`：会话状态缓存
- 业务模块 → `RedisService.executeWithLock()`：分布式锁保护并发操作

## Redisson 专项标注
- **键值操作**：`set/get/delete/expire`，支持 `getOrLoad` 缓存加载模式
- **Hash 操作**：`hSet/hGet/hGetAll/hDelete/hExists`
- **分布式锁**：`tryLock(waitTime, leaseTime, unit)`，`executeWithLock()` 模板方法
- **Stream 操作**：`streamAdd/streamConsumeMessages/streamAck/createStreamGroup`
  - 阻塞模式消费：`StreamReadGroupArgs.timeout()` 服务端等待
  - 容错处理：Redisson 4.0 `EmptyList` 类型转换异常静默处理
- **原子计数器**：`increment/decrement`
- **模式删除**：`deleteByPattern(pattern)`
