package com.analyzercoder.application.llm;

/** Background indexing can wait for quota while keeping network-response budgets bounded. */
public final class EmbeddingRequestScope implements AutoCloseable {
    private static final ThreadLocal<EmbeddingRequestScope> CURRENT = new ThreadLocal<>();
    private final EmbeddingRequestScope previous;
    private final Runnable checkpoint;
    private long waitedNanos;

    private EmbeddingRequestScope(Runnable checkpoint) {
        this.previous = CURRENT.get();
        this.waitedNanos = previous == null ? 0 : previous.waitedNanos;
        this.checkpoint = checkpoint;
        CURRENT.set(this);
    }

    public static EmbeddingRequestScope indexing(Runnable checkpoint) {
        return new EmbeddingRequestScope(checkpoint);
    }

    static boolean canWaitAndRetry() {
        return CURRENT.get() != null;
    }

    static long waitedNanos() {
        return CURRENT.get() == null ? 0 : CURRENT.get().waitedNanos;
    }

    static void waited(long nanos) {
        if (CURRENT.get() != null) CURRENT.get().waitedNanos += Math.max(0, nanos);
    }

    public static void checkpoint() {
        if (CURRENT.get() != null) CURRENT.get().checkpoint.run();
        if (Thread.currentThread().isInterrupted())
            throw new LlmConnectionException("LLM_CHECK_CANCELED", "向量请求等待已取消");
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else {
            previous.waitedNanos = waitedNanos;
            CURRENT.set(previous);
        }
    }
}
