package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;

class ReportingLeaseCoordinatorTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutDownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void localStartWaitsForTheRemoteLeaseAcknowledgement() throws Exception {
        StubRemote remote = new StubRemote();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote, Duration.ofSeconds(1), scheduler);
        AtomicBoolean committed = new AtomicBoolean();
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        CompletableFuture<String> start = CompletableFuture.supplyAsync(() -> coordinator.start(() -> {
            committed.set(true);
            commitEntered.countDown();
            try {
                allowCommit.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return "started";
        }));

        assertThat(committed).isFalse();
        assertThat(coordinator.status()).isEqualTo("idle");

        remote.create.complete(healthy("lease-gated"));
        assertThat(commitEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(committed).isTrue();
        assertThat(coordinator.status()).isEqualTo("idle");
        allowCommit.countDown();

        assertThat(start.get(1, TimeUnit.SECONDS)).isEqualTo("started");
        assertThat(coordinator.status()).isEqualTo("healthy");
        coordinator.stopBestEffort().get(1, TimeUnit.SECONDS);
    }

    @Test
    void renewalsUseFixedDelayAndNeverOverlap() throws Exception {
        StubRemote remote = new StubRemote();
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> firstRenewal =
                new CompletableFuture<>();
        remote.renewals.add(firstRenewal);
        remote.renewals.add(CompletableFuture.completedFuture(healthy("lease-renewed")));
        remote.create.complete(healthy("lease-renewed"));
        Duration renewDelay = Duration.ofMillis(80);
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote, renewDelay, scheduler);

        coordinator.start(() -> "started");

        await(() -> remote.renewCount.get() == 1);
        CountDownLatch overlapWindowElapsed = new CountDownLatch(1);
        scheduler.schedule(
                overlapWindowElapsed::countDown,
                renewDelay.multipliedBy(2).toNanos(),
                TimeUnit.NANOSECONDS);
        assertThat(overlapWindowElapsed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(remote.renewCount).hasValue(1);

        long firstRenewalCompletedAt = System.nanoTime();
        firstRenewal.complete(healthy("lease-renewed"));
        await(() -> remote.renewCount.get() == 2);
        assertThat(remote.renewalStartedAtNanos.get(1) - firstRenewalCompletedAt)
                .isGreaterThanOrEqualTo(renewDelay.toNanos());

        coordinator.stopBestEffort().get(1, TimeUnit.SECONDS);
    }

    @Test
    void failedLocalCommitStopsTheJustCreatedRemoteLease() {
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-rollback"));
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote, Duration.ofSeconds(1), scheduler);
        IllegalStateException failure = new IllegalStateException("local commit failed");

        assertThatThrownBy(() -> coordinator.start(() -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(remote.stoppedLeaseId).hasValue("lease-rollback");
    }

    @Test
    void lateRenewalCannotRescheduleAfterStop() throws Exception {
        StubRemote remote = new StubRemote();
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> lateRenewal = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        remote.renewals.add(lateRenewal);
        remote.create.complete(healthy("lease-fenced"));
        Duration renewDelay = Duration.ofMillis(60);
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote, renewDelay, scheduler);
        coordinator.start(() -> "started");
        await(() -> remote.renewCount.get() == 1);

        coordinator.stopBestEffort().get(1, TimeUnit.SECONDS);
        lateRenewal.complete(healthy("lease-fenced"));
        Thread.sleep(renewDelay.multipliedBy(2).toMillis());

        assertThat(coordinator.status()).isEqualTo("idle");
        assertThat(remote.renewCount).hasValue(1);
        assertThat(remote.stoppedLeaseId).hasValue("lease-fenced");
    }

    @Test
    void failedRenewalDegradesThenExpiresWithoutReleasingTheControlCallbackEarly() throws Exception {
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-expiring", Duration.ofMillis(140)));
        remote.renewals.add(CompletableFuture.failedFuture(
                new IllegalStateException("must-not-leak")));
        remote.renewals.add(new CompletableFuture<>());
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote, Duration.ofMillis(40), Duration.ofMillis(140), scheduler);

        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);

        await(() -> "degraded".equals(coordinator.snapshot().get("status")));
        assertThat(coordinator.snapshot())
                .containsKeys("last_confirmed_at", "server_deadline_at", "last_error")
                .doesNotContainKey("lease_id");
        assertThat(coordinator.snapshot().get("last_error").toString())
                .doesNotContain("must-not-leak");
        assertThat(localExpiryCount).hasValue(0);

        await(() -> "expired".equals(coordinator.snapshot().get("status")));
        assertThat(localExpiryCount).hasValue(1);
    }

    @Test
    void renewalTimeoutDegradesAtFifteenSecondsAndExpiresAtThirtySeconds() {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-timeline"));
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> firstRenewal =
                new CompletableFuture<>();
        remote.renewals.add(firstRenewal);
        remote.renewals.add(new CompletableFuture<>());
        CopyOnWriteArrayList<String> expiryOrder = new CopyOnWriteArrayList<>();
        remote.onStop = () -> expiryOrder.add("remote_stop");
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());

        coordinator.start(() -> "started", () -> expiryOrder.add("local_expiry"));
        assertThat(coordinator.snapshot())
                .containsEntry("status", "healthy")
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:00Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:30Z");

        controllable.advanceBy(Duration.ofMillis(9_999));
        assertThat(remote.renewCount).hasValue(0);
        controllable.advanceBy(Duration.ofMillis(1));
        assertThat(remote.renewCount).hasValue(1);
        controllable.advanceBy(Duration.ofSeconds(5));
        assertThat(coordinator.status()).isEqualTo("healthy");

        firstRenewal.completeExceptionally(new ProductException(
                HttpStatus.GATEWAY_TIMEOUT,
                "REPORTING_LEASE_TIMEOUT",
                "sensitive details must not be exposed"));
        assertThat(coordinator.snapshot())
                .containsEntry("status", "degraded")
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:00Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:30Z")
                .containsEntry("last_error", "REPORTING_LEASE_TIMEOUT");

        controllable.advanceBy(Duration.ofMillis(14_999));
        assertThat(expiryOrder).isEmpty();
        assertThat(coordinator.status()).isEqualTo("degraded");
        controllable.advanceBy(Duration.ofMillis(1));

        assertThat(coordinator.status()).isEqualTo("expired");
        assertThat(expiryOrder).containsExactly("local_expiry", "remote_stop");
    }

    @Test
    void successfulRenewalRebasesDeadlineAndFencesTheCancelledExpiryTask() {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-rebased"));
        remote.renewals.add(CompletableFuture.completedFuture(healthy("lease-rebased")));
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);

        controllable.advanceBy(Duration.ofSeconds(10));
        assertThat(coordinator.snapshot())
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:10Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:40Z");

        controllable.runScheduledCommandIgnoringCancellation(0);

        assertThat(coordinator.status()).isEqualTo("healthy");
        assertThat(localExpiryCount).hasValue(0);
    }

    @Test
    void lateSuccessfulRenewalCannotReviveAnExpiredLease() {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-late"));
        CancellationTrackingFuture<ReportingLeaseRemote.LeaseAcknowledgement> lateRenewal =
                new CancellationTrackingFuture<>(false);
        remote.renewals.add(lateRenewal);
        AtomicInteger localExpiryCount = new AtomicInteger();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        coordinator.start(() -> "started", localExpiryCount::incrementAndGet);

        controllable.advanceBy(Duration.ofSeconds(30));
        assertThat(coordinator.status()).isEqualTo("expired");
        assertThat(localExpiryCount).hasValue(1);
        assertThat(lateRenewal.cancelCount).hasValue(1);

        lateRenewal.complete(healthy("lease-late"));

        assertThat(coordinator.snapshot())
                .containsEntry("status", "expired")
                .containsEntry("last_confirmed_at", "2026-01-01T00:00:00Z")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:30Z");
        assertThat(remote.renewCount).hasValue(1);
    }

    @Test
    void aDegradedBusinessChannelAcknowledgementStillRefreshesTheLease() {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-channel"));
        remote.renewals.add(CompletableFuture.completedFuture(
                degraded("lease-channel", "before_request_failed")));
        remote.renewals.add(CompletableFuture.completedFuture(healthy("lease-channel")));
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        coordinator.start(() -> "started");

        controllable.advanceBy(Duration.ofSeconds(10));
        assertThat(coordinator.snapshot())
                .containsEntry("status", "healthy")
                .containsEntry("channel_status", "degraded")
                .containsEntry("last_error", "before_request_failed")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:40Z");

        controllable.advanceBy(Duration.ofSeconds(10));
        assertThat(coordinator.snapshot())
                .containsEntry("status", "healthy")
                .containsEntry("channel_status", "healthy")
                .containsEntry("server_deadline_at", "2026-01-01T00:00:50Z")
                .doesNotContainKey("last_error");
    }

    @Test
    void expiryAndExplicitStopShareOneRemoteStopThatCloseCanCancel() {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-competing-stop"));
        remote.renewals.add(new CompletableFuture<>());
        CancellationTrackingFuture<Void> remoteStop = new CancellationTrackingFuture<>(true);
        remote.stopResponse = remoteStop;
        AtomicInteger localExpiryCount = new AtomicInteger();
        AtomicReference<CompletableFuture<Void>> stopSeenByExpiry = new AtomicReference<>();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());

        coordinator.start(() -> "started", () -> {
            localExpiryCount.incrementAndGet();
            stopSeenByExpiry.set(coordinator.stopBestEffort());
        });
        controllable.advanceBy(Duration.ofSeconds(30));
        CompletableFuture<Void> stopSeenAfterExpiry = coordinator.stopBestEffort();

        assertThat(localExpiryCount).hasValue(1);
        assertThat(remote.stopCount).hasValue(1);
        assertThat(stopSeenAfterExpiry).isSameAs(stopSeenByExpiry.get());
        assertThat(remoteStop.cancelCount).hasValue(0);

        coordinator.close();

        assertThat(remote.stopCount).hasValue(1);
        assertThat(remoteStop.cancelCount).hasValue(1);
        assertThat(stopSeenAfterExpiry).isCompletedWithValue(null);
        assertThat(controllable.isTerminated()).isTrue();
    }

    @Test
    void closeCancelsTheReservedExpiryStopBeforeLocalCleanupCanStartIt() throws Exception {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-close-before-stop"));
        remote.renewals.add(new CompletableFuture<>());
        CountDownLatch localExpiryStarted = new CountDownLatch(1);
        CountDownLatch allowLocalExpiry = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> stopCompletion = new AtomicReference<>();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        coordinator.start(() -> "started", () -> {
            stopCompletion.set(coordinator.stopBestEffort());
            localExpiryStarted.countDown();
            await(allowLocalExpiry);
        });

        CompletableFuture<Void> expiring = CompletableFuture.runAsync(
                () -> controllable.advanceBy(Duration.ofSeconds(30)));
        try {
            assertThat(localExpiryStarted.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> closing = CompletableFuture.runAsync(coordinator::close);
            closing.get(1, TimeUnit.SECONDS);

            assertThat(stopCompletion.get()).isCompletedWithValue(null);
            assertThat(controllable.isTerminated()).isTrue();
        } finally {
            allowLocalExpiry.countDown();
            expiring.get(1, TimeUnit.SECONDS);
        }

        assertThat(remote.stopCount).hasValue(0);
    }

    @Test
    void closeWaitsUntilAnAlreadyStartingRemoteStopHasObtainedItsSource() throws Exception {
        ControllableScheduler controllable = new ControllableScheduler();
        StubRemote remote = new StubRemote();
        remote.create.complete(healthy("lease-stop-starting"));
        remote.renewals.add(new CompletableFuture<>());
        CountDownLatch remoteStopStarted = new CountDownLatch(1);
        CountDownLatch allowRemoteStopToReturn = new CountDownLatch(1);
        CancellationTrackingFuture<Void> remoteStop = new CancellationTrackingFuture<>(true);
        remote.stopResponse = remoteStop;
        remote.onStop = () -> {
            remoteStopStarted.countDown();
            await(allowRemoteStopToReturn);
        };
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        coordinator.start(() -> "started");

        CompletableFuture<Void> expiring = CompletableFuture.runAsync(
                () -> controllable.advanceBy(Duration.ofSeconds(30)));
        assertThat(remoteStopStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CountDownLatch closeStarted = new CountDownLatch(1);
        CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
            closeStarted.countDown();
            coordinator.close();
        });
        try {
            assertThat(closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            await(controllable::isShutdown);
            assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            allowRemoteStopToReturn.countDown();
            expiring.get(1, TimeUnit.SECONDS);
            closing.get(1, TimeUnit.SECONDS);
        }

        assertThat(remote.stopCount).hasValue(1);
        assertThat(remoteStop.cancelCount).hasValue(1);
        assertThat(controllable.isTerminated()).isTrue();
    }

    @Test
    void closeFencesANonCancellableCreateAcknowledgement() throws Exception {
        ControllableScheduler controllable = new ControllableScheduler();
        CloseRaceRemote remote = new CloseRaceRemote();
        ReportingLeaseCoordinator coordinator = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                controllable,
                controllable.monotonicClock(),
                controllable.clock());
        AtomicBoolean committed = new AtomicBoolean();
        CompletableFuture<String> starting = CompletableFuture.supplyAsync(() ->
                coordinator.start(() -> {
                    committed.set(true);
                    return "started";
                }));
        assertThat(remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();

        coordinator.close();
        remote.create.complete(healthy("lease-after-close"));

        assertThatThrownBy(() -> starting.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ProductException.class);
        assertThat(committed).isFalse();
        assertThat(remote.stopCount).hasValue(1);
        assertThat(controllable.isTerminated()).isTrue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while awaiting the test latch");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static final class StubRemote implements ReportingLeaseRemote {
        private final CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create =
                new CompletableFuture<>();
        private final LinkedBlockingQueue<CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement>> renewals =
                new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<Long> renewalStartedAtNanos = new CopyOnWriteArrayList<>();
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicReference<String> stoppedLeaseId = new AtomicReference<>();
        private CompletableFuture<Void> stopResponse = CompletableFuture.completedFuture(null);
        private Runnable onStop = () -> {
        };

        @Override
        public CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create() {
            return create;
        }

        @Override
        public CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renew(String leaseId) {
            renewalStartedAtNanos.add(System.nanoTime());
            renewCount.incrementAndGet();
            CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> response = renewals.poll();
            return response == null
                    ? CompletableFuture.completedFuture(healthy(leaseId))
                    : response;
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount.incrementAndGet();
            stoppedLeaseId.set(leaseId);
            onStop.run();
            return stopResponse;
        }
    }

    private static final class CloseRaceRemote implements ReportingLeaseRemote {

        private final CountDownLatch createStarted = new CountDownLatch(1);
        private final CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create =
                new CompletableFuture<>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }
                };
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create() {
            createStarted.countDown();
            return create;
        }

        @Override
        public CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renew(String leaseId) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static final class CancellationTrackingFuture<T> extends CompletableFuture<T> {

        private final AtomicInteger cancelCount = new AtomicInteger();
        private final boolean cancellable;

        private CancellationTrackingFuture(boolean cancellable) {
            this.cancellable = cancellable;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCount.incrementAndGet();
            return cancellable && super.cancel(mayInterruptIfRunning);
        }
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(String leaseId) {
        return healthy(leaseId, Duration.ofSeconds(30));
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(
            String leaseId,
            Duration timeout) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                timeout,
                "healthy",
                null);
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement degraded(
            String leaseId,
            String lastError) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(30),
                "degraded",
                lastError);
    }
}
