package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * MCP 容器型 runner 业务枚举(SSOT)。
 *
 * <p>「会拉取并运行任意镜像」的 command 首词表(docker/podman)。与
 * {@link McpPackageRunner} 同属前端二次确认弹窗 + 后端 known-runner 校验的共享词表,
 * 见该枚举注释的漂移背景。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum McpContainerRunner implements ProtocolValue {

    DOCKER("docker"),
    PODMAN("podman");

    private final String value;

    McpContainerRunner(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<McpContainerRunner> fromValue(String value) {
        return Arrays.stream(values()).filter(runner -> runner.value.equals(value)).findFirst();
    }
}
