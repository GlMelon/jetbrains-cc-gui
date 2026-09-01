<div align="center">

# AI Code GUI

> 原名：Claude Code GUI

<img width="120" alt="Image" src="./docs/images/idea-claude-code-gui-logo.png" />

**简体中文** · [English](./README.md)

<a href="https://trendshift.io/repositories/24968" target="_blank"><img src="https://trendshift.io/api/badge/repositories/24968" alt="zhukunpenglinyutong%2Fjetbrains-cc-gui | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

![][github-contributors-shield] ![][github-forks-shield] ![][github-stars-shield] ![][github-issues-shield] ![][github-mit]

</div>

> 原名：Claude Code GUI，现更名为 AI Code GUI 以支持多种AI编码工具。对于安全方面，后续每个小版本发版前都进行
> /security-review 审查，每隔10个小版本进行一次整体的 claude-code-security 审查

一个功能强大的 IntelliJ IDEA 插件，为开发者提供 **Claude Code**、**OpenAI Codex** 以及更多 AI 编程 CLI的可视化操作界面，让 AI 辅助编程变得更加高效和直观。

<img width="850" alt="Image" src="/docs/img/banner.png" />

---

## 插件下载

[AI Code GUI 下载](https://plugins.jetbrains.com/plugin/29342-cc-gui-claude-or-codex-)

---

## 核心特性

### 多 AI 引擎支持
- **Claude Code** - Anthropic 官方 AI 编程助手，支持 Opus 4.5 等多模型
- **OpenAI Codex** - OpenAI 强大的代码生成引擎
- **Grok CLI**（Beta）- xAI 的命令行 AI 编程助手
- **Kimi CLI**（Beta）- 月之暗面（Moonshot AI）的命令行 AI 编程助手
- **OpenCode**（Beta）- 开源终端 AI 编程 Agent
- **PI CLI**（Beta）- PI 命令行 AI 编程助手
- **OMP CLI**（Beta）- OMP 命令行 AI 编程助手
- **DeepSeek Harness**（Beta）- DeepSeek 的命令行编程 Harness

### 智能对话功能

- 上下文感知的 AI 编程助手
- 支持 @文件引用，精准指定代码上下文
- 图片发送支持，可视化描述需求
- 对话回退功能，灵活调整对话历史
- 强化提示词，优化 AI 理解能力

### Agent 智能体

- 内置 Agent 系统，自动化执行复杂任务
- Skills 斜杠命令系统（/init, /review 等）
- MCP 服务器支持，扩展 AI 能力边界

### 开发者体验

- 完善的权限管理和安全控制
- 代码 DIFF 对比功能
- 文件跳转和代码导航
- 深色/浅色主题切换
- 字体缩放和 IDE 字体同步
- 国际化支持（中/英文自动切换）

### 会话管理

- 历史会话记录和搜索
- 会话收藏功能
- 消息导出支持
- 供应商管理（兼容 cc-switch）
- 使用统计分析

---

## 架构设计

本项目采用**三层运行时架构**：

### 分层说明

- **后端** — IntelliJ 插件主体 (Java, `src/main/java/com/github/claudecodegui/`)。承载全部业务逻辑、状态权威与持久化。所有数据计算、能力判定、数据归一化与决策均在此完成。
- **前端** — React + TypeScript webview (`webview/`)。通过 JCEF 嵌入 IDE，**只负责**渲染回显与输入采集，不包含任何业务逻辑。
- **ai-bridge** — 独立 Node.js 进程 (`ai-bridge/`)。负责 CLI 进程管理与消息流处理，通过 stdin/stdout 的 NDJSON 与后端通信。

### 通信机制

| 方向 | 方式 | 契约 |
|---|---|---|
| 前端 → 后端（上行） | `window.sendToJava({type, content})` | `UpstreamAction` 枚举，经 `FrontendActionHandler<T>` 派发 |
| 后端 → 前端（下行） | `window.__bridge.dispatch(type, payload)` | `DownstreamEvent` 枚举常量 |
| Java ↔ ai-bridge | stdin/stdout NDJSON 行协议 | `BaseSDKBridge.executeStreamingCommand` → `channel-manager.js` |

### 设计原则

- **前后端职责分离**：前端只做渲染，后端统一处理所有业务逻辑（最高优先级）。
- **单一真相源 (SSOT)**：协议消息名、payload 结构、枚举值由 Java 枚举通过 `prebuild` 钩子自动生成前端 TypeScript 类型，两端不手写字符串字面量。
- **开闭原则**：通过策略注册表 + Adapter 接口（`FrontendActionHandler<T>`、`ProviderAdapter`、`SessionRuntime`）扩展能力，不改核心分派逻辑。
- **Provider 对称性**：3 个 AI provider（Claude、Codex、OpenCode）× 2 种调用模式（SDK daemon、CLI 子进程）= 6 条调用路径共享等价的横切处理逻辑。

详细架构规范请参见 [AGENTS.md](AGENTS.md)。

---

## 项目状态

项目处于活跃开发阶段，代码持续更新中。版本历史和迭代进度请阅读 [CHANGELOG.md](CHANGELOG.md)

---

### 贡献代码

贡献代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)

