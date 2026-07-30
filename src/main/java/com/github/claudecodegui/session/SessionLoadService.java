package com.github.claudecodegui.session;

/**
 * Session load service (singleton).
 * Used to pass session load requests between the "Session History" and "AI Code GUI" tool windows.
 */
public class SessionLoadService {

    private static final SessionLoadService INSTANCE = new SessionLoadService();

    private SessionLoadListener listener;
    private String pendingSessionId;
    private String pendingProjectPath;

    private SessionLoadService() {
    }

    public static SessionLoadService getInstance() {
        return INSTANCE;
    }

    /**
     * Listener for session load events.
     */
    public interface SessionLoadListener {
        void onLoadSessionRequest(String sessionId, String projectPath);
    }

    /**
     * Sets the listener (called by the AI Code GUI window).
     */
    public void setListener(SessionLoadListener listener) {
        this.listener = listener;

        // If there is a pending load request, trigger it immediately
        if (pendingSessionId != null && listener != null) {
            listener.onLoadSessionRequest(pendingSessionId, pendingProjectPath);
            pendingSessionId = null;
            pendingProjectPath = null;
        }
    }
}
