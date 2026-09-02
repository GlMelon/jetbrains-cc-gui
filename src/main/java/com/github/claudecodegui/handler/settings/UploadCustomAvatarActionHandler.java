package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.avatar.AvatarConfigResult;
import com.github.claudecodegui.settings.avatar.AvatarConfigService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Path;

public final class UploadCustomAvatarActionHandler implements FrontendActionHandler<String> {
    private static final Logger LOG = Logger.getInstance(UploadCustomAvatarActionHandler.class);

    private final AvatarConfigService service;

    public UploadCustomAvatarActionHandler(AvatarConfigService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.AVATAR_UPLOAD_CUSTOM;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                        .withFileFilter(this::isSupportedImage)
                        .withTitle("Select Avatar Image")
                        .withDescription("Select a PNG, JPEG, WebP, or SVG image");
                FileChooser.chooseFile(descriptor, ctx.getProject(), null, file -> handleSelectedFile(payload, ctx, file));
            } catch (Exception e) {
                LOG.warn("[UploadCustomAvatarActionHandler] Failed to open avatar file chooser: " + e.getMessage(), e);
            }
        });
    }

    private boolean isSupportedImage(VirtualFile file) {
        String ext = file.getExtension();
        if (ext == null) {
            return false;
        }
        return ext.equalsIgnoreCase("png")
                || ext.equalsIgnoreCase("jpg")
                || ext.equalsIgnoreCase("jpeg")
                || ext.equalsIgnoreCase("webp")
                || ext.equalsIgnoreCase("svg");
    }

    private void handleSelectedFile(String payload, HandlerContext ctx, VirtualFile file) {
        if (file == null) {
            return;
        }
        AvatarConfigResult result = service.uploadCustom(payload, Path.of(file.getPath()), file.getName());
        ctx.dispatchEvent(DownstreamEvent.AVATAR_CONFIG_APPLY.value(), result.configJson());
    }
}