---

## 本地开发调试

### 1. 安装前端依赖

```bash
cd webview
npm install
```

安装过程会自动执行 `prebuild` 钩子，从 Java 枚举生成协议类型到 `webview/src/generated/protocol.ts`。

### 2. 安装 ai-bridge 依赖

```bash
cd ai-bridge
npm install
```

### 3. 编译后端

```bash
./gradlew compileJava
```

### 4. 调试插件

```bash
./gradlew clean runIde
```

### 5. 构建插件

```sh
./gradlew clean buildPlugin

# 生成的插件包会在 build/distributions/ 目录下（包体大约40MB）
```

---

## License

MIT

---

## 贡献者列表

感谢所有帮助 IDEA-Claude-Code-GUI 变得更好的贡献者！

<table>
  <tr>
    <td align="center">
      <a href="https://github.com/zhukunpenglinyutong">
        <img src="https://avatars.githubusercontent.com/u/31264015?size=100" width="100" height="100" alt="zhukunpenglinyutong" style="border-radius: 50%; border: 3px solid #ff6b35; box-shadow: 0 0 15px rgba(255, 107, 53, 0.6);" />
      </a>
      <div>⭐️⭐️⭐️</div>
    </td>
    <td align="center">
      <a href="https://github.com/M1sury">
        <img src="https://avatars.githubusercontent.com/u/64764195?size=100" width="100" height="100" alt="M1sury" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/gadfly3173">
        <img src="https://avatars.githubusercontent.com/u/28685179?size=100" width="100" height="100" alt="gadfly3173" style="border-radius: 50%; border: 3px solid #ff6b35; box-shadow: 0 0 15px rgba(255, 107, 53, 0.6);" />
      </a>
      <div>🔥🔥🔥</div>
    </td>
    <td align="center">
      <a href="https://github.com/song782360037">
        <img src="https://avatars.githubusercontent.com/u/66980578?size=100" width="100" height="100" alt="song782360037" style="border-radius: 50%;" />
      </a>
      <div>🔥</div>
    </td>
    <td align="center">
      <a href="https://github.com/hpstream">
        <img src="https://avatars.githubusercontent.com/u/18394192?size=100" width="100" height="100" alt="hpstream" style="border-radius: 50%;" />
      </a>
      <div>🔥🔥</div>
    </td>
    <td align="center">
      <a href="https://github.com/imblowsnow">
        <img src="https://avatars.githubusercontent.com/u/74449531?size=100" width="100" height="100" alt="imblowsnow" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/Rinimabi">
        <img src="https://avatars.githubusercontent.com/u/18625271?size=100" width="100" height="100" alt="Rinimabi" style="border-radius: 50%;" />
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/GotoFox">
        <img src="https://avatars.githubusercontent.com/u/68596145?size=100" width="100" height="100" alt="GotoFox" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/changshunxu520">
        <img src="https://avatars.githubusercontent.com/u/16171624?size=100" width="100" height="100" alt="changshunxu520" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/lie5860">
        <img src="https://avatars.githubusercontent.com/u/30894657?size=100" width="100" height="100" alt="lie5860" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/buddhist-coder">
        <img src="https://avatars.githubusercontent.com/u/61658071?size=100" width="100" height="100" alt="buddhist-coder" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/LaCreArthur">
        <img src="https://avatars.githubusercontent.com/u/14138307?size=100" width="100" height="100" alt="LaCreArthur" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/dungnguyent8">
        <img src="https://avatars.githubusercontent.com/u/39462756?size=100" width="100" height="100" alt="dungnguyent8" style="border-radius: 50%;" />
      </a>
      <div>🔥</div>
    </td>
    <td align="center">
      <a href="https://github.com/magic5295">
        <img src="https://avatars.githubusercontent.com/u/157901486?size=100" width="100" height="100" alt="magic5295" style="border-radius: 50%;" />
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/JackWPP">
        <img src="https://avatars.githubusercontent.com/u/120316122?size=100" width="100" height="100" alt="JackWPP" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/luhua-123">
        <img src="https://avatars.githubusercontent.com/u/83643600?size=100" width="100" height="100" alt="luhua-123" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/geofqiu-hub">
        <img src="https://avatars.githubusercontent.com/u/248376932?size=100" width="100" height="100" alt="geofqiu-hub" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/1lck">
        <img src="https://avatars.githubusercontent.com/u/159525154?size=100" width="100" height="100" alt="1lck" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/fz-lyle">
        <img src="https://avatars.githubusercontent.com/u/35370530?size=100" width="100" height="100" alt="fz-lyle" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/dsudomoin">
        <img src="https://avatars.githubusercontent.com/u/155488585?size=100" width="100" height="100" alt="dsudomoin" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/serega0005">
        <img src="https://avatars.githubusercontent.com/u/39858725?size=100" width="100" height="100" alt="serega0005" style="border-radius: 50%;" />
      </a>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="https://github.com/jhaan83">
        <img src="https://avatars.githubusercontent.com/u/45828854?size=100" width="100" height="100" alt="jhaan83" style="border-radius: 50%;" />
      </a>
    </td>
    <td align="center">
      <a href="https://github.com/Olexandr1904">
        <img src="https://avatars.githubusercontent.com/u/12022163?size=100" width="100" height="100" alt="Olexandr1904" style="border-radius: 50%;" />
      </a>
    </td>
  </tr>
