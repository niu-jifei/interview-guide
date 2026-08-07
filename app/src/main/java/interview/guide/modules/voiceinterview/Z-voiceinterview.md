# voiceinterview 模块包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/modules/voiceinterview/`

## 文件清单

### Controller 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceInterviewController.java` | 语音面试 REST API（创建会话、获取状态） | `@RestController` |

### Handler 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceInterviewWebSocketHandler.java` | WebSocket 实时双向音频处理 | `TextWebSocketHandler`，虚拟线程 |

### Service 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceInterviewService.java` | 语音面试核心业务编排 | `@Service` |
| `QwenAsrService.java` | 阿里云 Qwen3 ASR 实时语音识别 | WebSocket 连接 |
| `QwenTtsService.java` | 阿里云 Qwen3 TTS 实时语音合成 | WebSocket 连接 |
| `DashscopeLlmService.java` | LLM 流式回复（DashScope） | `@Service` |

### Listener（异步）
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceEvaluateStreamProducer.java` | 语音评估任务生产者 | 继承 `AbstractStreamProducer` |
| `VoiceEvaluateStreamConsumer.java` | 语音评估任务消费者 | 继承 `AbstractStreamConsumer` |

### Config 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceInterviewProperties.java` | 语音面试配置（阶段时长、TTS/ASR 参数） | `@ConfigurationProperties` |
| `WebSocketConfig.java` | WebSocket 注册配置 | `@Configuration` |

### Model 层
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `VoiceInterviewSessionEntity.java` | 语音面试会话实体 | `@Entity` |
| `VoiceInterviewEvaluationEntity.java` | 语音评估结果实体 | `@Entity` |
| `VoiceInterviewMessageDTO.java` | 消息 DTO | `record` |
| `WebSocketControlMessage.java` | WebSocket 控制消息 | `record` |
| `WebSocketSubtitleMessage.java` | WebSocket 字幕消息 | `record` |

## 文件协作关系
- 前端 WebSocket → `VoiceInterviewWebSocketHandler`：实时音频流
- `VoiceInterviewWebSocketHandler` → `QwenAsrService`：PCM 音频 → 文字识别
- `VoiceInterviewWebSocketHandler` → `DashscopeLlmService`：文字 → LLM 流式回复
- `VoiceInterviewWebSocketHandler` → `QwenTtsService`：文字 → PCM 音频合成
- `VoiceInterviewWebSocketHandler` → `VoiceInterviewService`：会话状态管理
- `VoiceInterviewWebSocketHandler` → 虚拟线程池：LLM/TTS 阻塞任务
- `VoiceEvaluateStreamConsumer` → `UnifiedEvaluationService`：整体评估

## WebSocket 专项标注
- **处理管道**：用户音频 → STT(Qwen ASR) → LLM(DashScope) → TTS(Qwen TTS) → AI 音频
- **线程模型**：
  - `utteranceMergeScheduler`：2 线程调度器，合并 STT 定稿
  - `voicePipelineExecutor`：`newVirtualThreadPerTaskExecutor()`，LLM/TTS/JDBC 阻塞工作
- **ASR 配置**：Qwen3-asr-flash-realtime，WebSocket 实时识别，VAD 静音检测 2000ms
- **TTS 配置**：Qwen3-tts-flash-realtime，Cherry 音色，分块合成（chunked-audio-enabled）
- **防回环**：AI 音频播放后 800ms 冷却期，防止麦克风拾取触发 STT
- **暂停超时**：4:30 警告，5:00 自动暂停
- **限流**：每会话 10 msg/s，每 IP 3 interview/h，全局 50 并发
