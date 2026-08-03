package com.github.claudecodegui.service;

import com.github.claudecodegui.bridge.BridgeArchiveExtractor;
import com.github.claudecodegui.skill.SkillFrontmatterParser;
import com.github.claudecodegui.skill.UnifiedSkillServiceRegistry;
import com.github.claudecodegui.util.CliTempDir;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Skills 市场:从 GitHub 仓库(anthropics/skills、vercel-labs/agent-skills、obra/superpowers)
 * 下载 tarball,校验 SHA-256,解压后经 {@link UnifiedSkillServiceRegistry} 安装到对应 provider 目录。
 *
 * <p>数据流:listMarketSkills → GitHub Contents API 列 skills 目录;
 * installSkill → codeload tarball 下载 → SHA-256 校验(对比 skills-lock.json)→
 * {@link BridgeArchiveExtractor#extractTarGz} 解压 → 定位含 SKILL.md 子目录 →
 * {@link SkillFrontmatterParser} 解析 frontmatter + 名称二次校验 →
 * {@code UnifiedSkillServiceRegistry.forProvider(provider).importSkills(...)} →
 * {@link SkillLockService} 记录锁。
 *
 * <p>仿 {@link SmitheryMarketService}:纯函数(buildContentsUrl/buildTarballUrl/
 * parseContentsResponse/locateSkillDir)可单测(无 HTTP);listMarketSkills/installSkill
 * 为 HTTP 集成(端到端实测)。
 *
 * <p><b>认证</b>:GitHub 公开仓库 Contents API/tarball 免认证(60 req/h rate limit);
 * 403 视为 rate limit,前端提示稍后重试。token 支持留待后续(复用 Smithery Key 同款入口)。
 *
 * <p><b>安全</b>:解压用纯 Java commons-compress(BridgeArchiveExtractor.extractTarGz),
 * ZipSlip 防御 100% 可控(系统 GNU tar 会解压 ../ 到目标外,绕过防御);安装前
 * {@link SkillFrontmatterParser#isValidSkillName} 二次校验 skill 名(防恶意目录名)。
 */
public class SkillMarketService {

    private static final Logger LOG = Logger.getInstance(SkillMarketService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** tarball 下载可能较大,给 60s;Contents API 列表走同一超时也够。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String USER_AGENT = "jetbrains-melon-cc-gui-skill-market";
    private static final String CONTENTS_API = "https://api.github.com/repos";
    private static final String CODELOAD = "https://codeload.github.com";
    private static final String RAW_GITHUB = "https://raw.githubusercontent.com";
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";

    /** Skills 市场源(GitHub 仓库)。三源结构一致:skills/{name}/SKILL.md。 */
    public record SkillMarketSource(String id, String label, String owner, String repo,
                                    String branch, String skillsPath) {
    }

    /**
     * 已知源列表(后端 SSOT,owner/repo 不暴露前端)。
     * <ul>
     *   <li>{@code anthropics} — anthropics/skills(官方 Agent Skills 目录,pdf/docx 等)</li>
     *   <li>{@code vercel} — vercel-labs/agent-skills(Vercel 官方 agent skills 集合)</li>
     *   <li>{@code superpowers} — obra/superpowers(agentic skills 框架,含 using-superpowers 等)</li>
     * </ul>
     */
    public static final List<SkillMarketSource> SOURCES = List.of(
            new SkillMarketSource("anthropics", "Anthropic Skills", "anthropics", "skills", "main", "skills"),
            new SkillMarketSource("vercel", "Vercel Agent Skills", "vercel-labs", "agent-skills", "main", "skills"),
            new SkillMarketSource("superpowers", "Superpowers", "obra", "superpowers", "main", "skills")
    );

    /** 按 id 查源(纯函数)。 */
    static SkillMarketSource findSource(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        for (SkillMarketSource s : SOURCES) {
            if (s.id().equals(sourceId)) {
                return s;
            }
        }
        return null;
    }

    /** 构造 Contents API URL(纯函数):列 {path} 子目录,?ref={branch}。 */
    static String buildContentsUrl(SkillMarketSource src, String path) {
        String base = CONTENTS_API + "/" + src.owner() + "/" + src.repo() + "/contents";
        if (path != null && !path.isEmpty()) {
            base += "/" + path;
        }
        return base + "?ref=" + src.branch();
    }

    /** 构造 codeload tarball URL(纯函数):refs/heads/{branch}。 */
    static String buildTarballUrl(SkillMarketSource src) {
        return CODELOAD + "/" + src.owner() + "/" + src.repo() + "/tar.gz/refs/heads/" + src.branch();
    }

    /**
     * 构造 raw.githubusercontent.com URL(纯函数):{owner}/{repo}/{branch}/{skillPath}/{fileName}。
     * skillPath 规范化去首尾斜杠;空路径退化为根级文件。
     */
    static String buildRawUrl(SkillMarketSource src, String skillPath, String fileName) {
        String p = skillPath == null ? "" : skillPath.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        String pathSeg = p.isEmpty() ? "" : "/" + p;
        return RAW_GITHUB + "/" + src.owner() + "/" + src.repo()
                + "/" + src.branch() + pathSeg + "/" + fileName;
    }

    /** 规范化拼接 skillPath 与文件名(去首尾斜杠、反斜杠归一),用于 Contents API 单文件路径。 */
    static String joinPath(String skillPath, String fileName) {
        String p = skillPath == null ? "" : skillPath.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? fileName : p + "/" + fileName;
    }

    /**
     * 解析 Contents API 单文件响应 → SKILL.md 文本(纯函数,便于单测)。
     * <p>响应:{type:"file", content:"<base64>", encoding:"base64"}。GitHub 返回的 content 字段为
     * base64 编码且按 76 字符折行(含真实换行),解码得 SKILL.md 原文。缺 content 字段或解码失败 → PARSE_ERROR。
     */
    static String decodeContentFileBody(String body) throws MarketFetchException {
        if (body == null || body.isBlank()) {
            throw new MarketFetchException(MarketFetchException.PARSE_ERROR);
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (!obj.has("content") || !obj.get("content").isJsonPrimitive()) {
                throw new MarketFetchException(MarketFetchException.PARSE_ERROR);
            }
            String b64 = obj.get("content").getAsString().replace("\n", "");
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (MarketFetchException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("[SkillMarket] decode SKILL.md content failed: " + e.getMessage());
            throw new MarketFetchException(MarketFetchException.PARSE_ERROR, e);
        }
    }

    /** 源列表 → JsonArray(前端 Tab 用)。 */
    public static JsonArray listSources() {
        JsonArray arr = new JsonArray();
        for (SkillMarketSource s : SOURCES) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("label", s.label());
            o.addProperty("owner", s.owner());
            o.addProperty("repo", s.repo());
            arr.add(o);
        }
        return arr;
    }

    /**
     * 列出某源的 skills(GitHub Contents API)。
     * <p>skillsPath 404 时 fallback 列根目录(容错 vercel-labs/agent-skills 目录结构差异)。
     *
     * @return {sources:[...], source, sourceLabel, skills:[{name,path}]}
     * @throws MarketFetchException UNKNOWN_SOURCE/HTTP_404/HTTP_403/NETWORK_ERROR/PARSE_ERROR
     */
    public static JsonObject listMarketSkills(String sourceId) throws MarketFetchException {
        SkillMarketSource src = findSource(sourceId);
        if (src == null) {
            throw new MarketFetchException("UNKNOWN_SOURCE");
        }
        String body;
        try {
            body = httpGetJson(buildContentsUrl(src, src.skillsPath()));
        } catch (MarketFetchException e) {
            if ("HTTP_404".equals(e.getErrorCode()) && src.skillsPath() != null && !src.skillsPath().isEmpty()) {
                // skillsPath 不存在 → fallback 根目录
                body = httpGetJson(buildContentsUrl(src, ""));
            } else {
                throw e;
            }
        }
        return parseContentsResponse(body, src);
    }

    /**
     * 安装 skill:下载 tarball → SHA-256 校验 → 解压 → 定位 SKILL.md 目录 → frontmatter 校验 →
     * UnifiedSkillServiceRegistry 安装 → 写锁。
     *
     * @param sourceId  源 id(anthropics/vercel/superpowers)
     * @param skillPath skill 相对仓库根路径(Contents API 返回的 path,如 "skills/pdf")
     * @param scope     安装 scope(Claude/OpenCode=global/local,Codex=user/repo)
     * @param provider  provider(claude/codex/opencode)
     * @param cwd       工作目录(local/repo scope 必需)
     * @return {success, skillName, source, hash, importResult:{...}}
     * @throws MarketFetchException 下载/校验/源错误
     * @throws IOException          解压/IO 错误
     */
    public static JsonObject installSkill(String sourceId, String skillPath, String scope,
                                          String provider, String cwd)
            throws MarketFetchException, IOException {
        SkillMarketSource src = findSource(sourceId);
        if (src == null) {
            throw new MarketFetchException("UNKNOWN_SOURCE");
        }
        String dirName = lastSegment(skillPath);
        if (dirName.isEmpty()) {
            throw new MarketFetchException("INVALID_SKILL_NAME");
        }

        // 1. 临时目录(tarball + 解压中间产物,放托管 tmp 走既有清理)
        File tmpBase = CliTempDir.getManagedTempDir();
        if (tmpBase == null) {
            tmpBase = new File(System.getProperty("java.io.tmpdir"));
        }
        String stamp = String.valueOf(System.currentTimeMillis());
        File tarball = new File(tmpBase, "skill-market-" + stamp + ".tar.gz");
        File extractDir = new File(tmpBase, "skill-extract-" + stamp);

        try {
            // 2. 下载 tarball(codeload → objects.githubusercontent.com,跟随重定向)
            httpDownload(buildTarballUrl(src), tarball);

            // 3. SHA-256 哈希
            String hash = SkillLockService.computeSha256(tarball.toPath());

            // 4. 哈希校验(首次记录,重装比对;不匹配=篡改→拒绝)
            if (!SkillLockService.verifyHash(dirName, hash)) {
                LOG.warn("[SkillMarket] Hash mismatch for " + dirName + " from " + src.id() + ", aborting install");
                throw new MarketFetchException("HASH_MISMATCH");
            }

            // 5. 解压(纯 Java commons-compress,ZipSlip 防御)
            Files.createDirectories(extractDir.toPath());
            BridgeArchiveExtractor.extractTarGz(tarball, extractDir, null);

            // 6. 定位含 SKILL.md 的 skill 目录(tarball 顶层 {repo}-{branch}/,其下 {skillPath})
            File skillDir = locateSkillDir(extractDir, skillPath);
            if (skillDir == null) {
                throw new MarketFetchException(MarketFetchException.PARSE_ERROR);
            }

            // 7. frontmatter 解析拿规范 skillName + 二次校验
            SkillFrontmatterParser.SkillMetadata meta = SkillFrontmatterParser.parse(skillDir.toPath());
            String finalName = (meta != null && meta.name() != null && !meta.name().isEmpty())
                    ? meta.name() : dirName;
            if (!SkillFrontmatterParser.isValidSkillName(finalName)) {
                throw new MarketFetchException("INVALID_SKILL_NAME");
            }

            // 8. 经统一路由安装(Claude/Codex/OpenCode 各自目录 + 安全栈)
            JsonObject importResult = UnifiedSkillServiceRegistry.forProvider(provider)
                    .importSkills(List.of(skillDir.getAbsolutePath()), scope, cwd);

            boolean ok = importResult != null && importResult.has("success")
                    && importResult.get("success").isJsonPrimitive()
                    && importResult.get("success").getAsBoolean();
            if (ok) {
                SkillLockService.recordInstall(finalName, src.id(), "github", hash, scope);
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", ok);
            result.addProperty("skillName", finalName);
            result.addProperty("source", src.id());
            result.addProperty("hash", hash);
            result.add("importResult", importResult != null ? importResult : new JsonObject());
            return result;
        } finally {
            // 清理中间产物(tarball + 解压目录)
            BridgeArchiveExtractor.deleteDirectory(tarball);
            BridgeArchiveExtractor.deleteDirectory(extractDir);
        }
    }

    /**
     * 获取单个 skill 详情:raw 下载 SKILL.md → 写临时目录 →
     * {@link SkillFrontmatterParser#parse} 解析 frontmatter(name/description/license/...)。
     *
     * <p>列表({@link #listMarketSkills})走 Contents API 快速路径,只返回 name/path(不读 SKILL.md 内容,
     * 避免 N 次 GitHub 请求撞 60 req/h 限流);详情按需拉取单个 SKILL.md(用户主动点击,单文件请求不撞限流)。
     * <b>取数路径</b>:走 Contents API 单文件端点({@code api.github.com},与列表同域名)而非
     * {@code raw.githubusercontent.com}——后者在部分网络环境(如中国大陆)DNS 污染/不可达会导致详情必超时;
     * 前者复用列表已验证可达的域名,响应 {@code content} 字段 base64 编码(经 {@link #decodeContentFileBody} 解码)。
     * 大写 SKILL.md 优先,404 fallback 小写 skill.md(对称 {@link #hasSkillMd} 双写兼容)。
     *
     * @param sourceId  源 id(anthropics/vercel/superpowers)
     * @param skillPath skill 相对仓库根路径(Contents API 返回的 path,如 "skills/pdf")
     * @return {name, description, license?, compatibility?, allowedTools?, userInvocable, paths:[...], path, source, sourceLabel}
     * @throws MarketFetchException UNKNOWN_SOURCE/INVALID_SKILL_NAME/HTTP_404/HTTP_403/NETWORK_ERROR/TIMEOUT/PARSE_ERROR
     */
    public static JsonObject getSkillMarketDetail(String sourceId, String skillPath) throws MarketFetchException {
        SkillMarketSource src = findSource(sourceId);
        if (src == null) {
            throw new MarketFetchException("UNKNOWN_SOURCE");
        }
        String dirName = lastSegment(skillPath);
        if (dirName.isEmpty()) {
            throw new MarketFetchException("INVALID_SKILL_NAME");
        }

        // 经 Contents API 单文件端点拉取 SKILL.md(api.github.com,与列表同域名,国内可达)。
        // raw.githubusercontent.com 在部分网络环境 DNS 污染/不可达 → 详情必超时;复用列表已验证可达的
        // Contents API(响应 content 字段 base64 编码)规避可达性问题。大写优先,404 fallback 小写。
        String body;
        try {
            body = httpGetJson(buildContentsUrl(src, joinPath(skillPath, "SKILL.md")));
        } catch (MarketFetchException e) {
            if ("HTTP_404".equals(e.getErrorCode())) {
                body = httpGetJson(buildContentsUrl(src, joinPath(skillPath, "skill.md")));
            } else {
                throw e;
            }
        }
        String content = decodeContentFileBody(body);

        // 写临时目录复用 SkillFrontmatterParser.parse(含 frontmatter 提取 + 正文首段 fallback)
        File tmpBase = CliTempDir.getManagedTempDir();
        if (tmpBase == null) {
            tmpBase = new File(System.getProperty("java.io.tmpdir"));
        }
        File skillDir = new File(tmpBase, "skill-detail-" + System.currentTimeMillis()
                + File.separator + dirName);
        try {
            Files.createDirectories(skillDir.toPath());
            Files.writeString(new File(skillDir, "SKILL.md").toPath(), content, StandardCharsets.UTF_8);

            SkillFrontmatterParser.SkillMetadata meta = SkillFrontmatterParser.parse(skillDir.toPath());
            JsonObject result = new JsonObject();
            String name = (meta != null && meta.name() != null && !meta.name().isEmpty())
                    ? meta.name() : dirName;
            result.addProperty("name", name);
            result.addProperty("description", meta != null && meta.description() != null
                    ? meta.description() : "");
            if (meta != null) {
                if (meta.license() != null) {
                    result.addProperty("license", meta.license());
                }
                if (meta.compatibility() != null) {
                    result.addProperty("compatibility", meta.compatibility());
                }
                if (meta.allowedTools() != null) {
                    result.addProperty("allowedTools", meta.allowedTools());
                }
                result.addProperty("userInvocable", meta.userInvocable());
                JsonArray pathsArr = new JsonArray();
                if (meta.paths() != null) {
                    for (String p : meta.paths()) {
                        pathsArr.add(p);
                    }
                }
                result.add("paths", pathsArr);
            }
            result.addProperty("path", skillPath != null ? skillPath : "");
            result.addProperty("source", src.id());
            result.addProperty("sourceLabel", src.label());
            return result;
        } catch (IOException e) {
            LOG.warn("[SkillMarket] detail parse failed for " + skillPath + ": " + e.getMessage());
            throw new MarketFetchException(MarketFetchException.PARSE_ERROR, e);
        } finally {
            // 清理临时 skill 目录(含父 stamp 目录)
            BridgeArchiveExtractor.deleteDirectory(skillDir.getParentFile());
        }
    }

    // ── 纯函数:解析与定位 ──

    /**
     * 解析 Contents API 响应 → {sources, source, sourceLabel, skills:[{name,path}]}。
     * 过滤 type=dir 且非隐藏目录(以 . 开头)。容错:非数组/解析失败→空 skills。
     */
    static JsonObject parseContentsResponse(String body, SkillMarketSource src) {
        JsonObject result = new JsonObject();
        result.add("sources", listSources());
        result.addProperty("source", src.id());
        result.addProperty("sourceLabel", src.label());
        JsonArray skills = new JsonArray();

        if (body != null && !body.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(body);
                if (root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        if (e.isJsonObject()) {
                            JsonObject item = e.getAsJsonObject();
                            String type = optStr(item, "type");
                            String name = optStr(item, "name");
                            if ("dir".equals(type) && name != null && !name.isEmpty() && !name.startsWith(".")) {
                                JsonObject s = new JsonObject();
                                s.addProperty("name", name);
                                s.addProperty("path", optStr(item, "path"));
                                skills.add(s);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非法 JSON → 空 skills(容错)
            }
        }

        result.add("skills", skills);
        return result;
    }

    /**
     * 在解压目录中定位含 SKILL.md 的 skill 目录(纯函数)。
     * <p>tarball 顶层是 {repo}-{branch}/,其下 {skillPath}。先尝试完整路径匹配
     * (extractDir/{topDir}/{skillPath});未命中则递归找同名目录(容错 path 格式差异)。
     *
     * @return skill 目录 File,或 null
     */
    static File locateSkillDir(File extractDir, String skillPath) {
        if (extractDir == null || !extractDir.isDirectory()) {
            return null;
        }
        String name = lastSegment(skillPath);
        File[] tops = extractDir.listFiles(File::isDirectory);
        if (tops == null) {
            return null;
        }
        // 1. 完整路径匹配:extractDir/{top}/{skillPath}
        String rel = skillPath != null ? skillPath.replace('\\', '/') : "";
        for (File top : tops) {
            File candidate = new File(top, rel);
            if (hasSkillMd(candidate)) {
                return candidate;
            }
        }
        // 2. 递归找同名 + 含 SKILL.md 的目录
        if (name.isEmpty()) {
            return null;
        }
        for (File top : tops) {
            File found = findDirByName(top, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 递归找第一个名为 {name} 且含 SKILL.md 的目录。 */
    private static File findDirByName(File root, String name) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                if (c.getName().equals(name) && hasSkillMd(c)) {
                    return c;
                }
                File found = findDirByName(c, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasSkillMd(File dir) {
        return new File(dir, "SKILL.md").isFile() || new File(dir, "skill.md").isFile();
    }

    /** 取路径最后一段(skillPath → skill 目录名)。 */
    static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static String optStr(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
            return o.get(key).getAsString();
        }
        return "";
    }

    // ── HTTP 集成 ──

    /** GET JSON(Contents API)。错误分级:404→HTTP_404,403→HTTP_403(rate limit),其他非 2xx→HTTP_xxx,异常→NETWORK_ERROR。 */
    private static String httpGetJson(String url) throws MarketFetchException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", GITHUB_ACCEPT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return checkAndBody(resp.statusCode(), resp.body(), url);
        } catch (MarketFetchException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            // connect/request 超时单独分级:与"无网络"区分,前端提示超时并可重试
            LOG.debug("[SkillMarket] GET " + url + " timed out: " + e);
            throw new MarketFetchException(MarketFetchException.TIMEOUT, e);
        } catch (Exception e) {
            LOG.debug("[SkillMarket] GET " + url + " failed: " + e);
            throw new MarketFetchException(MarketFetchException.NETWORK_ERROR, e);
        }
    }

    /** GET 文本(raw SKILL.md)。错误分级同 {@link #httpGetJson}。 */
    private static String httpGetString(String url) throws MarketFetchException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/plain, text/markdown, */*")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return checkAndBody(resp.statusCode(), resp.body(), url);
        } catch (MarketFetchException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.debug("[SkillMarket] GET " + url + " timed out: " + e);
            throw new MarketFetchException(MarketFetchException.TIMEOUT, e);
        } catch (Exception e) {
            LOG.debug("[SkillMarket] GET " + url + " failed: " + e);
            throw new MarketFetchException(MarketFetchException.NETWORK_ERROR, e);
        }
    }

    /** 下载 tarball 到文件(codeload→objects.githubusercontent.com,跟随重定向)。 */
    private static void httpDownload(String url, File target) throws MarketFetchException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", GITHUB_ACCEPT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(target.toPath()));
            String ignored = checkAndBody(resp.statusCode(), null, url);
        } catch (MarketFetchException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            LOG.debug("[SkillMarket] download " + url + " timed out: " + e);
            throw new MarketFetchException(MarketFetchException.TIMEOUT, e);
        } catch (Exception e) {
            LOG.debug("[SkillMarket] download " + url + " failed: " + e);
            throw new MarketFetchException(MarketFetchException.NETWORK_ERROR, e);
        }
    }

    /** 状态码分级:404→HTTP_404,403→HTTP_403,其他非 2xx→HTTP_xxx;2xx 返回 body。 */
    private static String checkAndBody(int code, String body, String url) throws MarketFetchException {
        if (code == 404) {
            throw new MarketFetchException("HTTP_404");
        }
        if (code == 403) {
            throw new MarketFetchException("HTTP_403");
        }
        if (code < 200 || code >= 300) {
            throw new MarketFetchException("HTTP_" + code);
        }
        return body;
    }
}
