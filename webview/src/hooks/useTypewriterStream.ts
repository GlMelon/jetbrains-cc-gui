import { useEffect, useRef } from 'react';

/**
 * 流式逐字打字机 —— 对齐 motion-preview.html 的 B 方案(char-pop 逐字弹入)。
 *
 * 设计要点(防卡顿 / 防队列积压):
 * 1. 直接 DOM 追加,绕过 React diff —— 已显示的字符永久留在 DOM,不随 re-render 重建,
 *    每帧成本 = 本帧新增字符数(O(增量),非 O(总量))。
 * 2. 自适应步长:
 *    - backlog 小(≤CATCHUP_BACKLOG): 按 ~28ms/字 优雅逐字;
 *    - backlog 中: 每帧吐 35%,追赶(后端突发快时快速消解);
 *    - backlog 大(>MAX_BACKLOG_FLUSH): 一次吐完(失控保护,绝不让队列无限增长)。
 * 3. 超长降级(POP_LIMIT): 已显示字符超过阈值后,新增字符改用纯文本节点,不再套
 *    `<span class="md-char">`,把 DOM span 总数封顶,防超长回复撑爆 DOM。
 * 4. 流式结束(isStreaming=false)且全部吐完 → 停止 rAF,不再空转。
 * 5. 消息切换(content 前缀与已显示部分不一致)→ 清空容器、计数归零。
 *
 * 注意:流式期间为纯文本逐字(换行→<br>),不渲染 markdown 加粗/链接/代码高亮;
 * 流式结束后由 MarkdownBlock 切换到完整 markdown 管线,格式在那一刻补齐(效果图同款取舍)。
 */
const TARGET_INTERVAL_MS = 28; // 优雅逐字:约 28ms/字(~35 字/秒),对齐效果图节奏
const CATCHUP_BACKLOG = 120; // backlog 超过此值 → 进入追赶模式
const CATCHUP_RATIO = 0.35; // 追赶模式:每帧吐 backlog 的 35%
const MAX_BACKLOG_FLUSH = 600; // backlog 超过此值 → 一次性全量吐(失控保护)
const POP_LIMIT = 1500; // 已显示字符超过此值 → 新增字符放弃 char-pop,纯文本追加(封顶 span 总数:前段逐字弹入,超长回复尾部降级纯文本,避免长消息 DOM span 堆积)

export function useTypewriterStream(
  containerRef: React.RefObject<HTMLDivElement | null>,
  content: string,
  isStreaming: boolean,
): void {
  const shownLenRef = useRef(0);
  const lastTickRef = useRef(0);
  const rafRef = useRef<number | null>(null);
  const contentRef = useRef(content);
  const streamingRef = useRef(isStreaming);
  const prevFullRef = useRef('');

  // 渲染期同步最新值到 ref(rAF 循环通过 ref 读取,始终拿到最新)
  contentRef.current = content;
  streamingRef.current = isStreaming;

  // 消息切换检测:流式是纯追加,新 content 必以「已显示部分」为前缀。
  // 前缀不一致 = 换了消息(content 被替换)→ 清空重置,避免残留旧 DOM。
  useEffect(() => {
    const prev = prevFullRef.current;
    if (prev && shownLenRef.current > 0) {
      const expectedPrefix = prev.slice(0, shownLenRef.current);
      if (!content.startsWith(expectedPrefix)) {
        const el = containerRef.current;
        if (el) el.textContent = '';
        shownLenRef.current = 0;
        lastTickRef.current = 0;
      }
    }
    prevFullRef.current = content;
  }, [content, containerRef]);

  useEffect(() => {
    const tick = (now: number) => {
      const container = containerRef.current;
      const full = contentRef.current;
      const streaming = streamingRef.current;

      // 容器尚未挂载:非流式(历史消息)→ 永久停止;流式中 → 等容器挂载
      if (!container) {
        if (!streaming) {
          rafRef.current = null;
          return;
        }
        rafRef.current = requestAnimationFrame(tick);
        return;
      }

      const shown = shownLenRef.current;
      const backlog = full.length - shown;

      if (backlog > 0) {
        let advance: number;
        if (backlog > MAX_BACKLOG_FLUSH) {
          advance = backlog; // 失控保护:一次吐完
        } else if (backlog > CATCHUP_BACKLOG) {
          advance = Math.max(1, Math.round(backlog * CATCHUP_RATIO)); // 追赶
        } else {
          const dt = now - lastTickRef.current;
          advance = Math.max(1, Math.floor(dt / TARGET_INTERVAL_MS)); // 优雅逐字
        }
        advance = Math.min(advance, backlog);
        appendChars(container, full, shown, shown + advance);
        shownLenRef.current = shown + advance;
      }
      lastTickRef.current = now;

      // 流式已结束且全部吐完 → 停止 rAF(节能;后续由非流式 html 接管)
      if (!streaming && shownLenRef.current >= full.length) {
        rafRef.current = null;
        return;
      }
      rafRef.current = requestAnimationFrame(tick);
    };

    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [containerRef]);
}

/**
 * 把 full[from,to) 的字符批量追加到 container。
 * 用 DocumentFragment 一次性插入,把多次 reflow 压成一次。
 * 超过 POP_LIMIT 后新增字符改用纯文本节点(封顶 span 总数)。
 */
function appendChars(container: HTMLElement, full: string, from: number, to: number): void {
  const frag = document.createDocumentFragment();
  for (let i = from; i < to; i++) {
    const ch = full[i];
    if (ch === '\n') {
      frag.appendChild(document.createElement('br'));
    } else if (i < POP_LIMIT) {
      // 按字符绝对索引 i 判断(非批次起始 from):即便失控保护一次性 from=0
      // 吐出超长 backlog,也只有前 POP_LIMIT 个字符套 span,其余降级纯文本节点,
      // span 总数硬性封顶,防止超长回复撑爆 DOM。
      const span = document.createElement('span');
      span.className = 'md-char';
      span.textContent = ch;
      frag.appendChild(span);
    } else {
      frag.appendChild(document.createTextNode(ch));
    }
  }
  container.appendChild(frag);
}
