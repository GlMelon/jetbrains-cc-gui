package com.github.claudecodegui.cli;

import com.google.gson.annotations.SerializedName;

/**
 * CLI环境检查结果数据模型。
 * 用于前端展示CLI工具的安装状态、版本信息等。
 */
public class CliEnvironmentStatus {

    /**
     * CLI工具名称（如 claude、codex、opencode）
     */
    private String name;

    /**
     * 工具显示名称（如 Claude CLI、Codex CLI、OpenCode CLI）
     */
    private String displayName;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 是否已安装
     */
    private boolean installed;

    /**
     * 当前安装版本
     */
    @SerializedName("currentVersion")
    private String version;

    /**
     * 最新可用版本
     */
    private String latestVersion;

    /**
     * 安装路径
     */
    private String installPath;

    /**
     * npm包名
     */
    private String npmPackage;

    /**
     * 安装来源（如 npm、brew、scoop 等）
     */
    private String installSource;

    /**
     * 错误信息（如果检测失败）
     */
    @SerializedName("errorMessage")
    private String error;

    /**
     * 是否有可用更新
     */
    private boolean hasUpdate;

    public CliEnvironmentStatus() {
    }

    public CliEnvironmentStatus(String name, String displayName, String description, String npmPackage) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.npmPackage = npmPackage;
    }

    // ── Getters and Setters ──

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public String getInstallPath() {
        return installPath;
    }

    public void setInstallPath(String installPath) {
        this.installPath = installPath;
    }

    public String getNpmPackage() {
        return npmPackage;
    }

    public void setNpmPackage(String npmPackage) {
        this.npmPackage = npmPackage;
    }

    public String getInstallSource() {
        return installSource;
    }

    public void setInstallSource(String installSource) {
        this.installSource = installSource;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isHasUpdate() {
        return hasUpdate;
    }

    public void setHasUpdate(boolean hasUpdate) {
        this.hasUpdate = hasUpdate;
    }
}
