# infrastructure/export 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/infrastructure/export/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `PdfExportService.java` | PDF 导出服务（简历分析报告、面试报告） | `@Service`，iText 8 |

## 文件协作关系
- `PdfExportService` → `ObjectMapper`：解析 JSON 字段（优势、改进建议）
- 业务模块 Service → `PdfExportService.exportResumeAnalysis()`：导出简历报告
- 业务模块 Service → `PdfExportService.exportInterviewReport()`：导出面试报告

## iText 专项标注
- **中文字体**：内嵌 `ZhuqueFangsong-Regular.ttf`（朱雀仿宋），`PdfEncodings.IDENTITY_H`
- **文本清理**：`sanitizeText()` 移除 emoji 等特殊字符
- **颜色方案**：标题蓝色 `#2980B9`，章节深灰 `#34495E`，分数按等级绿/黄/红
- **导出类型**：
  - `exportResumeAnalysis()`：基本信息 + 综合评分 + 各维度评分 + 摘要 + 优势 + 建议
  - `exportInterviewReport()`：面试信息 + 评分 + 评价 + 问答详情（含参考答案）
