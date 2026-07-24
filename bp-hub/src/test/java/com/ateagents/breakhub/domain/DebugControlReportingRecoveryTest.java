package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;

class DebugControlReportingRecoveryTest {

    @Test
    void successfulRecoveryKeepsTheSameDebuggingIntentSessionAndControl() {
        Harness harness = harness(CompletableFuture.completedFuture(healthy("lease-recovered")));
        ControlIdentity actor = new ControlIdentity("web", "recovering-controller");
        try {
            harness.control.start(actor, () -> "session-recovery");

            harness.scheduler.advanceBy(Duration.ofSeconds(10));

            assertThat(harness.reportingLease.status()).isEqualTo("healthy");
            assertThat(harness.control.activeDebuggingSession())
                    .map(DebugControlService.ActiveDebuggingSession::sessionId)
                    .contains("session-recovery");
            assertThat(harness.control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", true)
                    .containsEntry("owned_by_requester", true);
            verifyNoInteractions(harness.pauses);
        } finally {
            harness.close();
        }
    }

    @Test
    void controlReleaseFencesThePendingRecoveryAcknowledgement() {
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        Harness harness = harness(recovery);
        ControlIdentity actor = new ControlIdentity("web", "released-recovery");
        try {
            harness.control.start(actor, () -> "session-released-recovery");
            harness.scheduler.advanceBy(Duration.ofSeconds(10));

            assertThat(harness.control.release(actor)).containsEntry("result", "released");
            recovery.complete(healthy("lease-orphaned-after-release"));

            assertThat(harness.control.activeDebuggingSession()).isEmpty();
            assertThat(harness.control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", false);
            assertThat(harness.remote.stoppedLeaseIds)
                    .contains("lease-old", "lease-orphaned-after-release");
            verify(harness.pauses, times(1)).safeRelease(
                    "session-released-recovery",
                    "control_released");
        } finally {
            harness.close();
        }
    }

    @Test
    void reportingExpiryDefersRemoteStopsUntilPauseCleanupCompletes() throws Exception {
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        Harness harness = harness(recovery);
        ControlIdentity actor = new ControlIdentity("web", "expiry-recovery-order");
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupStarted.countDown();
            await(allowCleanup);
            harness.remote.terminationOrder.add("local_cleanup");
            return 0;
        }).when(harness.pauses).safeRelease(
                "session-expiry-recovery",
                "reporting_lease_expired");
        CompletableFuture<Void> expiring = null;
        try {
            harness.control.start(actor, () -> "session-expiry-recovery");
            harness.scheduler.advanceBy(Duration.ofSeconds(10));
            expiring = CompletableFuture.runAsync(
                    () -> harness.scheduler.advanceBy(Duration.ofSeconds(20)));
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();

            recovery.complete(healthy("lease-recovered-at-expiry"));

            assertThat(harness.remote.stoppedLeaseIds).isEmpty();
            allowCleanup.countDown();
            expiring.get(1, TimeUnit.SECONDS);
            assertThat(harness.remote.terminationOrder.get(0)).isEqualTo("local_cleanup");
            assertThat(harness.remote.stoppedLeaseIds)
                    .containsExactlyInAnyOrder("lease-old", "lease-recovered-at-expiry");
            verify(harness.pauses, times(1)).safeRelease(
                    "session-expiry-recovery",
                    "reporting_lease_expired");
        } finally {
            allowCleanup.countDown();
            if (expiring != null) {
                expiring.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            }
            harness.close();
        }
    }

