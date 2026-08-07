# 前端 api 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/frontend/src/api/`

## 文件清单

| 文件名 | 职责 | 关键特征 |
|--------|------|----------|
| `request.ts` | Axios 封装，统一请求/响应拦截 | HTTP 200 + Result 格式，60s 超时 |
| `resume.ts` | 简历 API（上传、列表、详情、删除、分析） | FormData 上传，5 分钟超时 |
| `interview.ts` | 模拟面试 API（创建会话、答题、报告） | SSE 流式接口 |
| `history.ts` | 面试记录 API（列表、详情、PDF 导出） | 分页查询 |
| `knowledgebase.ts` | 知识库 API（上传、查询、列表、向量化） | 流式 SSE 查询 |
| `skill.ts` | Skill 管理 API（列表、分类） | 静态配置加载 |
| `voiceInterview.ts` | 语音面试 API（创建会话、状态查询） | WebSocket 连接配置 |
| `interviewSchedule.ts` | 面试日程 API（CRUD、AI 解析） | 日历数据格式 |
| `llmProvider.ts` | LLM Provider 管理 API | 加密 API Key |

## request.ts 核心设计

### 响应拦截器
```typescript
// 后端约定：所有响应都是 HTTP 200 + Result
// - code === 200 → 成功，返回 data
// - code !== 200 → 失败，直接显示 message
```

### 特殊处理
- **文件上传**：5 分钟超时（与 Nginx `proxy_read_timeout` 对齐）
- **下载**：提供 `getInstance()` 获取原始 Axios 实例处理 Blob
- **网络错误**：区分上传失败和普通网络错误

### API 方法
- `get<T>`, `post<T>`, `put<T>`, `patch<T>`, `delete<T>`：标准 REST 方法
- `upload<T>`：文件上传专用（FormData + 长超时）
- `getInstance()`：获取原始 Axios 实例

## 协作关系
- 所有页面组件 → 对应 API 模块 → `request.ts` → Axios
- API 模块按业务模块组织，与后端 Controller 一一对应
