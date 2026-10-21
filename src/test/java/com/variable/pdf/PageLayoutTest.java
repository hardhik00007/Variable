package com.variable.pdf;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageLayoutTest {
    @Test void computesTopsAndTotal() {
        var layout = new PageLayout(10);
        layout.update(new double[]{100, 200, 100});
        assertEquals(0.0, layout.topOf(0), 1e-9);
        assertEquals(110.0, layout.topOf(1), 1e-9);
        assertEquals(320.0, layout.topOf(2), 1e-9);
        assertEquals(420.0, layout.totalHeight(), 1e-9);
    }

    @Test void findsCurrentAndVisiblePagesAtBoundaries() {
        var layout = new PageLayout(10);
        layout.update(new double[]{100, 200, 100});
        assertEquals(0, layout.pageAt(0));
        assertEquals(0, layout.pageAt(100));
        assertEquals(1, layout.pageAt(101));
        assertEquals(2, layout.pageAt(10_000));
        assertEquals(0, layout.firstVisible(99));
        assertEquals(1, layout.firstVisible(100));
        assertEquals(0, layout.lastVisible(110));
        assertEquals(1, layout.lastVisible(111));
        assertEquals(2, layout.lastVisible(10_000));
        assertEquals(0, layout.lastVisible(0));
    }

    @Test void emptyLayoutDoesNotThrow() {
        var layout = new PageLayout(10);
        assertEquals(0, layout.pageCount());
        assertEquals(0, layout.pageAt(5));
        assertEquals(0, layout.firstVisible(5));
        assertEquals(0, layout.lastVisible(5));
    }

    @Test void binarySearchMatchesLinearScanForMixedPageSizes() {
        Random random = new Random(42);
        double[] heights = new double[5_000];
        for (int i = 0; i < heights.length; i++) heights[i] = 50 + random.nextDouble() * 900;
        var layout = new PageLayout(18);
        layout.update(heights);
        for (int trial = 0; trial < 2_000; trial++) {
            double offset = random.nextDouble() * (layout.totalHeight() + 500) - 100;
            int expectedAt = heights.length - 1;
            for (int i = 0; i < heights.length; i++) {
                if (layout.topOf(i) + heights[i] >= offset) { expectedAt = i; break; }
            }
            assertEquals(expectedAt, layout.pageAt(offset));
            int expectedFirst = heights.length - 1;
            for (int i = 0; i < heights.length; i++) {
                if (layout.topOf(i) + heights[i] > offset) { expectedFirst = i; break; }
            }
            assertEquals(expectedFirst, layout.firstVisible(offset));
            int expectedLast = 0;
            for (int i = 0; i < heights.length; i++) if (layout.topOf(i) < offset) expectedLast = i;
            assertEquals(expectedLast, layout.lastVisible(offset));
        }
    }
}