    @Test
    void coordinatorCloseDefersLateRecoveryStopUntilPauseCleanupCompletes() throws Exception {
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        Harness harness = harness(recovery);
        ControlIdentity actor = new ControlIdentity("web", "coordinator-close-order");
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupStarted.countDown();
            await(allowCleanup);
            harness.remote.terminationOrder.add("local_cleanup");
            return 0;
        }).when(harness.pauses).safeRelease(
                "session-coordinator-close",
                "reporting_lease_expired");
        CompletableFuture<Void> closing = null;
        try {
            harness.control.start(actor, () -> "session-coordinator-close");
            harness.scheduler.advanceBy(Duration.ofSeconds(10));
            closing = CompletableFuture.runAsync(harness.reportingLease::close);
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();

            recovery.complete(healthy("lease-recovered-during-close"));

            assertThat(harness.remote.stoppedLeaseIds).isEmpty();
            allowCleanup.countDown();
            closing.get(1, TimeUnit.SECONDS);
            assertThat(harness.remote.terminationOrder.get(0)).isEqualTo("local_cleanup");
            assertThat(harness.remote.stoppedLeaseIds)
                    .containsExactlyInAnyOrder("lease-old", "lease-recovered-during-close");
        } finally {
            allowCleanup.countDown();
            if (closing != null) {
                closing.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            }
            harness.control.close();
            harness.reportingLease.close();
        }
    }

    @Test
    void productCloseKeepsRecoveryRemoteStopBehindLocalPauseCleanup() throws Exception {
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        Harness harness = harness(recovery);
        ControlIdentity actor = new ControlIdentity("web", "product-close-order");
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupStarted.countDown();
            await(allowCleanup);
            harness.remote.terminationOrder.add("local_cleanup");
            return 0;
        }).when(harness.pauses).safeRelease(
                "session-product-close",
                "product_shutdown");
        CompletableFuture<Void> closing = null;
        try {
            harness.control.start(actor, () -> "session-product-close");
            harness.scheduler.advanceBy(Duration.ofSeconds(10));
            closing = CompletableFuture.runAsync(harness.control::close);
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();

            recovery.complete(healthy("lease-recovered-during-product-close"));

            assertThat(harness.reportingLease.status()).isEqualTo("healthy");
            assertThat(harness.remote.stoppedLeaseIds).isEmpty();
            allowCleanup.countDown();
            closing.get(1, TimeUnit.SECONDS);
            assertThat(harness.remote.terminationOrder.get(0)).isEqualTo("local_cleanup");
            assertThat(harness.remote.stoppedLeaseIds)
                    .containsExactly("lease-recovered-during-product-close");
        } finally {
            allowCleanup.countDown();
            if (closing != null) {
                closing.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            }
            harness.reportingLease.close();
        }
    }

    private static Harness harness(
            CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery) {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(CompletableFuture.completedFuture(healthy("lease-old")));
        remote.creates.add(recovery);
        remote.renewals.add(CompletableFuture.failedFuture(new ProductException(
                HttpStatus.BAD_GATEWAY,
                "REPORTING_LEASE_NOT_FOUND",
                "old Demo process disappeared")));
        ReportingLeaseCoordinator reportingLease = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                scheduler.monotonicClock(),
                scheduler.clock());
        ProductProperties properties = mock(ProductProperties.class);
        when(properties.controlLease()).thenReturn(
                new ProductProperties.ControlLease(Duration.ofHours(1)));
        PauseService pauses = mock(PauseService.class);
        return new Harness(
                new DebugControlService(properties, reportingLease, pauses),
                reportingLease,
                scheduler,
                remote,
                pauses);
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(String leaseId) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(30),
                "healthy",
                null);
    }

    private record Harness(
            DebugControlService control,
            ReportingLeaseCoordinator reportingLease,
            ControllableScheduler scheduler,
            RecoveryRemote remote,
            PauseService pauses) {

        private void close() {
            control.close();
            reportingLease.close();
        }
    }

    private static final class RecoveryRemote implements ReportingLeaseRemote {

        private final LinkedBlockingQueue<CompletableFuture<LeaseAcknowledgement>> creates =
                new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<CompletableFuture<LeaseAcknowledgement>> renewals =
                new LinkedBlockingQueue<>();
        private final List<String> stoppedLeaseIds = new CopyOnWriteArrayList<>();
        private final List<String> terminationOrder = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            CompletableFuture<LeaseAcknowledgement> response = creates.poll();
            return response == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Unexpected create"))
                    : response;
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            CompletableFuture<LeaseAcknowledgement> response = renewals.poll();
            return response == null
                    ? CompletableFuture.completedFuture(healthy(leaseId))
                    : response;
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stoppedLeaseIds.add(leaseId);
            terminationOrder.add("remote_stop:" + leaseId);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test cleanup gate");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static final class NonCancellableFuture<T> extends CompletableFuture<T> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
