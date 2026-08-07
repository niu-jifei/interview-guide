# common/aspect 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/common/aspect/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `RateLimitAspect.java` | 限流 AOP 切面，拦截 `@RateLimit` 注解方法 | `@Aspect`，`@Component`，Lua 脚本 |

## 文件协作关系
- `RateLimitAspect` → `RateLimit` 注解：`@Around` 拦截带注解的方法
- `RateLimitAspect` → `RedissonClient`：执行 Lua 脚本限流
- `RateLimitAspect` → `RateLimitExceededException`：限流触发时抛出异常
- Controller 方法 → `@RateLimit(dimension, count)`：标注限流规则

## 限流专项标注
- **Lua 脚本**：`resources/scripts/rate_limit_single.lua`，滑动时间窗口算法
- **脚本加载**：`@PostConstruct` 加载到 Redis，获取 SHA1，后续 `evalSha()` 执行
- **NOSCRIPT 容错**：Redis 重启后自动重新加载脚本
- **多维度限流**：`@Repeatable` 支持同一方法标注多个维度，各自独立计数
  - `GLOBAL`：全局限流
  - `IP`：按客户端 IP 限流（支持 X-Forwarded-For 等代理头）
  - `USER`：按用户 ID 限流
- **降级机制**：支持 `fallback` 属性指定降级方法
- **Redis Key 设计**：`ratelimit:{ClassName:MethodName}:dimension`（Hash Tag 分组）
