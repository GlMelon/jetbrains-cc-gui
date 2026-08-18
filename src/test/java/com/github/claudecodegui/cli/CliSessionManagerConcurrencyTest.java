package com.github.claudecodegui.cli;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * 会话隔离并发验收(实施计划 §9.1「两会话并发互相阻塞 = 0 次,必须有自动化测试」;
 * 设计文档 §2.3 红线——旧 SDK daemon 因全局串行链导致跨会话堵塞,不得复刻)。
 *
 * <p>用可阻塞的假 {@link CliSession} 精确控制轮完成时机(CountDownLatch,非盲 sleep 计时):
 * <ul>
 *   <li>跨 tab:慢会话轮未完成期间,快会话必须能独立完成(零阻塞红线);</li>
 *   <li>同 tab:第二条消息必须等第一条完成后才开始(不交错、不丢失)。</li>
 * </ul>
 */
public class CliSessionManagerConcurrencyTest {

    /** 可控假会话:blocking=true 时 send 占住执行线程直至 release 放行,否则立即完成。 */
    private static final class BlockingSession implements CliSession {
        final CountDownLatch release = new CountDownLatch(1);
        final boolean blocking;
        final AtomicInteger sendStarts = new AtomicInteger();
        final List<String> startedMessages = new CopyOnWriteArrayList<>();

        BlockingSession(boolean blocking) {
            this.blocking = blocking;
        }

        @Override
        public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
            sendStarts.incrementAndGet();
            startedMessages.add(request.message());
            if (blocking) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            callback.onComplete(true, request.message(), null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void dispose() {
        }
    }

    /** 每 tab 独立 BlockingSession 的工厂(对齐真实 per-tab CliSession 语义)。 */
    private static final class PerTabBlockingFactory implements CliSessionFactory {
        final ConcurrentHashMap<String, BlockingSession> sessions = new ConcurrentHashMap<>();

        @Override
        public String provider() {
            return "claude";
        }

        @Override
        public CliSession create(String tabId) {
            return sessions.computeIfAbsent(tabId, k -> new BlockingSession(true));
        }

        /** 预置一个立即完成的会话(快会话对照用)。 */
        void seedNonBlocking(String tabId) {
            sessions.put(tabId, new BlockingSession(false));
        }
    }

    private static final MessageCallback NOOP_CALLBACK = new MessageCallback() {
        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(SDKResult result) {
        }
    };

    private static CliSendRequest request(String tabId, String message) {
        return new CliSendRequest(tabId, "claude", message,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    /** 轮询安全读取:会话尚未异步建出时返回 0。 */
    private static int sendStarts(PerTabBlockingFactory factory, String tabId) {
        BlockingSession session = factory.sessions.get(tabId);
        return session != null ? session.sendStarts.get() : 0;
    }

    @Test
    public void slowSessionDoesNotBlockOtherTab() throws Exception {
        PerTabBlockingFactory factory = new PerTabBlockingFactory();
        CliSessionManager manager = new CliSessionManager(List.of(factory));

        CompletableFuture<SDKResult> slow = manager.send(request("tab-slow", "slow-msg"), NOOP_CALLBACK);
        // 等慢会话真正进入 send(占住一个执行线程),确保并发窗口成立
        // (会话在 dispatchSend 异步线程内才创建,轮询须容忍尚未建出的窗口)
        waitFor("slow session send started",
                () -> sendStarts(factory, "tab-slow") >= 1);

        factory.seedNonBlocking("tab-fast");
        CompletableFuture<SDKResult> fast = manager.send(request("tab-fast", "fast-msg"), NOOP_CALLBACK);

        // 红线:慢会话未完成期间,快会话必须能独立完成(跨会话零阻塞)
        fast.get(10, TimeUnit.SECONDS);
        assertFalse("slow tab must still be in-flight while fast tab completed", slow.isDone());
        assertEquals(1, factory.sessions.get("tab-fast").sendStarts.get());

        factory.sessions.get("tab-slow").release.countDown();
        slow.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void sameTabSendsAreSerializedInOrder() throws Exception {
        PerTabBlockingFactory factory = new PerTabBlockingFactory();
        CliSessionManager manager = new CliSessionManager(List.of(factory));

        CompletableFuture<SDKResult> first = manager.send(request("tab-serial", "msg-1"), NOOP_CALLBACK);
        waitFor("first send started",
                () -> sendStarts(factory, "tab-serial") >= 1);

        CompletableFuture<SDKResult> second = manager.send(request("tab-serial", "msg-2"), NOOP_CALLBACK);

        // 同 tab 串行:第二条在第一条完成前不得开始(留 300ms 观察窗,防偶发调度延迟误判)
        Thread.sleep(300);
        assertEquals("second send must wait for the first to finish", 1,
                factory.sessions.get("tab-serial").sendStarts.get());

        factory.sessions.get("tab-serial").release.countDown();
        second.get(10, TimeUnit.SECONDS);
        first.get(10, TimeUnit.SECONDS);
        assertEquals(2, factory.sessions.get("tab-serial").sendStarts.get());
        assertEquals(List.of("msg-1", "msg-2"), factory.sessions.get("tab-serial").startedMessages);
    }
}
