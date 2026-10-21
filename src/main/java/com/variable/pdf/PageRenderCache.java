package com.variable.pdf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Least-recently-used cache bounded by a byte budget rather than an entry count, because page images vary
 * enormously in size with zoom and display scale. Not thread-safe: confine it to one thread (the UI thread).
 */
public final class PageRenderCache<V> {
    /** A page rendered at a particular raster scale (stored in thousandths). */
    public record Key(int pageIndex, int scaleMilli) {
        public static Key of(int pageIndex, double scale) {
            return new Key(pageIndex, (int) Math.round(scale * 1000.0));
        }
    }

    private record Entry<V>(V value, long weight) {
    }

    private final LinkedHashMap<Key, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long budgetBytes;
    private final ToLongFunction<V> weigher;
    private long bytes;

    public PageRenderCache(long budgetBytes, ToLongFunction<V> weigher) {
        this.budgetBytes = budgetBytes;
        this.weigher = weigher;
    }

    public V get(Key key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    public void put(Key key, V value) {
        Entry<V> previous = entries.remove(key);
        if (previous != null) bytes -= previous.weight();
        long weight = Math.max(0, weigher.applyAsLong(value));
        entries.put(key, new Entry<>(value, weight));
        bytes += weight;
        // Evict least-recently-used entries, but never the one just inserted.
        var iterator = entries.entrySet().iterator();
        while (bytes > budgetBytes && entries.size() > 1 && iterator.hasNext()) {
            Map.Entry<Key, Entry<V>> eldest = iterator.next();
            iterator.remove();
            bytes -= eldest.getValue().weight();
        }
    }

    public void clear() {
        entries.clear();
        bytes = 0;
    }

    public int size() {
        return entries.size();
    }

    public long bytes() {
        return bytes;
    }
}
