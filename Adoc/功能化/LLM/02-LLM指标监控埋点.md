# 结构化输出（StructuredOutputInvoker）指标监控方案

> 适用范围：`app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java`
> 文档状态：调研 + 方案记录（**未改动任何代码**，等待确认后落地）
> 关联模块：`VoiceInterviewWebSocketHandler`（同样使用 `MeterRegistry`）、`LlmProviderRegistry`（使用 `ObservationRegistry`）

---

## 一、当前 `StructuredOutputInvoker` 的指标实现分析

### 1.1 依赖注入方式

```java
@Autowired(required = false) MeterRegistry meterRegistry
```

- 用 `@Autowired(required = false)` 注入 `MeterRegistry`，**没有就为 `null`，不报错**。
- 同时在构造函数里读 `StructuredOutputProperties.isStructuredMetricsEnabled()`，通过一个总开关 `metricsEnabled` 控制。
- 真正是否记录指标由 `isMetricsAvailable()` 决定：

```java
private boolean isMetricsAvailable() {
    return metricsEnabled && meterRegistry != null;
}
```

### 1.2 已定义的三个指标

| 指标名（MeterRegistry 原始名） | 类型 | 标签（Tags） | 含义 | 记录位置 |
|---|---|---|---|---|
| `app.ai.structured_output.invocations` | Counter | `context`、`status` | 一次 `invoke()` 调用的结果计数（成功/失败） | 成功在返回前、失败在抛异常前各记一次 |
| `app.ai.structured_output.attempts` | Counter | `context`、`status` | 每次 LLM 调用尝试（含重试）计数 | 每次 `try` 成功记 `success`，失败记 `failure` |
| `app.ai.structured_output.latency` | Timer | `context`、`status` | 单次 `invoke()` 端到端耗时 | `record(System.nanoTime() - startNanos, NANOSECONDS)` |

- `status` 取值：`success` / `failure`。
- `context` 取值：由调用方传入的 `logContext` 经 `normalizeContextTag()` 归一化（转小写、空格→下划线、去非字母数字、合并下划线、截断 48 字符），兜底为 `unknown`。

### 1.3 指标语义说明

- `invocations` 与 `attempts` 的区别：
  - 一次 `invoke()` 至少产生 1 次 `attempts`（首次尝试），若重试则累加。
  - 一次 `invoke()` 只产生 1 次 `invocations`（最终结果）。
  - 二者之差（attempts − invocations）可反映**重试率**，是诊断 LLM 输出不稳定的核心指标。
- `latency` 是 Timer，天然产生 count / sum / max，配合直方图可导出生 p50/p95/p99 延迟。

### 1.4 配套配置（`application.yml`）

```yaml
app:
  ai:
    structured-metrics-enabled: ${APP_AI_STRUCTURED_METRICS_ENABLED:true}  # 总开关
```

`StructuredOutputProperties` 中对应字段：`structuredMetricsEnabled`（默认 `true`）。

---

## 二、关键问题：当前指标是「休眠」状态，并未真正生效 ⚠️

**结论：类写得对，但运行时不会产生任何数据。**

### 2.1 原因

`MeterRegistry` 这个 Bean **不是 Spring 自动白送的**，它是 `spring-boot-starter-actuator`（具体是 `spring-boot-actuator-autoconfigure` 里的 `MeterRegistryAutoConfiguration`）自动配置的。本项目：

- `build.gradle` **没有** `spring-boot-starter-actuator`；
- `build.gradle` **没有** 任何 Micrometer 后端（Prometheus / JMX / Datadog 等 registry binder）；
- `application.yml` **没有** `management:` 配置块；
- 因此应用上下文中**不存在 `MeterRegistry` Bean**，`@Autowired(required = false)` 解析为 `null`，`isMetricsAvailable()` 永远返回 `false`，三个指标永远不记录。

> 说明：`io.micrometer.core.instrument.MeterRegistry` 能被 import 并编译通过，是因为 **micrometer-core 作为 Spring AI 的传递依赖已在编译/运行 classpath 上**（同目录 `LlmProviderRegistry` 还 import 了 `io.micrometer.observation.ObservationRegistry`）。但「类在 classpath」≠「有 Bean」，缺 actuator 就没有 `MeterRegistry` 实例。

