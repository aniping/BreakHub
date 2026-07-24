package com.ateagents.breakhub.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

final class ControllableScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final TestClock clock = new TestClock();
    private final PriorityQueue<ScheduledTask<?>> pending = new PriorityQueue<>();
    private final List<ScheduledTask<?>> history = new ArrayList<>();
    private boolean shutdown;
    private long sequence;

    Clock clock() {
        return clock;
    }

    LongSupplier monotonicClock() {
        return clock;
    }

    void advanceBy(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot move test time backwards");
        }
        long targetNanos = clock.getAsLong() + duration.toNanos();
        while (!pending.isEmpty() && pending.peek().dueNanos <= targetNanos) {
            ScheduledTask<?> task = pending.remove();
            clock.moveTo(task.dueNanos);
            task.run();
        }
        clock.moveTo(targetNanos);
    }

    void moveClockWithoutRunning(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cannot move test time backwards");
        }
        clock.moveTo(clock.getAsLong() + duration.toNanos());
    }

    void runScheduledCommandIgnoringCancellation(int historyIndex) {
        history.get(historyIndex).runCommandIgnoringCancellation();
    }

    int scheduledCommandCount() {
        return history.size();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(() -> {
            command.run();
            return null;
        }, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException("scheduler is shut down");
        }
        ScheduledTask<V> task = new ScheduledTask<>(
                callable,
                clock.getAsLong() + unit.toNanos(delay),
                sequence++,
                clock);
        pending.add(task);
        history.add(task);
        return task;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command,
            long initialDelay,
            long period,
            TimeUnit unit) {
        throw new UnsupportedOperationException("Periodic scheduling is not needed by these tests");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command,
            long initialDelay,
            long delay,
            TimeUnit unit) {
        throw new UnsupportedOperationException("Periodic scheduling is not needed by these tests");
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> remaining = new ArrayList<>(pending);
        pending.clear();
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && pending.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    private static final class ScheduledTask<V> implements RunnableScheduledFuture<V> {

        private final Callable<V> command;
        private final long dueNanos;
        private final long sequence;
        private final LongSupplier clock;
        private final CompletableFuture<V> result = new CompletableFuture<>();

        private ScheduledTask(
                Callable<V> command,
                long dueNanos,
                long sequence,
                LongSupplier clock) {
            this.command = command;
            this.dueNanos = dueNanos;
            this.sequence = sequence;
            this.clock = clock;
        }

        @Override
        public void run() {
            if (!isCancelled()) {
                runCommand();
            }
        }

        private void runCommandIgnoringCancellation() {
            try {
                command.call();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private void runCommand() {
            try {
                result.complete(command.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }

        @Override
        public boolean isPeriodic() {
            return false;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueNanos - clock.getAsLong(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            ScheduledTask<?> task = (ScheduledTask<?>) other;
            int dueComparison = Long.compare(dueNanos, task.dueNanos);
            return dueComparison != 0 ? dueComparison : Long.compare(sequence, task.sequence);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return result.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return result.isCancelled();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }

    private static final class TestClock extends Clock implements LongSupplier {

        private static final Instant ORIGIN = Instant.parse("2026-01-01T00:00:00Z");

        private long nanos;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Only UTC is used by these tests");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return ORIGIN.plusNanos(nanos);
        }

        @Override
        public long getAsLong() {
            return nanos;
        }

        private void moveTo(long targetNanos) {
            nanos = targetNanos;
        }
    }
}
