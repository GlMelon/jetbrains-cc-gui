# JCEF 多文件 WebView 与自定义 HTTPS Origin 迁移记录

- **日期**：2026-08-06
- **范围**：React Bits 引入后前端 WebView 启动黑屏 / 白屏问题
- **状态**：已完成实现并通过定向验证
- **相关目录**：`webview/`、`src/main/java/com/github/claudecodegui/ui/`

## 1. 背景

插件前端使用 React + TypeScript，并通过 JCEF 嵌入 IntelliJ。此前前端使用 `vite-plugin-singlefile` 构建为单个 HTML 文件，再由 Java 侧调用 `JBCefBrowser.loadHTML()` 加载。

引入 React Bits 后，前端构建产物的模块特征发生变化，主 bundle 或其依赖中包含了以下内容：

- ES Module 入口：`<script type="module">`
- `import.meta`
- 动态 `import()`
- 多个异步 chunk 和资源文件

单文件内嵌方案与 JCEF 的 `loadHTML()` 加载方式不再适合该产物形态，导致插件启动时前端无法执行。

## 2. 现象

启动插件后可能出现黑屏或白屏，Java 日志中可以看到类似错误：

```text
Cannot use 'import.meta' outside a module
```

由于前端初始化脚本没有成功执行，前端不会发送 `frontend_ready`。后端的 WebView watchdog 将页面误判为未就绪并持续执行 reload / recreate，进一步放大了黑屏、白屏现象。

## 3. 根因

问题不是 React Bits 组件本身不能在插件中使用，而是原有的单文件构建和 JCEF 加载方式无法稳定承载新的模块化产物。

临时把 HTML 中的 `type="module"` 改成 classic script 也不能解决问题，因为 bundle 内仍然保留 `import.meta` 和动态模块加载语义。此时 Chromium 会在模块上下文之外解析这些语法并直接中止执行。

因此，根本修复方向是：

1. 恢复标准 Vite 多文件构建；
2. 让 JCEF 以正常页面 URL 加载 HTML；
3. 为 HTML、JavaScript、CSS、动态 chunk 和其他静态资源提供稳定的同源资源访问机制；
4. 保留 ES Module 的原始加载语义。

## 4. 最终方案

采用 **标准 Vite 多文件构建 + JCEF 自定义 HTTPS Origin 资源拦截**：

```text
https://cc-gui-webview.local/index.html?pageGeneration=N
```

其中：

- `cc-gui-webview.local` 是插件内部使用的固定虚拟域名；
- URL 使用 HTTPS，确保页面具备稳定的 Origin；
- 页面和所有静态资源均由 JCEF 请求拦截器从插件 classpath 提供；
- 不启动本地 HTTP Server；
- 不把构建产物写入临时目录；
- 不修改或降级 ES Module 代码。

页面加载流程如下：

```text
Vite dist/index.html
        │
        ▼
Gradle processResources
        │
        ▼
build/resources/main/webview/**
        │
        ▼
WebviewResourceRequestHandler
        │
        ▼
https://cc-gui-webview.local/index.html
        │
        ├── ./assets/index-*.js
        ├── ./assets/style-*.css
        ├── 动态 import() chunk
        ├── JSON / SVG / 图片
        └── 字体等静态资源
```

## 5. 代码改动

### 5.1 前端构建

修改 `webview/vite.config.ts`：

- 删除 `vite-plugin-singlefile`；
- 删除单文件产物兼容处理；
- 增加 `base: './'`，使入口文件、chunk 和静态资源使用相对路径；
- 保留标准 Vite 多文件输出。

修改 `webview/package.json`：

- 删除 `postbuild` 中的 `scripts/copy-dist.mjs`；
- 删除 `vite-plugin-singlefile` 依赖。

同步更新 `webview/package-lock.json`。

删除：

```text
webview/scripts/copy-dist.mjs
```

### 5.2 Gradle 资源打包

修改 `build.gradle`：

- `processResources` 依赖 `buildWebview`；
- 将 `webview/dist` 复制到插件资源目录的 `webview/` 下；
- 确保 `index.html`、所有 JS / CSS 文件以及异步 chunk 都会进入最终插件资源。

构建后资源结构类似：

```text
build/resources/main/webview/
├── index.html
└── assets/
    ├── index-*.js
    ├── style-*.css
    ├── *.js
    └── 其他静态资源
```

### 5.3 JCEF 资源加载

新增：

