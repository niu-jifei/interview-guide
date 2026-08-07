# infrastructure/file 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/infrastructure/file/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `FileStorageService.java` | S3 兼容文件存储服务（MinIO/RustFS） | `@Service`，AWS S3 SDK |
| `DocumentParseService.java` | 通用文档解析（Apache Tika） | `@Service`，AutoDetectParser |
| `FileValidationService.java` | 文件类型/大小校验 | 工具类 |
| `FileHashService.java` | 文件哈希计算（去重用） | 工具类 |
| `TextCleaningService.java` | 文本清洗（去除空白、特殊字符） | 工具类 |
| `ContentTypeDetectionService.java` | 内容类型检测 | 工具类 |
| `NoOpEmbeddedDocumentExtractor.java` | Tika 嵌入文档忽略器 | 实现 `EmbeddedDocumentExtractor` |

## 文件协作关系
- `DocumentParseService` → `TextCleaningService`：解析后清洗文本
- `DocumentParseService` → `NoOpEmbeddedDocumentExtractor`：禁用嵌入资源解析
- `DocumentParseService` → `FileStorageService`：下载文件后解析
- `FileValidationService` → 简历/知识库上传前校验文件类型和大小
- `FileHashService` → 简历去重（计算文件 MD5/SHA256）

## 专项标注
- **Tika 配置**：`BodyContentHandler` 限制 5MB，`PDFParserConfig` 关闭图片提取，`SortByPosition` 改善多栏布局
- **S3 Key 生成**：`{prefix}/{yyyy/MM/dd}/{uuid}_{safeName}`，汉字转拼音（pinyin4j）
- **文件名安全**：汉字→大驼峰拼音，特殊字符→下划线
