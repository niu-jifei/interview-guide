# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

InterviewGuide 是一个智能 AI 面试平台，后端基于 Spring Boot 4.0 + Java 21 + Spring AI 2.0，前端基于 React 18 + TypeScript + Vite。

## 常用命令

### 后端 (Gradle)

```bash
# 启动后端服务
./gradlew bootRun

# 编译打包
./gradlew build

# 运行单个测试
./gradlew test --tests "interview.guide.modules.resume.service.ResumeGradingServiceTest"

# 跳过测试打包
./gradlew build -x test
```

### 前端 (pnpm)

```bash
cd frontend
pnpm install
pnpm dev      # 开发服务器 (http://localhost:5173)
pnpm build    # 生产构建
```

### Docker

```bash
# 启动所有服务
docker-compose up -d --build

# 查看服务状态
docker-compose ps

# 查看后端日志
docker-compose logs -f app
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0, Spring AI 2.0 (阿里云 DashScope OpenAI 兼容) |
| 数据库 | PostgreSQL 14+ + pgvector (向量存储) |
| 缓存/消息 | Redis 6+ + Redisson 4.0 + Redis Stream |
| 文件存储 | S3 兼容存储 (RustFS/MinIO) |
| 文档解析 | Apache Tika 2.9.2 |
| PDF 导出 | iText 8 |
| 前端 | React 18, TypeScript 5.6, Vite 5.4, Tailwind CSS 4.1 |

## 架构要点

### 异步处理流程

简历分析、知识库向量化、面试评估报告生成采用 **Redis Stream** 异步处理：

```
上传请求 → 保存文件 → 发送消息到 Stream → 立即返回
                              ↓
                      Consumer 消费消息
                              ↓
                    执行分析/向量化任务
                              ↓
                      更新数据库状态
                              ↓
                   前端轮询获取最新状态
```

状态流转：`PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`

### 目录结构

```
app/src/main/java/interview/guide/
├── App.java                    # 启动类
├── common/                      # 通用模块
│   ├── annotation/              # 自定义注解 (如 @RateLimit)
│   ├── aspect/                  # AOP 切面
│   ├── async/                   # Stream 生产者/消费者基类
│   ├── config/                  # 配置类
│   ├── constant/                # 常量
│   ├── exception/               # 异常处理
│   ├── model/                   # 通用模型 (AsyncTaskStatus)
│   └── result/                  # 统一响应封装
├── infrastructure/              # 基础设施层
│   ├── export/                  # PDF 导出
│   ├── file/                    # 文件处理/解析
│   ├── mapper/                  # MyBatis Mapper
│   └── redis/                   # Redis 服务
└── modules/                     # 业务模块
    ├── interview/               # 模拟面试
    ├── knowledgebase/           # 知识库+RAG
    └── resume/                  # 简历管理
```

### 关键配置

- `app/src/main/resources/application.yml` - 主配置文件
- `app/src/main/resources/prompts/` - AI 提示词模板
- `gradle/libs.versions.toml` - 统一版本管理

### 重要约束

1. **JPA ddl-auto**: 首次启动用 `create`，表创建成功后改回 `update`
2. **pgvector initialize-schema**: 开发环境建议设为 `true`
3. **Lombok 和 MapStruct 配合**: MapStruct 处理器必须在 Lombok 之后配置，使用 `lombok-mapstruct-binding`

## 环境变量

必需的环境变量：
- `AI_BAILIAN_API_KEY` - 阿里云 DashScope API Key
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`
- `APP_STORAGE_*` - S3 存储配置 (endpoint, access-key, secret-key, bucket)