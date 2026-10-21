package com.variable.pdf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the coordinator with a fake renderer. No JavaFX, PDFBox, or timing assumptions. */
class PdfRenderCoordinatorTest {
    private final UiQueue ui = new UiQueue();
    private final List<String> renderCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch firstRenderStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirstRender = new CountDownLatch(1);
    private volatile boolean blockFirstRender;
    private volatile int failingPage = -1;
    private PdfRenderCoordinator<String> coordinator;

    private PdfRenderCoordinator<String> create(long budget) {
        coordinator = new PdfRenderCoordinator<>(
                (page, scale) -> {
                    boolean first;
                    synchronized (renderCalls) {
                        first = renderCalls.isEmpty();
                        renderCalls.add(page + "@" + scale);
                    }
                    if (page == failingPage) throw new IllegalStateException("boom");
                    if (first && blockFirstRender) {
                        firstRenderStarted.countDown();
                        releaseFirstRender.await(5, TimeUnit.SECONDS);
                    }
                    return "p" + page + "@" + scale;
                },
                value -> 100,
                budget,
                ui,
                new PdfRenderCoordinator.Listener<String>() {
                    @Override public void pageReady(int pageIndex, String value) { events.add(value); }
                    @Override public void pageFailed(int pageIndex) { failures.add("p" + pageIndex); }
                },
                "test-renderer");
        return coordinator;
    }

    @AfterEach void tearDown() throws Exception {
        releaseFirstRender.countDown();
        if (coordinator != null) {
            coordinator.close(null);
            coordinator.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test void reusesValidCachedRender() throws Exception {
        var c = create(10_000);
        c.request(List.of(0));
        ui.pumpUntil(() -> events.size() == 1);
        c.request(List.of(0));                       // served from cache, synchronously
        assertEquals(2, events.size());
        assertEquals(1, renderCalls.size());
        var stats = c.stats();
        assertEquals(1, stats.rendered());
        assertEquals(1, stats.cacheHits());
        System.out.println("  [metric] rendered=" + stats.rendered() + " cacheHits=" + stats.cacheHits());
    }

    @Test void scaleChangeInvalidatesCacheAndRendersAgain() throws Exception {
        var c = create(10_000);
        c.request(List.of(0));
        ui.pumpUntil(() -> events.size() == 1);
        assertNotNull(c.cached(0));

        c.setScale(2.0);
        assertNull(c.cached(0));
        assertEquals(0, c.stats().cachedPages());

        c.request(List.of(0));
        ui.pumpUntil(() -> events.size() == 2);
        assertEquals("p0@2.0", events.get(1));
        assertEquals(2, renderCalls.size());
        assertEquals(1, c.stats().cachedPages());
    }

    @Test void cacheStaysWithinByteBudget() throws Exception {
        var c = create(300);                         // three 100-byte entries
        for (int page = 0; page < 10; page++) {
            int expected = page + 1;
            c.request(List.of(page));
            ui.pumpUntil(() -> events.size() == expected);
            assertTrue(c.stats().cachedBytes() <= 300, "bytes " + c.stats().cachedBytes());
        }
        assertEquals(3, c.stats().cachedPages());
        assertNotNull(c.cached(9));
        assertNull(c.cached(0));
    }

    @Test void rejectsResultsRenderedForAnObsoleteScale() throws Exception {
        blockFirstRender = true;
        var c = create(10_000);
        c.request(List.of(0));
        assertTrue(firstRenderStarted.await(5, TimeUnit.SECONDS));

        c.setScale(2.0);                             // the in-flight 1.0 render is now obsolete
        releaseFirstRender.countDown();
        ui.pumpUntil(() -> c.stats().staleDropped() == 1);

        assertTrue(events.isEmpty(), "stale image must never reach the UI: " + events);
        assertNull(c.cached(0));
        assertEquals(0, c.stats().cachedPages());

        c.request(List.of(0));
        ui.pumpUntil(() -> events.size() == 1);
        assertEquals("p0@2.0", events.get(0));
    }

    @Test void newRequestReplacesQueuedWorkInsteadOfAddingToIt() throws Exception {
        blockFirstRender = true;
        var c = create(10_000);
        c.request(List.of(0, 1, 2));
        assertTrue(firstRenderStarted.await(5, TimeUnit.SECONDS));   // page 0 running; 1 and 2 queued

        c.request(List.of(5));                       // user scrolled away
        releaseFirstRender.countDown();
        ui.pumpUntil(() -> events.size() == 2);

        assertEquals(List.of("0@1.0", "5@1.0"), renderCalls);       // pages 1 and 2 were never rendered
        assertEquals(2, c.stats().pendingDiscarded());
    }

    @Test void doesNotQueueAPageThatIsAlreadyBeingRendered() throws Exception {
        blockFirstRender = true;
        var c = create(10_000);
        c.request(List.of(0));
        assertTrue(firstRenderStarted.await(5, TimeUnit.SECONDS));
        c.request(List.of(0));
        c.request(List.of(0));
        releaseFirstRender.countDown();
        ui.pumpUntil(() -> events.size() == 1);
        ui.drain();
        assertEquals(1, renderCalls.size());
    }

    @Test void failedPageIsReportedOnceAndRetriedOnlyAfterScaleChange() throws Exception {
        failingPage = 1;
        var c = create(10_000);
        c.request(List.of(1));
        ui.pumpUntil(() -> failures.size() == 1);
        c.request(List.of(1));
        ui.drain();
        assertEquals(1, renderCalls.size());
        assertEquals(1, failures.size());

        c.setScale(2.0);
        c.request(List.of(1));
        ui.pumpUntil(() -> failures.size() == 2);
        assertEquals(2, renderCalls.size());
    }

    @Test void closingWithPendingWorkDiscardsItAndIgnoresInFlightResult() throws Exception {
        blockFirstRender = true;
        var c = create(10_000);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        c.request(List.of(0, 1, 2, 3));
        assertTrue(firstRenderStarted.await(5, TimeUnit.SECONDS));

        c.close(() -> order.add("cleanup@" + Thread.currentThread().getName()));
        releaseFirstRender.countDown();
        assertTrue(c.awaitTermination(5, TimeUnit.SECONDS));
        ui.drain();

        assertEquals(List.of("0@1.0"), renderCalls);                 // queued pages never started
        assertEquals(List.of("cleanup@test-renderer"), order);       // ran on the worker thread, after the render
        assertTrue(events.isEmpty());
        assertTrue(failures.isEmpty());
        var stats = c.stats();
        assertEquals(3, stats.pendingDiscarded());
        assertEquals(1, stats.staleDropped());
        assertEquals(0, stats.cachedPages());

        c.request(List.of(7));                                       // no-op after close
        ui.drain();
        assertEquals(1, renderCalls.size());
        c.setScale(3.0);
        assertEquals(1.0, c.scale(), 1e-9);
    }
}
