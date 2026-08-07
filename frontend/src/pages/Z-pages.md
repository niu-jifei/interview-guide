# 前端 pages 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/frontend/src/pages/`

## 文件清单

| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `UploadPage.tsx` | 简历上传页面 | 拖拽上传、文件校验、进度条 |
| `HistoryPage.tsx` | 简历库列表页 | 分页、搜索、分析状态展示 |
| `ResumeDetailPage.tsx` | 简历详情页 | AI 评分展示、一键面试 |
| `InterviewPage.tsx` | 模拟面试页面 | 多轮问答、倒计时、SSE 流式 |
| `InterviewHistoryPage.tsx` | 面试记录列表 | 分页、详情跳转、PDF 导出 |
| `KnowledgeBaseQueryPage.tsx` | 知识库问答页 | 多知识库选择、SSE 流式聊天 |
| `KnowledgeBaseUploadPage.tsx` | 知识库上传页 | 文件上传、向量化状态 |
| `KnowledgeBaseManagePage.tsx` | 知识库管理页 | 列表、分类、搜索、统计 |
| `VoiceInterviewPage.tsx` | 语音面试页 | WebSocket 实时音频、STT/TTS |
| `VoiceInterviewEvaluationPage.tsx` | 语音面试评估报告 | 多维评分、对话回放 |
| `InterviewSchedulePage.tsx` | 面试日程页 | 日历视图、AI 解析邀请 |
| `InterviewHubPage.tsx` | 面试中心页 | 统一入口、Skill 选择、JD 匹配 |
| `SettingsPage.tsx` | 设置页 | LLM Provider 管理、ASR/TTS 配置 |

## 路由结构

```
/ → 重定向到 /history
/upload → 简历上传
/history → 简历库列表
/history/:resumeId → 简历详情
/interview-hub → 面试中心
/interviews → 面试记录列表
/interviews/:sessionId → 面试详情报告
/interview → 模拟面试（无简历）
/interview/:resumeId → 模拟面试（带简历）
/voice-interview → 语音面试
/voice-interview/:sessionId/evaluation → 语音评估报告
/knowledgebase → 知识库管理
/knowledgebase/upload → 知识库上传
/knowledgebase/chat → 知识库问答
/interview-schedule → 面试日程
/settings → 设置
```

## 页面包装器模式

`App.tsx` 中使用包装器模式（`*Wrapper`）：
- 注入路由参数（`useParams`）
- 注入导航逻辑（`useNavigate`）
- 注入上下文（`useOutletContext`）
- 处理加载状态和错误边界

## 核心流程

### 简历流程
```
上传 → 简历库 → 详情 → 一键面试 → 面试记录
```

### 面试流程
```
面试中心 → 选择 Skill/难度 → 创建会话 → 逐题答题 → 交卷 → 异步评估 → 查看报告
```

### 知识库流程
```
上传 → 向量化（异步） → 管理列表 → 选择知识库 → 流式问答
```

### 语音面试流程
```
创建会话 → WebSocket 连接 → 实时音频流 → 评估 → 查看报告
```