</table>

---

## 赞助支持

如果这个项目对你有帮助，想请作者吃顿肯德基（KFC）或者喝杯咖啡，都是可以的~

[查看赞助者列表 →](./SPONSORS.md)

---

## AtomGit

https://atomgit.com/zhukunpenglinyutong/idea-claude-code-gui

---

## 友链

感谢 [LINUX DO](https://linux.do/) 用户的支持与反馈

感谢[AtomGit](https://atomgit.com/zhukunpenglinyutong/idea-claude-code-gui)平台G-Star认证

---

## 致谢

最近有很多博主自发推荐本项目，心中十分感激，再次感谢《沉默的王二》《macrozheng》《JavaGuide》《Java知音》《鲲鹏talk
公众号》《程序员青戈》等博主推荐本项目，我会继续努力迭代，让大家用起来更舒适。

---

## Star History

[![Star History](https://api.star-history.com/svg?repos=zhukunpenglinyutong/idea-claude-code-gui&type=date&legend=top-left)](https://www.star-history.com/#zhukunpenglinyutong/idea-claude-code-gui&type=date&legend=top-left)

<!-- LINK GROUP -->

[github-contributors-shield]: https://img.shields.io/github/contributors/zhukunpenglinyutong/idea-claude-code-gui?color=c4f042&labelColor=black&style=flat-square
[github-forks-shield]: https://img.shields.io/github/forks/zhukunpenglinyutong/idea-claude-code-gui?color=8ae8ff&labelColor=black&style=flat-square
[github-issues-link]: https://github.com/zhukunpenglinyutong/idea-claude-code-gui/issues
[github-issues-shield]: https://img.shields.io/github/issues/zhukunpenglinyutong/idea-claude-code-gui?color=ff80eb&labelColor=black&style=flat-square
[github-license-link]: https://github.com/zhukunpenglinyutong/idea-claude-code-gui/blob/main/LICENSE
[github-stars-shield]: https://img.shields.io/github/stars/zhukunpenglinyutong/idea-claude-code-gui?color=ffcb47&labelColor=black&style=flat-square
[github-mit]: https://img.shields.io/badge/github-MIT-blue?logo=github
