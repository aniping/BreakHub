package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;

class DebugControlTerminationRaceTest {

    @Test
    void competingTerminationPathsConvergeOnceWithoutLateRenewalRevival() throws Exception {
        ControllableScheduler scheduler = new ControllableScheduler();
        AtomicLong monotonicNanos = new AtomicLong();
        RacingRemote remote = new RacingRemote();
        ReportingLeaseCoordinator reportingLease = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                monotonicNanos::get,
                scheduler.clock());
        ProductProperties properties = mock(ProductProperties.class);
        when(properties.controlLease()).thenReturn(
                new ProductProperties.ControlLease(Duration.ofHours(1)));
        PauseService pauses = mock(PauseService.class);
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "termination-race");
        control.start(actor, () -> "session-race");

        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.renewCount).hasValue(1);
        monotonicNanos.set(Duration.ofSeconds(30).toNanos());

        AtomicInteger competitorNumber = new AtomicInteger();
        ExecutorService competitors = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(
                    task,
                    "breakhub-termination-race-" + competitorNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        CyclicBarrier startTogether = new CyclicBarrier(4);
        CompletableFuture<Void> expiry = compete(
                startTogether,
                () -> scheduler.runScheduledCommandIgnoringCancellation(0),
                competitors);
        CompletableFuture<Void> explicitStop = competeAllowingShutdown(
                startTogether,
                () -> control.stop(actor, "session-race"),
                competitors);
        CompletableFuture<Void> controlRelease = competeAllowingShutdown(
                startTogether,
                () -> control.release(actor),
                competitors);
        CompletableFuture<Void> applicationClose = compete(startTogether, control::close, competitors);

        CompletableFuture.allOf(expiry, explicitStop, controlRelease, applicationClose)
                .get(2, TimeUnit.SECONDS);
        competitors.shutdown();
        assertThat(competitors.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        verify(pauses, times(1)).safeRelease(eq("session-race"), anyString());
        assertThat(remote.stopCount).hasValue(1);
        assertThat(control.activeDebuggingSession()).isEmpty();
        assertThat(control.debuggingSnapshot("session-race"))
                .containsEntry("status", "idle");
        assertThat(control.controlSnapshot(Optional.of(actor)))
                .containsEntry("held", false);

        String reportingStatusAfterClose = reportingLease.status();
        assertThat(remote.pendingRenewal.complete(healthy("lease-race"))).isTrue();

        assertThat(reportingLease.status()).isEqualTo(reportingStatusAfterClose);
        assertThat(remote.stopCount).hasValue(1);
        verify(pauses, times(1)).safeRelease(eq("session-race"), anyString());
        assertThat(control.activeDebuggingSession()).isEmpty();
        assertThat(control.controlSnapshot(Optional.of(actor)))
                .containsEntry("held", false);
        assertThatThrownBy(() -> control.performWrite(actor, () -> {
            throw new AssertionError("the write must not run after close");
        }))
                .isInstanceOf(ProductException.class)
                .satisfies(error -> {
                    ProductException productError = (ProductException) error;
                    assertThat(productError.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(productError.code()).isEqualTo("PRODUCT_SHUTTING_DOWN");
                });

        reportingLease.close();
    }

    private static CompletableFuture<Void> compete(
            CyclicBarrier barrier,
            Runnable operation,
            Executor executor) {
        return CompletableFuture.runAsync(() -> {
            await(barrier);
            operation.run();
        }, executor);
    }

    private static CompletableFuture<Void> competeAllowingShutdown(
            CyclicBarrier barrier,
            Runnable operation,
            Executor executor) {
        return compete(barrier, () -> {
            try {
                operation.run();
            } catch (ProductException error) {
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.code()).isEqualTo("PRODUCT_SHUTTING_DOWN");
            }
        }, executor);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(1, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("Termination competitors did not become ready", error);
        }
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(String leaseId) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(30),
                "healthy",
                null);
    }

    private static final class RacingRemote implements ReportingLeaseRemote {

        private final NonCancellableRenewal pendingRenewal = new NonCancellableRenewal();
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            return CompletableFuture.completedFuture(healthy("lease-race"));
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            renewCount.incrementAndGet();
            return pendingRenewal;
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class NonCancellableRenewal
            extends CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
