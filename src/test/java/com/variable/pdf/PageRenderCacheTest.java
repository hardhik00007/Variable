package com.variable.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PageRenderCacheTest {
    private static PageRenderCache.Key key(int page) {
        return PageRenderCache.Key.of(page, 1.0);
    }

    @Test void evictsLeastRecentlyUsedWhenOverByteBudget() {
        var cache = new PageRenderCache<String>(10, String::length);
        cache.put(key(0), "aaaa");
        cache.put(key(1), "bbbb");
        cache.put(key(2), "cccc");           // 12 bytes > 10: evicts page 0
        assertNull(cache.get(key(0)));
        assertEquals(2, cache.size());
        assertEquals(8, cache.bytes());

        cache.get(key(1));                   // touch page 1, so page 2 is now least recent
        cache.put(key(3), "dddd");
        assertNull(cache.get(key(2)));
        assertNotNull(cache.get(key(1)));
        assertNotNull(cache.get(key(3)));
    }

    @Test void keepsAnOversizedEntryAloneRatherThanDroppingIt() {
        var cache = new PageRenderCache<String>(10, String::length);
        cache.put(key(0), "aa");
        cache.put(key(1), "x".repeat(20));
        assertNull(cache.get(key(0)));
        assertNotNull(cache.get(key(1)));
        assertEquals(1, cache.size());
    }

    @Test void replacingAKeyUpdatesAccounting() {
        var cache = new PageRenderCache<String>(100, String::length);
        cache.put(key(0), "aaaa");
        cache.put(key(0), "bb");
        assertEquals(1, cache.size());
        assertEquals(2, cache.bytes());
    }

    @Test void differentScalesAreDifferentEntriesAndClearEmpties() {
        var cache = new PageRenderCache<String>(100, String::length);
        cache.put(PageRenderCache.Key.of(0, 1.0), "a");
        cache.put(PageRenderCache.Key.of(0, 2.0), "bb");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
    }
}
