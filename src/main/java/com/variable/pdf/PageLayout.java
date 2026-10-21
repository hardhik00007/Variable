package com.variable.pdf;

/**
 * Vertical page geometry kept as a model, so the visible pages can be found by binary search instead of scanning
 * every page node on each scroll event. Pages may have different heights.
 */
public final class PageLayout {
    private final double gap;
    private double[] tops = new double[0];
    private double[] heights = new double[0];
    private double total;

    public PageLayout(double gap) {
        this.gap = gap;
    }

    /** Replaces the layout. Takes ownership of the array. */
    public void update(double[] pageHeights) {
        heights = pageHeights;
        tops = new double[pageHeights.length];
        double y = 0;
        for (int i = 0; i < pageHeights.length; i++) {
            tops[i] = y;
            y += pageHeights[i] + gap;
        }
        total = pageHeights.length == 0 ? 0 : y - gap;
    }

    public int pageCount() {
        return heights.length;
    }

    public double topOf(int page) {
        return tops[page];
    }

    public double heightOf(int page) {
        return heights[page];
    }

    public double totalHeight() {
        return total;
    }

    /** First page whose bottom edge is at or below {@code offset}; clamped to the last page. */
    public int pageAt(double offset) {
        int n = heights.length;
        int low = 0;
        int high = n;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tops[mid] + heights[mid] >= offset) high = mid;
            else low = mid + 1;
        }
        return Math.min(low, Math.max(0, n - 1));
    }

    /** First page that extends below {@code top} (that is, the first one at least partly visible). */
    public int firstVisible(double top) {
        int n = heights.length;
        int low = 0;
        int high = n;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tops[mid] + heights[mid] > top) high = mid;
            else low = mid + 1;
        }
        return Math.min(low, Math.max(0, n - 1));
    }

    /** Last page that starts above {@code bottom}. */
    public int lastVisible(double bottom) {
        int n = heights.length;
        int low = 0;
        int high = n;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tops[mid] < bottom) low = mid + 1;
            else high = mid;
        }
        return Math.max(0, low - 1);
    }
}
