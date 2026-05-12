```json5
{
  // ========== 基本信息字段 ==========
  
  // 项目名称，用于标识这个 npm 包
  "name": "ai-interview-frontend",
  
  // 标记为私有项目，不会被发布到 npm 公共仓库
  "private": true,
  
  // 项目版本号，遵循语义化版本规范 (主版本.次版本.修订号)
  "version": "0.0.1",
  
  // 指定使用 ES Modules 模块系统，支持 import/export 语法
  "type": "module",
  
  // ========== 脚本命令 ==========
  
  "scripts": {
    // 启动 Vite 开发服务器，支持热模块替换（HMR）
    "dev": "vite",
    
    // 先执行 TypeScript 类型检查和编译，然后构建生产版本
    "build": "tsc && vite build",
    
    // 预览生产构建后的应用，用于本地测试打包结果
    "preview": "vite preview",
    
    // 自定义命令：列出当前目录的详细文件信息
    "dir": "ls -l"
  },
  
  // ========== 生产依赖（运行时必需） ==========
  
  "dependencies": {
    // Tailwind CSS 的 PostCSS 插件，用于处理 CSS 样式
    "@tailwindcss/postcss": "^4.1.18",
    
    // HTTP 客户端库，用于发送网络请求（API 调用）
    "axios": "^1.7.7",
    
    // React 动画库，提供流畅的声明式动画效果
    "framer-motion": "^12.23.26",
    
    // 图标组件库，提供丰富的 SVG 图标
    "lucide-react": "^0.468.0",
    
    // React 核心库，用于构建用户界面
    "react": "^18.3.1",
    
    // React DOM 渲染器，将 React 组件渲染到浏览器 DOM
    "react-dom": "^18.3.1",
    
    // Markdown 渲染组件，将 Markdown 文本转换为 React 元素
    "react-markdown": "^9.0.1",
    
    // React 路由管理库，用于处理页面导航和路由
    "react-router-dom": "^7.11.0",
    
    // 代码语法高亮组件，用于显示带语法高亮的代码块
    "react-syntax-highlighter": "^16.1.0",
    
    // 高性能虚拟滚动列表组件，用于渲染大量数据
    "react-virtuoso": "^4.18.1",
    
    // React 图表库，用于数据可视化
    "recharts": "^3.6.0",
    
    // GitHub Flavored Markdown 支持，扩展 Markdown 语法
    "remark-gfm": "^4.0.0"
  },
  
  // ========== 开发依赖（仅开发时需要） ==========
  
  "devDependencies": {
    // Tailwind 排版插件，用于美化文章和文档样式
    "@tailwindcss/typography": "^0.5.15",
    
    // React 的 TypeScript 类型定义文件
    "@types/react": "^18.3.12",
    
    // React DOM 的 TypeScript 类型定义文件
    "@types/react-dom": "^18.3.1",
    
    // 语法高亮器的 TypeScript 类型定义文件
    "@types/react-syntax-highlighter": "^15.5.13",
    
    // Vite 的官方 React 插件，支持 JSX 和 Fast Refresh
    "@vitejs/plugin-react": "^4.3.3",
    
    // PostCSS 插件，自动为 CSS 添加浏览器前缀
    "autoprefixer": "^10.4.23",
    
    // CSS 转换工具，用于处理和转换 CSS
    "postcss": "^8.5.6",
    
    // Tailwind CSS 框架，用于快速构建现代 UI
    "tailwindcss": "^4.1.18",
    
    // TypeScript 编译器，用于类型检查和编译 TS 代码
    "typescript": "~5.6.2",
    
    // 现代化的前端构建工具，提供快速的开发体验
    "vite": "^5.4.10"
  },
  
  // ========== 包管理器配置 ==========
  
  // 指定使用 pnpm 作为包管理器，包含版本号和 SHA512 校验和
  "packageManager": "pnpm@10.26.2+sha512.0e308ff2005fc7410366f154f625f6631ab2b16b1d2e70238444dd6ae9d630a8482d92a451144debc492416896ed16f7b114a86ec68b8404b2443869e68ffda6"
}
```