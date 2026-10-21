package com.variable.pdf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * Schedules page renders for one PDF session. It has no JavaFX or PDFBox dependency so its behavior can be tested.
 *
 * <p>Threading: {@code request}, {@code setScale}, {@code close} and the {@link Listener} callbacks all run on the
 * "UI" side (the {@code uiExecutor}). A single worker thread performs rendering, and also any task passed to
 * {@link #runSerial}, so the document is only ever touched by one thread.
 *
 * <p>The pending queue is replaced, never appended to, on each {@link #request}: work that is no longer wanted is
 * dropped before it starts. A render that is already running cannot be interrupted, but its result is discarded
 * if the scale changed or the coordinator closed in the meantime.
 */
public final class PdfRenderCoordinator<V> {
    /** Produces a page image on the worker thread. */
    public interface Renderer<V> {
        V render(int pageIndex, double scale) throws Exception;
    }

    /** Receives results on the UI side. */
    public interface Listener<V> {
        void pageReady(int pageIndex, V value);

        void pageFailed(int pageIndex);
    }

    /** Counters for tests and logging; read on the UI side. */
    public record Stats(long rendered, long cacheHits, long staleDropped, long pendingDiscarded,
                        long renderNanos, int cachedPages, long cachedBytes) {
    }

    private final Renderer<V> renderer;
    private final Executor uiExecutor;
    private final Listener<V> listener;
    private final ExecutorService worker;
    private final PageRenderCache<V> cache;
    private final Set<Integer> failed = new HashSet<>();

    private final Object lock = new Object();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;
    private double scale = 1.0;
    private volatile long generation;
    private int inFlightPage = -1;
    private long inFlightGeneration = -1;

    private final AtomicLong rendered = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong staleDropped = new AtomicLong();
    private final AtomicLong pendingDiscarded = new AtomicLong();
    private final AtomicLong renderNanos = new AtomicLong();

    public PdfRenderCoordinator(Renderer<V> renderer, ToLongFunction<V> weigher, long cacheBudgetBytes,
                                Executor uiExecutor, Listener<V> listener, String threadName) {
        this.renderer = renderer;
        this.uiExecutor = uiExecutor;
        this.listener = listener;
        this.cache = new PageRenderCache<>(cacheBudgetBytes, weigher);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Runs a task on the worker thread, in order with renders (used to open the document). */
    public void runSerial(Runnable task) {
        synchronized (lock) {
            if (closed) return;
        }
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Closed concurrently; the task is no longer wanted.
        }
    }

    public double scale() {
        synchronized (lock) {
            return scale;
        }
    }

    /** Changes the raster scale. Cached images and queued work for the old scale are dropped. */
    public void setScale(double newScale) {
        synchronized (lock) {
            if (closed || newScale == scale) return;
            scale = newScale;
            generation++;
            pendingDiscarded.addAndGet(pending.size());
            pending.clear();
        }
        cache.clear();
        failed.clear();
    }

    public V cached(int pageIndex) {
        return cache.get(PageRenderCache.Key.of(pageIndex, scale()));
    }

    /**
     * Declares which pages are wanted now, most important first. Cached pages are delivered immediately; the rest
     * replace whatever was queued before.
     */
    public void request(List<Integer> wanted) {
        List<Integer> hitPages = new ArrayList<>();
        List<V> hitValues = new ArrayList<>();
        boolean startWorker = false;
        synchronized (lock) {
            if (closed) return;
            Set<Integer> wantedSet = new HashSet<>(wanted);
            for (int old : pending) if (!wantedSet.contains(old)) pendingDiscarded.incrementAndGet();
            pending.clear();
            for (int page : wanted) {
                if (failed.contains(page)) continue;
                V hit = cache.get(PageRenderCache.Key.of(page, scale));
                if (hit != null) {
                    hitPages.add(page);
                    hitValues.add(hit);
                } else if (!(inFlightPage == page && inFlightGeneration == generation)) {
                    pending.addLast(page);
                }
            }
            if (!pending.isEmpty() && !draining) {
                draining = true;
                startWorker = true;
            }
        }
        for (int i = 0; i < hitPages.size(); i++) {
            cacheHits.incrementAndGet();
            listener.pageReady(hitPages.get(i), hitValues.get(i));
        }
        if (startWorker) {
            try {
                worker.execute(this::drain);
            } catch (RejectedExecutionException ignored) {
                synchronized (lock) {
                    draining = false;
                }
            }
        }
    }

    private void drain() {
        while (true) {
            int page;
            long gen;
            double renderScale;
            synchronized (lock) {
                if (closed || pending.isEmpty()) {
                    draining = false;
                    return;
                }
                page = pending.pollFirst();
                gen = generation;
                renderScale = scale;
                inFlightPage = page;
                inFlightGeneration = gen;
            }
            long start = System.nanoTime();
            V result = null;
            boolean ok = true;
            try {
                result = renderer.render(page, renderScale);
            } catch (Throwable failure) {
                ok = false;
            }
            renderNanos.addAndGet(System.nanoTime() - start);
            rendered.incrementAndGet();
            synchronized (lock) {
                inFlightPage = -1;
                inFlightGeneration = -1;
            }
            final V value = result;
            final boolean success = ok;
            final double usedScale = renderScale;
            final long usedGeneration = gen;
            uiExecutor.execute(() -> deliver(page, usedGeneration, usedScale, success, value));
        }
    }

    private void deliver(int page, long gen, double usedScale, boolean ok, V value) {
        boolean isClosed;
        synchronized (lock) {
            isClosed = closed;
        }
        if (isClosed || gen != generation) {
            staleDropped.incrementAndGet();
            return;
        }
        if (ok && value != null) {
            cache.put(PageRenderCache.Key.of(page, usedScale), value);
            listener.pageReady(page, value);
        } else {
            failed.add(page);
            listener.pageFailed(page);
        }
    }

    public Stats stats() {
        return new Stats(rendered.get(), cacheHits.get(), staleDropped.get(), pendingDiscarded.get(),
                renderNanos.get(), cache.size(), cache.bytes());
    }

    /**
     * Stops all work. Queued renders are discarded, results still in flight are ignored, and {@code workerCleanup}
     * runs on the worker thread after any render already in progress (so the document is never closed mid-render).
     */
    public void close(Runnable workerCleanup) {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pendingDiscarded.addAndGet(pending.size());
            pending.clear();
        }
        cache.clear();
        failed.clear();
        try {
            if (workerCleanup != null) worker.execute(workerCleanup);
        } catch (RejectedExecutionException ignored) {
            // Already shut down.
        }
        worker.shutdown();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }
}