```text
src/main/java/com/github/claudecodegui/ui/WebviewResourceRequestHandler.java
```

该处理器负责：

- 只拦截 `https://cc-gui-webview.local` Origin；
- 从 classpath `/webview/**` 读取资源；
- 返回正确的 MIME 类型；
- 支持 HTML、JavaScript、CSS、JSON、SVG、图片、字体和普通二进制资源；
- 对 `index.html` 保留 `pageGeneration`、主题及 tab 状态注入能力；
- 拒绝非 HTTPS、错误 Host、非默认端口和带用户信息的 URL；
- 拒绝 `..`、`.`、反斜杠、空字节和编码后的路径穿越请求；
- 为入口 HTML 和静态资源设置缓存策略。

修改：

```text
src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java
```

主要变化：

- 注册 `WebviewResourceRequestHandler`；
- 初次加载和 watchdog reload 均使用 `loadURL()`；
- 在 `loadURL()` 之前注册 JavaScript bridge；
- 增加主 frame 加载错误日志，便于后续定位资源加载问题。

修改：

```text
src/main/java/com/github/claudecodegui/util/HtmlLoader.java
```

资源入口从旧的单文件 HTML 改为：

```text
/webview/index.html
```

### 5.4 测试

新增：

```text
src/test/java/com/github/claudecodegui/ui/WebviewResourceRequestHandlerTest.java
```

覆盖内容包括：

- Origin URL 构造；
- Origin 校验；
- 安全路径解析；
- 路径穿越拦截；
- `pageGeneration` 参数解析；
- 常见资源 MIME 类型识别。

## 6. 验证记录

### 6.1 前端构建

```powershell
cd webview
npm run build
```

结果：通过。

构建产物为标准多文件结构，入口文件保留：

```html
<script type="module" crossorigin src="./assets/index-*.js"></script>
```

主 JavaScript 文件中保留合法的 `import.meta` 和动态 `import()`。

### 6.2 Gradle 资源处理

```powershell
.\gradlew.bat processResources --no-daemon
```

结果：通过。

已确认资源被复制到：

```text
build/resources/main/webview/index.html
build/resources/main/webview/assets/*
```

### 6.3 Java 编译

通过 IntelliJ 项目构建验证：

```text
build_project(projectPath=..., rebuild=false)
```

结果：

```text
isSuccess: true
problems: []
```

### 6.4 定向 Java 测试

```powershell
.\gradlew.bat test --no-daemon `
  --tests "com.github.claudecodegui.ui.WebviewResourceRequestHandlerTest" `
  --tests "com.github.claudecodegui.ui.WebviewInitializerTest" `
  --tests "com.github.claudecodegui.util.HtmlLoaderTest"
```

结果：`BUILD SUCCESSFUL`。

### 6.5 真实 `runIde` 验证

实际启动 sandbox IDE 后，日志：

```text
build/idea-sandbox/IC-2024.3.1/log/idea.log
```

已确认出现：

```text
Received frontend_ready signal, frontend is now ready to receive data
```

同时未发现以下错误：

```text
WebviewWatchdog
Webview resource load failed
Cannot use 'import.meta' outside a module
onLoadError
ERR_*
```

这证明页面已经完成真实加载、前端初始化和 Java ↔ WebView ready 握手。

## 7. 后续维护要求

后续继续引入 React Bits、动态组件或其他会产生异步 chunk 的前端依赖时，应保持以下约束：

1. 不要恢复 `vite-plugin-singlefile`；
2. 不要把 `<script type="module">` 改成 classic script；
3. 不要通过删除 `import.meta`、动态 `import()` 等语法规避问题；
4. 新增静态资源类型时，应在 `WebviewResourceRequestHandler` 中补充 MIME 类型；
5. 修改前端构建目录或入口路径时，要同步检查 `build.gradle`、`HtmlLoader` 和资源处理器；
6. 页面初始化问题优先检查 `idea.log` 中的 `frontend_ready`、`onLoadError` 和资源请求错误；
7. 修改 WebView 加载逻辑后，应至少执行前端构建、资源处理、相关 Java 测试和一次真实 `runIde` 验证。

## 8. 结论

本次问题通过迁移到 JCEF 自定义 HTTPS Origin 的标准多文件 WebView 加载模式解决。该方案保留了现代 Vite / React 产物的模块能力，避免了单文件 HTML 的大小和模块兼容性限制，也为后续使用 React Bits、动态导入和代码分割提供了稳定基础。
