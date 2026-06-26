# Skill SSOT 架构规划

## 一、背景

### 1.1 当前问题

1. **CLI 调用模式不加载 skill**：CLI 模式（ClaudeCliSessionRuntime/CodexCliSessionRuntime/OpenCodeCliSessionRuntime）不会触发 SDK 的 skill 加载逻辑，导致 skill 不可用。

2. **多工具兼容性不足**：
   - 当前只支持 Claude（`~/.claude/skills/`）和 Codex（`~/.agents/skills/`、`~/.codex/skills/`）
   - 缺少对 OpenCode 等其他工具的支持
   - 每个工具有独立的 skill 目录，无法统一管理

3. **SKILL.md 格式要求严格**：
   - 必须包含 `SKILL.md` 或 `skill.md` 文件
   - 必须包含有效的 YAML frontmatter
   - 缺失字段时没有兜底机制

### 1.2 参考实现

参考 **CC Switch** 项目的优秀实践：

- **SSOT 架构**：skill 统一存储在一个中心目录，然后按需同步到各应用目录
- **多目录扫描**：扫描所有应用目录（Claude、Codex、OpenCode 等）
- **宽松解析**：对 SKILL.md 格式非常宽容，缺失字段时使用目录名兜底

## 二、架构目标

### 2.1 核心目标

1. **统一管理**：所有工具（Claude、Codex、OpenCode 等）的 skill 统一管理
2. **消除重复**：合并 `SkillService` 和 `CodexSkillService` 的重复逻辑
3. **宽松解析**：支持非标准 SKILL.md 格式，缺失字段时使用目录名兜底
4. **多目录扫描**：扫描所有工具的 skill 目录，而不仅仅是 `~/.claude/skills/`
5. **向后兼容**：保持现有功能不变，平滑迁移

### 2.2 支持的工具

| 工具 | Skill 目录 | 启用/禁用机制 |
|------|-----------|--------------|
| Claude | `~/.claude/skills/` | 文件移动 |
| Codex | `~/.agents/skills/`、`~/.codex/skills/` | `config.toml` |
| OpenCode | `~/.config/opencode/skills/` | 待定 |
| 其他工具 | 可配置 | 可配置 |

## 三、核心组件设计

### 3.1 统一的 `UnifiedSkillService` 接口

```java
/**
 * 统一的 Skill 服务接口
 * 支持所有工具（Claude、Codex、OpenCode 等）的 skill 管理
 */
public interface UnifiedSkillService {
    
    /**
     * 获取所有 skill（包括启用和禁用的）
     */
    JsonObject getAllSkills(String cwd, String provider);
    
    /**
     * 导入 skill
     */
    JsonObject importSkills(List<String> sourcePaths, String scope, String cwd, String provider);
    
    /**
     * 删除 skill
     */
    JsonObject deleteSkill(String name, String scope, boolean enabled, String cwd, String provider);
    
    /**
     * 启用/禁用 skill
     */
    JsonObject toggleSkill(String name, String scope, boolean currentEnabled, String cwd, String provider);
    
    /**
     * 扫描 skill 目录
     */
    List<SkillScanDir> getSkillScanDirs(String cwd, String provider);
}
```

### 3.2 Provider-specific 实现

```java
/**
 * Claude Skill 服务实现
 */
public class ClaudeSkillServiceImpl implements UnifiedSkillService {
    // 复用现有的 SkillService 逻辑
    // 支持 ~/.claude/skills/ 和 {workspace}/.claude/skills/
}

/**
 * Codex Skill 服务实现
 */
public class CodexSkillServiceImpl implements UnifiedSkillService {
    // 复用现有的 CodexSkillService 逻辑
    // 支持 ~/.agents/skills/ 和 ~/.codex/skills/
}

/**
 * OpenCode Skill 服务实现
 */
public class OpenCodeSkillServiceImpl implements UnifiedSkillService {
    // 新增：支持 ~/.config/opencode/skills/
}
```

### 3.3 统一的 Skill 扫描器

```java
/**
 * 统一的 Skill 扫描器
 * 扫描所有工具的 skill 目录
 */
public class UnifiedSkillScanner {
    
    /**
     * 获取所有工具的 skill 扫描目录
     */
    public static List<SkillScanDir> getAllToolSkillScanDirs(String cwd, String provider) {
        List<SkillScanDir> dirs = new ArrayList<>();
        
        // 1. Claude 目录
        if ("claude".equals(provider) || "all".equals(provider)) {
            dirs.addAll(getClaudeSkillScanDirs(cwd));
        }
        
        // 2. Codex 目录
        if ("codex".equals(provider) || "all".equals(provider)) {
            dirs.addAll(CodexSkillService.getSkillScanDirs(cwd));
        }
        
        // 3. OpenCode 目录
        if ("opencode".equals(provider) || "all".equals(provider)) {
            dirs.addAll(getOpenCodeSkillScanDirs(cwd));
        }
        
        // 4. 其他工具目录
        // ...
        
        return dirs;
    }
    
    /**
     * 宽松的 SKILL.md 解析
     * 支持非标准格式，缺失字段时使用目录名兜底
     */
    public static SkillMetadata parseSkillMetadata(Path skillDir) {
        // 1. 支持 UTF-8 BOM
        // 2. YAML 解析失败时回退到空值
        // 3. 缺失字段时使用目录名作为兜底
        // ...
    }
}
```

