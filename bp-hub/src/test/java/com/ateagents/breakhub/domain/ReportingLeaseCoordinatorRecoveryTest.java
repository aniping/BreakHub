package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;

class ReportingLeaseCoordinatorRecoveryTest {

    @Test
    void leaseNotFoundRecreatesWithinTheOriginalWindowAndFencesTheOldExpiry() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(completed("lease-recovered"));
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        remote.renewals.add(completed("lease-recovered"));
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);
        scheduler.advanceBy(Duration.ofSeconds(10));

        assertThat(remote.createCount).hasValue(2);
        assertThat(remote.renewedLeaseIds).containsExactly("lease-old");
        assertThat(coordinator.snapshot())
                .containsEntry("status", "healthy")
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:10Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:40Z");
        assertThat(localExpiryCount).hasValue(0);

        scheduler.runScheduledCommandIgnoringCancellation(0);
        scheduler.runScheduledCommandIgnoringCancellation(1);
        assertThat(remote.renewedLeaseIds).containsExactly("lease-old");
        scheduler.advanceBy(Duration.ofSeconds(10));

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(remote.renewedLeaseIds)
                .containsExactly("lease-old", "lease-recovered");
        assertThat(coordinator.snapshot())
                .containsEntry("server_deadline_at", "2026-01-01T00:00:50Z");
        assertThat(localExpiryCount).hasValue(0);
    }

    @Test
    void recoveryAcknowledgementAtTheOriginalDeadlineExpiresAndStopsTheNewLease() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(recovery);
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);
        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.createCount).hasValue(2);
        scheduler.moveClockWithoutRunning(Duration.ofSeconds(20));
        remote.failingStopLeaseIds.add("lease-too-late");
        recovery.complete(healthy("lease-too-late"));

        assertThat(coordinator.status()).isEqualTo("expired");
        assertThat(localExpiryCount).hasValue(1);
        assertThat(remote.stoppedLeaseIds)
                .containsExactlyInAnyOrder("lease-old", "lease-too-late");
        assertThat(remote.stoppedLeaseIds).doesNotHaveDuplicates();
    }

    @Test
    void recoveryAcknowledgementImmediatelyBeforeTheOriginalDeadlineIsAccepted() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(recovery);
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);
        scheduler.advanceBy(Duration.ofSeconds(10));
        scheduler.moveClockWithoutRunning(Duration.ofSeconds(20).minusNanos(1));
        recovery.complete(healthy("lease-before-deadline"));

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(localExpiryCount).hasValue(0);
        assertThat(remote.stoppedLeaseIds).isEmpty();
    }

    @Test
    void leaseNotFoundObservedAtTheDeadlineDoesNotStartRecovery() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renewal =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.renewals.add(renewal);
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);
        scheduler.advanceBy(Duration.ofSeconds(10));
        scheduler.moveClockWithoutRunning(Duration.ofSeconds(20));
        renewal.completeExceptionally(new ProductException(
                HttpStatus.BAD_GATEWAY,
                "REPORTING_LEASE_NOT_FOUND",
                "old process lease disappeared"));

        assertThat(coordinator.status()).isEqualTo("expired");
        assertThat(localExpiryCount).hasValue(1);
        assertThat(remote.createCount).hasValue(1);
        assertThat(remote.stoppedLeaseIds).containsExactly("lease-old");
    }

    @Test
    void aNewProductGenerationFencesALateRecoveryAcknowledgement() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> oldRecovery =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(oldRecovery);
        remote.creates.add(completed("lease-current"));
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "first");
        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.createCount).hasValue(2);
        coordinator.stopBestEffort().join();
        coordinator.start(() -> "second");

        oldRecovery.complete(healthy("lease-orphaned-recovery"));
        scheduler.runScheduledCommandIgnoringCancellation(0);

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(remote.createCount).hasValue(3);
        assertThat(remote.stoppedLeaseIds)
                .contains("lease-old", "lease-orphaned-recovery")
                .doesNotContain("lease-current");
    }

    @Test
    void lostDebuggingIntentRejectsAndStopsTheRecoveredLease() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(recovery);
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        AtomicBoolean debuggingIntent = new AtomicBoolean(true);
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(
                () -> "started",
                () -> {
                },
                debuggingIntent::get);
        scheduler.advanceBy(Duration.ofSeconds(10));
        debuggingIntent.set(false);
        recovery.complete(healthy("lease-without-intent"));

        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(remote.stoppedLeaseIds)
                .containsExactlyInAnyOrder("lease-old", "lease-without-intent")
                .doesNotHaveDuplicates();
    }

    @Test
    void lostDebuggingIntentPreventsARecoveryCreateFromStarting() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(completed("lease-old"));
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        AtomicBoolean debuggingIntent = new AtomicBoolean(true);
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(
                () -> "started",
                () -> {
                },
                debuggingIntent::get);
        debuggingIntent.set(false);
        scheduler.advanceBy(Duration.ofSeconds(10));

        assertThat(remote.createCount).hasValue(1);
        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(remote.stoppedLeaseIds).containsExactly("lease-old");
    }

    @Test
    void lateRenewalFromTheOldLeaseCannotUpdateTheNewGeneration() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> oldRenewal =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(completed("lease-current"));
        remote.renewals.add(oldRenewal);
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "first");
        scheduler.advanceBy(Duration.ofSeconds(10));
        coordinator.stopBestEffort().join();
        coordinator.start(() -> "second");
        oldRenewal.complete(healthy("lease-old"));

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(coordinator.snapshot())
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:10Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:40Z");
        assertThat(remote.stoppedLeaseIds)
                .contains("lease-old")
                .doesNotContain("lease-current");
    }

    @Test
    void lateStopCompletionFromTheOldLeaseCannotClearTheNewGeneration() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        CompletableFuture<Void> oldStop = new CompletableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(completed("lease-current"));
        remote.stops.add(oldStop);
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "first");
        CompletableFuture<Void> stopping = coordinator.stopBestEffort();
        coordinator.start(() -> "second");
        oldStop.complete(null);
        stopping.join();

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(coordinator.hasActiveLease()).isTrue();
        assertThat(remote.stoppedLeaseIds)
                .containsExactly("lease-old")
                .doesNotContain("lease-current");
    }

    @Test
    void aFailedRecoveryCreateRetriesAfterTheFixedDelayWithoutOverlap() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(failed("REPORTING_LEASE_UNAVAILABLE"));
        remote.creates.add(completed("lease-recovered"));
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started");
        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.createCount).hasValue(2);
        assertThat(coordinator.status()).isEqualTo("degraded");

        scheduler.advanceBy(Duration.ofMillis(9_999));
        assertThat(remote.createCount).hasValue(2);
        scheduler.advanceBy(Duration.ofMillis(1));

        assertThat(remote.createCount).hasValue(3);
        assertThat(coordinator.status()).isEqualTo("healthy");
    }

    @Test
    void aCancelledRecoveryRetryTaskCannotCreateForTheNewGeneration() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(failed("REPORTING_LEASE_UNAVAILABLE"));
        remote.creates.add(completed("lease-current"));
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "first");
        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.createCount).hasValue(2);
        coordinator.stopBestEffort().join();
        coordinator.start(() -> "second");

        scheduler.runScheduledCommandIgnoringCancellation(2);

        assertThat(remote.createCount).hasValue(3);
        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(remote.stoppedLeaseIds)
                .contains("lease-old")
                .doesNotContain("lease-current");
    }

    @Test
    void genericRenewalFailureNeverCreatesARecoveryLease() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(completed("lease-old"));
        remote.renewals.add(failed("REPORTING_LEASE_TIMEOUT"));
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        coordinator.start(() -> "started");
        scheduler.advanceBy(Duration.ofSeconds(10));

        assertThat(remote.createCount).hasValue(1);
        assertThat(coordinator.snapshot())
                .containsEntry("status", "degraded")
                .containsEntry("last_error", "REPORTING_LEASE_TIMEOUT");
    }

    @Test
    void initialCreateResponseLossDoesNotGuessOrTakeOverTheUnknownLease() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        remote.creates.add(failed("REPORTING_LEASE_TIMEOUT"));
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);

        assertThatThrownBy(() -> coordinator.start(() -> "must-not-commit"))
                .isInstanceOf(ProductException.class)
                .satisfies(error -> assertThat(((ProductException) error).code())
                        .isEqualTo("REPORTING_LEASE_TIMEOUT"));

        scheduler.advanceBy(Duration.ofMinutes(1));
        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(remote.createCount).hasValue(1);
        assertThat(remote.renewedLeaseIds).isEmpty();
        assertThat(remote.stoppedLeaseIds).isEmpty();
    }

    @Test
    void closeFencesANonCancellableRecoveryAcknowledgement() {
        ControllableScheduler scheduler = new ControllableScheduler();
        RecoveryRemote remote = new RecoveryRemote();
        NonCancellableFuture<ReportingLeaseRemote.LeaseAcknowledgement> recovery =
                new NonCancellableFuture<>();
        remote.creates.add(completed("lease-old"));
        remote.creates.add(recovery);
        remote.renewals.add(failed("REPORTING_LEASE_NOT_FOUND"));
        AtomicInteger localShutdownCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = coordinator(remote, scheduler);
        coordinator.start(() -> "started", localShutdownCount::incrementAndGet);
        scheduler.advanceBy(Duration.ofSeconds(10));
        assertThat(remote.createCount).hasValue(2);

        coordinator.close();
        recovery.complete(healthy("lease-after-close"));

        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(localShutdownCount).hasValue(1);
        assertThat(remote.stoppedLeaseIds)
                .contains("lease-old", "lease-after-close")
                .doesNotHaveDuplicates();
        assertThat(scheduler.isTerminated()).isTrue();
    }

    private static ReportingLeaseCoordinator coordinator(
            RecoveryRemote remote,
            ControllableScheduler scheduler) {
        return new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                scheduler.monotonicClock(),
                scheduler.clock());
    }

    private static CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> completed(
            String leaseId) {
        return CompletableFuture.completedFuture(healthy(leaseId));
    }

    private static <T> CompletableFuture<T> failed(String code) {
        return CompletableFuture.failedFuture(new ProductException(
                HttpStatus.BAD_GATEWAY,
                code,
                "sanitized test failure"));
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(String leaseId) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(30),
                "healthy",
                null);
    }

    private static final class RecoveryRemote implements ReportingLeaseRemote {

        private final LinkedBlockingQueue<CompletableFuture<LeaseAcknowledgement>> creates =
                new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<CompletableFuture<LeaseAcknowledgement>> renewals =
                new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<CompletableFuture<Void>> stops =
                new LinkedBlockingQueue<>();
        private final List<String> renewedLeaseIds = new CopyOnWriteArrayList<>();
        private final List<String> stoppedLeaseIds = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArraySet<String> failingStopLeaseIds =
                new CopyOnWriteArraySet<>();
        private final AtomicInteger createCount = new AtomicInteger();

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            createCount.incrementAndGet();
            CompletableFuture<LeaseAcknowledgement> response = creates.poll();
            if (response == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Unexpected recovery create"));
            }
            return response;
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            renewedLeaseIds.add(leaseId);
            CompletableFuture<LeaseAcknowledgement> response = renewals.poll();
            return response == null ? completed(leaseId) : response;
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stoppedLeaseIds.add(leaseId);
            if (failingStopLeaseIds.contains(leaseId)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulated best-effort stop failure"));
            }
            CompletableFuture<Void> response = stops.poll();
            return response == null ? CompletableFuture.completedFuture(null) : response;
        }
    }

    private static final class NonCancellableFuture<T> extends CompletableFuture<T> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
