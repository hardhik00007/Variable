package com.variable.pdf;

import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** A stand-in for the JavaFX thread: work posted by the render thread runs only when the test pumps it. */
final class UiQueue implements Executor {
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable command) {
        queue.add(command);
    }

    /** Runs posted work until {@code condition} holds. Fails (rather than hangs) after five seconds. */
    void pumpUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            Runnable next = queue.poll();
            if (next != null) next.run();
            else if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for condition");
            else Thread.sleep(1);
        }
    }

    /** Runs everything currently posted. */
    void drain() {
        Runnable next;
        while ((next = queue.poll()) != null) next.run();
    }
}
