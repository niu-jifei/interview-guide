# 前端 components 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/frontend/src/components/`

## 文件清单

### 布局组件
| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `Layout.tsx` | 主布局（侧边栏 + 内容区） | 响应式、暗色模式 |

### 业务组件
| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `UnifiedInterviewModal.tsx` | 统一面试配置弹窗 | Skill 选择、难度、JD 匹配 |
| `InterviewDetailPanel.tsx` | 面试详情报告面板 | 多维评分、答题回顾 |
| `InterviewCard.tsx` | 面试记录卡片 | 状态标签、快捷操作 |
| `ResumeCard.tsx` | 简历卡片 | 分析状态、评分展示 |
| `KnowledgeBaseCard.tsx` | 知识库卡片 | 向量化状态、分类标签 |

### 通用组件
| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `MarkdownRenderer.tsx` | Markdown 渲染 | 代码高亮、数学公式 |
| `StreamingText.tsx` | 流式文本展示 | SSE 逐字输出动画 |
| `FileUpload.tsx` | 文件上传组件 | 拖拽、进度条、类型校验 |
| `StatusBadge.tsx` | 状态徽章 | 颜色映射、图标 |
| `EmptyState.tsx` | 空状态占位 | 插图、引导文案 |
| `LoadingSpinner.tsx` | 加载动画 | 旋转指示器 |

### 语音面试组件
| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `AudioRecorder.tsx` | 音频录制 | Web Audio API、PCM 编码 |
| `AudioPlayer.tsx` | 音频播放 | 流式播放、音量控制 |
| `WebSocketManager.tsx` | WebSocket 管理 | 重连、心跳、消息队列 |
| `SubtitleDisplay.tsx` | 字幕展示 | 实时 STT 结果 |

## 协作关系
- `Layout.tsx` → 所有页面：提供统一导航和侧边栏
- `UnifiedInterviewModal.tsx` → `InterviewPage.tsx`：传递面试配置
- `InterviewDetailPanel.tsx` → `InterviewHistoryPage.tsx`：展示报告详情
- `StreamingText.tsx` → SSE 接口：流式输出渲染
- `WebSocketManager.tsx` → `VoiceInterviewPage.tsx`：实时音频通信

## 技术栈
- **React 18**：函数组件 + Hooks
- **TypeScript**：严格类型检查
- **TailwindCSS 4**：原子化 CSS
- **Vite**：构建工具，代码分割
- **Lucide React**：图标库
- **React Router DOM**：路由管理
