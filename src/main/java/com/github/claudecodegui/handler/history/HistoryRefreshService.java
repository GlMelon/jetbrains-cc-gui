package com.github.claudecodegui.handler.history;

import com.intellij.openapi.Disposable;

/**
 * Backend-owned history refresh lifecycle triggered by chat stream completion.
 */
public interface HistoryRefreshService extends Disposable {

    /**
     * Applies provider-specific post-stream refresh policy.
     *
     * @param provider protocol provider value for the completed turn
     */
    void onStreamCompleted(String provider);
}