### 2.2 佐证（全仓扫描）

- `build.gradle`：`actuator` / `micrometer` / `prometheus` 均为 0 命中。
- `application.yml`：`management` / `prometheus` / `actuator` 均为 0 命中。
- `MeterRegistry` 使用点只有两处，且都做了 null 容错（印证是「可选增强」写法）：
  - `StructuredOutputInvoker`：`@Autowired(required = false) MeterRegistry`
  - `VoiceInterviewWebSocketHandler`：`ObjectProvider<MeterRegistry> meterRegistryProvider` + `getRegistry()` 判空
- `LlmProviderRegistry` 使用 `ObservationRegistry`：Spring AI 的 ChatClient 观测也是靠 actuator 才能导出。

---

## 三、`MeterRegistry` 工作原理速览

`MeterRegistry` 是 Micrometer（Spring Boot 默认指标门面）的核心抽象：**应用只管埋点，后端由具体 Registry 决定**。

| 指标类型 | 用途 | 本项目使用 |
|---|---|---|
| `Counter` | 只增计数（请求数、错误数） | invocations / attempts |
| `Timer` | 耗时统计（count、sum、max、分位数） | latency |
| `Gauge` | 瞬时值（队列长度、连接数） | 暂未用 |
| `DistributionSummary` | 自定义分布（如 token 数） | 暂未用 |

| 常见后端 Registry | 引入方式 | 适用场景 |
|---|---|---|
| `SimpleMeterRegistry` | actuator 默认自带 | 本地/测试，内存态，不可跨进程采集 |
| `PrometheusMeterRegistry` | `micrometer-registry-prometheus` | 云原生，Prometheus 拉取 `/actuator/prometheus` |
| `JmxMeterRegistry` | `micrometer-registry-jmx` | 调试期用 JConsole/VisualVM 看 |
| `DatadogMeterRegistry` 等 | 对应 binder | SaaS 监控 |

**命名转换规则（重要）**：Micrometer 的点号名 `app.ai.structured_output.invocations` 在 Prometheus 导出时：
- 点 → 下划线：`app_ai_structured_output_invocations`
- Counter 追加 `_total`：`app_ai_structured_output_invocations_total`
- Timer 追加 `_seconds`（count/sum/histogram）：`app_ai_structured_output_latency_seconds_count` / `_sum` / `_bucket`
- 标签 `context` / `status` 变为 Prometheus label。

---

## 四、监控落地方案（待确认后部署）

### 方案 A：Prometheus + Grafana（推荐，云原生标准）

#### 步骤 1：加依赖（`app/build.gradle`）

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

> 版本由 Spring Boot / Micrometer BOM 统一管理，无需写版本号。

#### 步骤 2：`application.yml` 增加 `management:` 配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
    metrics:
      enabled: true
  metrics:
    tags:
      application: interview-guide
      env: ${APP_ENV:dev}
    distribution:
      percentiles-histogram:
        app.ai.structured_output.latency: true
      percentiles:
        app.ai.structured_output.latency: 0.5,0.9,0.95,0.99
    export:
      prometheus:
        enabled: true
        step: 1m
```

- `percentiles-histogram: true` + `percentiles` 让 `latency` Timer 导出分位数（p50/p90/p95/p99），这是调延迟告警的关键。
- `metrics.tags` 给所有指标附加全局标签（应用名、环境），多实例聚合时必备。

#### 步骤 3：Prometheus 抓取配置（`prometheus.yml`）

```yaml
scrape_configs:
  - job_name: 'interview-guide'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['localhost:8080']   # 应用端口
```

#### 步骤 4：Grafana 关键查询（PromQL）

```promql
# 调用总量（按状态）
sum(rate(app_ai_structured_output_invocations_total[5m])) by (status)

# 失败率
sum(rate(app_ai_structured_output_invocations_total{status="failure"}[5m]))
  / sum(rate(app_ai_structured_output_invocations_total[5m]))

