package com.github.claudecodegui.service;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Skills 市场安装锁文件(skills-lock.json)读写 + SHA-256 哈希校验。
 *
 * <p>锁文件记录 market 安装的 skill 元数据(源仓库/源类型/计算哈希),用于:
 * <ul>
 *   <li>重装/更新时校验 tarball 完整性(防篡改:哈希不匹配则拒绝安装)</li>
 *   <li>卸载时识别 market 来源(可选清理锁记录)</li>
 * </ul>
 *
 * <p>锁文件位置:{@code ~/.claude/skills-lock.json}(用户级跨项目共享,
 * 与 global skill 安装目录 {@code ~/.claude/skills/} 同根,便于关联)。
 * 格式:{@code {version:1, skills:{name:{source,sourceType,computedHash,scope}}}}。
 *
 * <p>纯函数 {@link #readLock}/{@link #writeLock}/{@link #recordSkill}/
 * {@link #removeSkill}/{@link #computeSha256}/{@link #isHashMatched} 可单测
 * (注入路径/JsonObject);{@link #recordInstall}/{@link #removeRecord}/
 * {@link #verifyHash} 为生产便捷入口(读真实 home)。
 */
public class SkillLockService {

    private static final Logger LOG = Logger.getInstance(SkillLockService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int LOCK_VERSION = 1;

    /** 锁文件默认路径(纯函数,注入 homeDir 以便测试)。 */
    static Path defaultLockPath(String homeDir) {
        return Paths.get(homeDir, ".claude", "skills-lock.json");
    }

    /** 生产:从真实 home 解析锁文件路径。 */
    static Path resolveLockPath() {
        return defaultLockPath(PlatformUtils.getHomeDirectory());
    }

    /** 构造空锁骨架。 */
    static JsonObject emptyLock() {
        JsonObject o = new JsonObject();
        o.addProperty("version", LOCK_VERSION);
        o.add("skills", new JsonObject());
        return o;
    }

    /**
     * 读取锁文件 → JsonObject;不存在/解析失败返回空锁(容错,不抛)。
     * 缺 skills/version 字段时补齐。
     */
    static JsonObject readLock(Path lockPath) {
        if (lockPath == null || !Files.isRegularFile(lockPath)) {
            return emptyLock();
        }
        try {
            String content = Files.readString(lockPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("skills") || !root.get("skills").isJsonObject()) {
                root.add("skills", new JsonObject());
            }
            if (!root.has("version")) {
                root.addProperty("version", LOCK_VERSION);
            }
            return root;
        } catch (Exception e) {
            LOG.warn("[SkillLock] Failed to read lock file " + lockPath + ": " + e.getMessage());
            return emptyLock();
        }
    }

    /** 写锁文件(创建父目录)。 */
    static void writeLock(Path lockPath, JsonObject lock) throws IOException {
        if (lockPath == null) {
            return;
        }
        if (lockPath.getParent() != null) {
            Files.createDirectories(lockPath.getParent());
        }
        Files.writeString(lockPath, GSON.toJson(lock));
    }

    /**
     * 在锁对象中记录/更新一个 skill(纯函数,不改文件)。
     *
     * @param name         skill 名(规范小写连字符)
     * @param source       源标识(如 "anthropics")
     * @param sourceType   源类型(如 "github")
     * @param computedHash tarball SHA-256
     * @param scope        安装 scope(global/local/user/repo)
     */
    static JsonObject recordSkill(JsonObject lock, String name, String source, String sourceType,
                                  String computedHash, String scope) {
        JsonObject skills = lock.has("skills") && lock.get("skills").isJsonObject()
                ? lock.getAsJsonObject("skills") : new JsonObject();
        JsonObject entry = new JsonObject();
        entry.addProperty("source", source != null ? source : "");
        entry.addProperty("sourceType", sourceType != null ? sourceType : "github");
        if (computedHash != null) {
            entry.addProperty("computedHash", computedHash);
        }
        if (scope != null) {
            entry.addProperty("scope", scope);
        }
        skills.add(name, entry);
        lock.add("skills", skills);
        return lock;
    }

    /** 从锁对象移除一个 skill 记录(纯函数,不改文件)。 */
    static JsonObject removeSkill(JsonObject lock, String name) {
        if (lock.has("skills") && lock.get("skills").isJsonObject()) {
            lock.getAsJsonObject("skills").remove(name);
        }
        return lock;
    }

    /** 计算文件 SHA-256 十六进制摘要。 */
    static String computeSha256(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("File not found for hashing: " + file);
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                md.update(buffer, 0, len);
            }
        }
        return toHex(md.digest());
    }

    /**
     * 比对锁文件中已记录的哈希是否与给定哈希一致。
     * <p>首次安装(未记录)→ true(放行,安装后记录);已记录且一致→ true;不一致→ false(篡改)。
     */
    static boolean isHashMatched(JsonObject lock, String name, String computedHash) {
        if (!lock.has("skills") || !lock.get("skills").isJsonObject()) {
            return true;
        }
        JsonObject skills = lock.getAsJsonObject("skills");
        if (!skills.has(name) || !skills.get(name).isJsonObject()) {
            return true; // 首次安装,无历史哈希
        }
        JsonObject entry = skills.getAsJsonObject(name);
        if (!entry.has("computedHash") || !entry.get("computedHash").isJsonPrimitive()) {
            return true;
        }
        String recorded = entry.get("computedHash").getAsString();
        return recorded.equals(computedHash);
    }

    // ── 生产便捷入口(读真实 home) ──

    /** 安装成功后记录到锁文件(失败仅 warn,不阻塞安装流程)。 */
    public static void recordInstall(String skillName, String source, String sourceType,
                                     String computedHash, String scope) {
        try {
            Path p = resolveLockPath();
            JsonObject lock = readLock(p);
            recordSkill(lock, skillName, source, sourceType, computedHash, scope);
            writeLock(p, lock);
        } catch (Exception e) {
            LOG.warn("[SkillLock] Failed to record install for " + skillName + ": " + e.getMessage());
        }
    }

    /** 卸载后从锁文件移除记录(失败仅 warn)。 */
    public static void removeRecord(String skillName) {
        try {
            Path p = resolveLockPath();
            JsonObject lock = readLock(p);
            removeSkill(lock, skillName);
            writeLock(p, lock);
        } catch (Exception e) {
            LOG.warn("[SkillLock] Failed to remove record for " + skillName + ": " + e.getMessage());
        }
    }

    /**
     * 校验给定哈希是否与锁文件已记录的一致。
     * <p>首次安装(未记录)→ true;校验异常→ true(不阻塞);不一致→ false(篡改)。
     */
    public static boolean verifyHash(String skillName, String computedHash) {
        try {
            JsonObject lock = readLock(resolveLockPath());
            return isHashMatched(lock, skillName, computedHash);
        } catch (Exception e) {
            LOG.warn("[SkillLock] Hash verify failed for " + skillName + ", allowing install: " + e.getMessage());
            return true;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