### 3.4 宽松的 SKILL.md 解析器

```java
/**
 * 宽松的 SKILL.md 解析器
 * 支持非标准格式，缺失字段时使用目录名兜底
 */
public class LenientSkillFrontmatterParser {
    
    /**
     * 解析 SKILL.md 元数据
     * 支持：
     * - UTF-8 BOM
     * - YAML 解析失败时回退到空值
     * - 缺失字段时使用目录名作为兜底
     */
    public static SkillMetadata parse(Path skillDir) {
        // 1. 定位 SKILL.md 文件
        Path skillMd = locateSkillMd(skillDir);
        if (skillMd == null) {
            // 没有 SKILL.md 文件，使用目录名作为 name，description 为空
            return new SkillMetadata(
                skillDir.getFileName().toString(),
                "",
                null,
                null,
                null,
                true,  // user-invocable 默认为 true
                List.of()
            );
        }
        
        // 2. 提取 frontmatter
        String yamlText = extractFrontmatter(skillMd);
        if (yamlText == null) {
            // 没有有效的 frontmatter，使用目录名作为 name
            return new SkillMetadata(
                skillDir.getFileName().toString(),
                "",
                null,
                null,
                null,
                true,
                List.of()
            );
        }
        
        // 3. 解析 YAML
        Map<String, Object> yamlMap = parseYaml(yamlText);
        if (yamlMap == null) {
            // YAML 解析失败，使用目录名作为 name
            return new SkillMetadata(
                skillDir.getFileName().toString(),
                "",
                null,
                null,
                null,
                true,
                List.of()
            );
        }
        
        // 4. 提取字段（缺失时使用默认值）
        String name = extractName(yamlMap, skillDir);
        String description = extractDescription(yamlMap, skillMd);
        boolean userInvocable = extractUserInvocable(yamlMap);
        List<String> paths = extractPaths(yamlMap);
        
        return new SkillMetadata(name, description, null, null, null, userInvocable, paths);
    }
}
```

## 四、实施步骤

### 阶段 1：基础架构（1-2 天）

#### 1.1 创建统一的 `UnifiedSkillService` 接口

- 定义统一的 API
- 支持多 provider
- 文件：`src/main/java/com/github/claudecodegui/skill/UnifiedSkillService.java`

#### 1.2 重构 `SkillFrontmatterParser`

- 支持 UTF-8 BOM
- YAML 解析失败时回退到空值
- 缺失字段时使用目录名作为兜底
- 文件：`src/main/java/com/github/claudecodegui/skill/SkillFrontmatterParser.java`

#### 1.3 创建 `OpenCodeSkillService`

- 实现 OpenCode 的 skill 扫描
- 支持 `~/.config/opencode/skills/` 目录
- 文件：`src/main/java/com/github/claudecodegui/skill/OpenCodeSkillService.java`

### 阶段 2：核心服务（3-5 天）

#### 2.1 重构 `SkillService`

- 实现 `UnifiedSkillService` 接口
- 保持现有功能不变
- 文件：`src/main/java/com/github/claudecodegui/skill/SkillService.java`

#### 2.2 重构 `CodexSkillService`

- 实现 `UnifiedSkillService` 接口
- 保持现有功能不变
- 文件：`src/main/java/com/github/claudecodegui/skill/CodexSkillService.java`

#### 2.3 创建 `UnifiedSkillScanner`

- 统一扫描所有工具的 skill 目录
- 支持多目录扫描
- 文件：`src/main/java/com/github/claudecodegui/skill/UnifiedSkillScanner.java`

### 阶段 3：Handler 层重构（2-3 天）

#### 3.1 重构 `SkillActionHandlers`

- 使用统一的 `UnifiedSkillService` 接口
- 根据 provider 分发到具体的实现
- 文件：`src/main/java/com/github/claudecodegui/handler/skill/SkillActionHandlers.java`

#### 3.2 更新 Protocol 定义

- 支持多 provider 的 skill 管理
- 添加 OpenCode 相关的 action 和 event
- 文件：`src/main/java/com/github/claudecodegui/protocol/`