# 重试率（attempts - invocations）
sum(rate(app_ai_structured_output_attempts_total[5m]))
  - sum(rate(app_ai_structured_output_invocations_total[5m]))

# P95 延迟（秒）
histogram_quantile(0.95, sum(rate(app_ai_structured_output_latency_seconds_bucket[5m])) by (le, context))
```

### 方案 B：JMX（零运维，本地/调试）

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-jmx'
```

`application.yml` 仅需 `management.endpoints.web.exposure.include: health,info,metrics`，然后用 JConsole / VisualVM 连接进程即可看到 `app.ai.structured_output.*` MBean。无需 Prometheus 服务器，适合开发联调验证埋点是否生效。

### 方案 C：Datadog / InfluxDB 等 SaaS

引入对应 binder（`micrometer-registry-datadog` / `micrometer-registry-influx`），在 `management.metrics.export.*` 配置 apiKey / uri 即可，埋点代码无需改。

---

## 五、针对本项目的优化建议（可选，未改动代码）

1. **Timer 分位数**：上面 `management.metrics.distribution` 已配置；若不配，Grafana 只能用 `_sum/_count` 算均值，看不到长尾。建议必配。
2. **全局标签**：`application`、`env` 务必加，否则多实例无法区分。
3. **高基数风险（重点）**：`context` 标签当前由调用方 `logContext` 决定，经 `normalizeContextTag` 只做了「格式归一 + 截断 48 字符」，并**未 hash 或枚举化**。若某调用方传入「用户 ID / 会话 ID」之类的动态值，`context` 标签基数会爆炸，拖垮 Prometheus。
   - 建议：约束各 `invoke()` 调用点的 `logContext` 只能传**稳定的业务语义字符串**（如 `resume_grading`、`interview_evaluation`），禁止传动态 ID。
4. **Spring AI 自带观测**：引入 actuator 后，`ChatClient` 调用会自动产生 `http.client.requests` / `gen_ai.*` Observation 指标（由 `LlmProviderRegistry` 的 `ObservationRegistry` 驱动），可顺带监控各 Provider 的 LLM 调用量、耗时、错误率，无需额外埋点。
5. **告警建议阈值**（Grafana Alert）：
   - 失败率 > 5% 持续 5 分钟 → 告警
   - P95 延迟 > 20s → 告警
   - 重试率（attempts−invocations）突增 → 提示模型输出质量下降

---

## 六、验证步骤（落地后自测）

1. 启动应用，`curl localhost:8080/actuator/prometheus | grep app_ai_structured_output`，应能看到 `app_ai_structured_output_invocations_total`、`app_ai_structured_output_attempts_total`、`app_ai_structured_output_latency_seconds_*`。
2. 跑一次触发结构化输出的接口（如面试评估 / 简历评分），观察对应指标 `context` 标签下的计数增长。
3. `/actuator/metrics/app.ai.structured_output.invocations` 也能返回 JSON 聚合结果。

---

## 七、结论与待办

| 项 | 状态 |
|---|---|
| `StructuredOutputInvoker` 埋点代码 | ✅ 已写好，逻辑正确（Counter×2 + Timer×1，标签合理） |
| `MeterRegistry` Bean 是否就绪 | ❌ 缺失（无 actuator，运行时为 null，指标休眠） |
| 监控导出配置 | ❌ 无 `management:` 块 |
| `VoiceInterviewWebSocketHandler` 指标 | ⚠️ 同样休眠，依赖同一次 actuator 引入一起激活 |
| Spring AI ChatClient 观测 | ⚠️ 引入 actuator 后自动生效 |

**下一步（需你确认再动手）**：
1. 选定方案 A（Prometheus+Grafana，推荐）或方案 B（JMX 调试）。
2. 由我补充 `build.gradle` 依赖 + `application.yml` 的 `management:` 配置（必要时补 `prometheus.yml`）。
3. （可选）审计所有 `invoke()` 调用点的 `logContext`，确保不传入高基数动态值。
