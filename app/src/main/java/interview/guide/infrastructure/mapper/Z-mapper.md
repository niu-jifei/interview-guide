# infrastructure/mapper 包分析

## 目录路径
`/Users/niujifei/Documents/njf-workspace/pub_learn_project/github_project/interview-guide/app/src/main/java/interview/guide/infrastructure/mapper/`

## 文件清单
| 文件名 | 职责 | 关键注解/特征 |
|--------|------|---------------|
| `ResumeMapper.java` | ResumeEntity ↔ DTO 映射 | `@Mapper(componentModel = "spring")` |
| `InterviewMapper.java` | Interview Entity ↔ DTO 映射 | `@Mapper(componentModel = "spring")` |
| `KnowledgeBaseMapper.java` | KB Entity ↔ DTO 映射 | `@Mapper(componentModel = "spring")` |
| `RagChatMapper.java` | RagChat Entity ↔ DTO 映射 | `@Mapper(componentModel = "spring")` |

## 文件协作关系
- 各模块 Service → 对应 Mapper：Entity 转 DTO 返回前端
- MapStruct 编译时生成实现类，运行时零反射开销
- 所有 Mapper 使用 `componentModel = "spring"`，支持 `@Autowired` 注入

## MapStruct 专项标注
- **映射方向**：Entity → DTO（禁止直接返回 Entity 给前端）
- **编译时生成**：`mapstruct-processor` 注解处理器生成实现
- **Lombok 兼容**：`lombok-mapstruct-binding:0.2.0` 确保 Lombok 先于 MapStruct 处理