### 阶段 4：前端适配（2-3 天）

#### 4.1 更新 `SkillsSettingsSection`

- 支持显示所有工具的 skill
- 添加 provider 切换功能
- 文件：`webview/src/components/skills/SkillsSettingsSection.tsx`

#### 4.2 更新类型定义

- 扩展 `Skill` 和 `SkillsConfig` 类型
- 支持多 provider
- 文件：`webview/src/types/skill.ts`

### 阶段 5：测试和优化（1-2 天）

#### 5.1 单元测试

- 测试统一的 skill 扫描
- 测试多 provider 的 skill 管理
- 文件：`src/test/java/com/github/claudecodegui/skill/`

#### 5.2 集成测试

- 测试完整的 skill 导入、删除、启用/禁用流程
- 测试多工具的 skill 同步

#### 5.3 性能优化

- 优化 skill 扫描性能
- 缓存扫描结果

## 五、关键文件清单

| 文件路径 | 作用 | 阶段 |
|---------|------|------|
| `src/main/java/com/github/claudecodegui/skill/UnifiedSkillService.java` | 统一的 Skill 服务接口 | 1 |
| `src/main/java/com/github/claudecodegui/skill/ClaudeSkillServiceImpl.java` | Claude Skill 服务实现 | 2 |
| `src/main/java/com/github/claudecodegui/skill/CodexSkillServiceImpl.java` | Codex Skill 服务实现 | 2 |
| `src/main/java/com/github/claudecodegui/skill/OpenCodeSkillServiceImpl.java` | OpenCode Skill 服务实现 | 1 |
| `src/main/java/com/github/claudecodegui/skill/UnifiedSkillScanner.java` | 统一的 Skill 扫描器 | 2 |
| `src/main/java/com/github/claudecodegui/skill/SkillFrontmatterParser.java` | 宽松的 SKILL.md 解析器 | 1 |
| `src/main/java/com/github/claudecodegui/handler/skill/SkillActionHandlers.java` | 重构的 Handler 层 | 3 |
| `src/main/java/com/github/claudecodegui/protocol/` | Protocol 定义 | 3 |
| `webview/src/components/skills/SkillsSettingsSection.tsx` | 重构的前端 UI | 4 |
| `webview/src/types/skill.ts` | 类型定义 | 4 |

## 六、向后兼容性

### 6.1 API 兼容

- 保持现有的 `GET_ALL_SKILLS`、`IMPORT_SKILL` 等 action 不变
- 保持现有的 `SKILL_LIST`、`SKILL_IMPORT_RESULT` 等 event 不变
- 新增的 action 和 event 使用新的命名空间（如 `OPENCODE_GET_ALL_SKILLS`）

### 6.2 配置兼容

- 保持现有的配置文件格式
- 支持平滑迁移
- 新增的配置项使用默认值

### 6.3 存储兼容

- 保持现有的 skill 存储位置
- 支持从旧位置迁移
- 新增的存储位置使用默认路径

## 七、风险和缓解措施

### 7.1 风险：重构可能引入新的 bug

**缓解措施**：
- 充分的单元测试和集成测试
- 渐进式迁移，先实现统一接口，再逐步迁移现有服务
- 保持现有功能不变，新功能作为可选特性

### 7.2 风险：性能下降

**缓解措施**：
- 优化扫描逻辑，使用缓存
- 异步扫描，避免阻塞主线程
- 限制扫描深度和范围

### 7.3 风险：向后兼容性问题

**缓解措施**：
- 保持现有 API 不变
- 新增的 action 和 event 使用新的命名空间
- 提供迁移工具和文档

### 7.4 风险：多工具 skill 冲突

**缓解措施**：
- 使用唯一的 skill ID（包含 provider 和 scope）
- 支持 skill 的优先级和覆盖规则
- 提供冲突检测和解决机制

## 八、时间估算

| 阶段 | 任务 | 时间 |
|------|------|------|
| 阶段 1 | 基础架构 | 1-2 天 |
| 阶段 2 | 核心服务 | 3-5 天 |
| 阶段 3 | Handler 层重构 | 2-3 天 |
| 阶段 4 | 前端适配 | 2-3 天 |
| 阶段 5 | 测试和优化 | 1-2 天 |
| **总计** | | **9-15 天** |

## 九、下一步行动

1. **立即行动**：为 `ops-automation-mcp` 创建 SKILL.md 文件（已完成）
2. **短期行动**：重启 Claude 对话，让 SDK 重新加载 skill
3. **中期行动**：开始阶段 1 的实施
4. **长期行动**：完成所有阶段的实施

---

*文档版本：v1.0*
*创建日期：2026-06-26*
*作者：Claude Code GUI*
