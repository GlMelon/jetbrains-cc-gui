package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebviewEventQueueTest {

    @Test
    public void mergesAdjacentContentDeltasIntoOneOrderedCall() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "a");
        queue.enqueue("onContentDelta", "b");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.onContentDelta('ab')"));
        queue.dispose();
    }

    @Test
    public void preservesLifecycleOrderInsteadOfCollapsingStateAcrossBoundaries() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("showLoading", "true");
        queue.enqueue("onStreamStart");
        queue.enqueue("showLoading", "false");
        scheduled.remove(0).run();

        String script = scripts.get(0);
        assertTrue(script.indexOf("window.showLoading('true')")
                < script.indexOf("window.onStreamStart()"));
        assertTrue(script.indexOf("window.onStreamStart()")
                < script.indexOf("window.showLoading('false')"));
        queue.dispose();
    }

    @Test
    public void separatesSnapshotFromStreamStartSoStartCannotCancelIt() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateMessages", "snapshot", "1");
        queue.enqueue("onStreamStart");
        scheduled.remove(0).run();
        scheduled.remove(0).run();

        assertEquals(2, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateMessages('snapshot', '1')"));
        assertTrue(scripts.get(1).contains("window.onStreamStart()"));
        queue.dispose();
    }

    @Test
    public void replacesLatestOnlyStateWhenAdjacent() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateStatus", "first");
        queue.enqueue("updateStatus", "second");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateStatus('second')"));
        assertTrue(!scripts.get(0).contains("first"));
        queue.dispose();
    }

    @Test
    public void clearsPendingEventsOnBrowserChange() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateStatus", "stale");
        // Swap the browser: pending events for the old instance must be dropped.
        browser.set(new Object());
        queue.browserChanged();
        queue.enqueue("updateStatus", "fresh");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateStatus('fresh')"));
        assertTrue(!scripts.get(0).contains("stale"));
        queue.dispose();
    }

    @Test
    public void dropsCallsWithUnescapedArgsInsteadOfPoisoningTheBatch() {
        // Regression for the stuck-streaming bug: a pre-quoted arg like "'backend'"
        // used to build window.__lastStreamEndSource(''backend'') — a parse-time
        // SyntaxError that silently discarded the WHOLE batch script, including
        // the onStreamEnd / showLoading(false) calls batched after it. The queue
        // must drop the single offending call so the rest of the batch survives.
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("__lastStreamEndSource", "'backend'");
        queue.enqueue("onStreamEnd", "42");
        queue.enqueue("showLoading", "false");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(!scripts.get(0).contains("__lastStreamEndSource"));
        assertTrue(scripts.get(0).contains("window.onStreamEnd('42')"));
        assertTrue(scripts.get(0).contains("window.showLoading('false')"));
        queue.dispose();
    }

    @Test
    public void detectsUnescapedJsLiteralArgs() {
        // Raw single quote / line terminators / trailing backslash cannot be
        // embedded between single quotes and indicate an unescaped payload.
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"it's raw"}));
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"line\nbreak"}));
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"cr\rbreak"}));
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"endswith\\"}));
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"sep\u2028erator"}));
        assertTrue(WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"ok", "'bad'"}));

        // escapeJs output never trips the guard: quotes/newlines are backslash
        // escape pairs, and a backslash is always followed by more text. A raw
        // tab is legal inside a JS string literal, so it is not fatal either.
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"escaped\\'quote"}));
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"escaped\\nnewline"}));
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"tab\there"}));
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(new String[]{"42"}));
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(new String[]{}));
        assertTrue(!WebviewEventQueue.containsUnescapedJsLiteral(null));
    }

    private static WebviewEventQueue<Object> newQueue(
            AtomicReference<Object> browser,
            AtomicBoolean disposed,
            List<Runnable> scheduled,
            List<String> scripts
    ) {
        return new WebviewEventQueue<>(
                browser::get,
                disposed::get,
                scheduled::add,
                (ignoredBrowser, script) -> scripts.add(script)
        );
    }
}
