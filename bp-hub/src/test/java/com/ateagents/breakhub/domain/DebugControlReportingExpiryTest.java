package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;

class DebugControlReportingExpiryTest {

    @Test
    void reportingExpiryConvergesOnceWhenPauseCleanupFails() {
        ControllableScheduler scheduler = new ControllableScheduler();
        PendingRenewalRemote remote = new PendingRenewalRemote();
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
        DataAccessResourceFailureException cleanupFailure =
                new DataAccessResourceFailureException("pause cleanup failed");
        when(pauses.safeRelease("session-expired", "reporting_lease_expired"))
                .thenThrow(cleanupFailure);
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "reporting-expiry-failure");
        control.start(actor, () -> "session-expired");

        scheduler.advanceBy(Duration.ofSeconds(30));

        assertThat(remote.stopCount).isEqualTo(1);
        assertThat(control.debuggingSnapshot("session-expired"))
                .containsEntry("status", "idle");
        assertThat(control.activeDebuggingSession()).isEmpty();
        assertThat(control.controlSnapshot(Optional.of(actor)))
                .containsEntry("held", true)
                .containsEntry("owned_by_requester", true);
        control.debuggingSnapshot("session-expired");
        control.activeDebuggingSession();
        verify(pauses).safeRelease("session-expired", "reporting_lease_expired");

        control.close();
        reportingLease.close();
    }

    @Test
    void explicitStopRenewsControlAndStopsReportingWhenPauseCleanupFails() throws Exception {
        ControllableScheduler scheduler = new ControllableScheduler();
        AwaitedStopRemote remote = new AwaitedStopRemote();
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
        IllegalStateException cleanupFailure = new IllegalStateException("pause cleanup failed");
        when(pauses.safeRelease("session-stop-failure", "debug_stopped"))
                .thenThrow(cleanupFailure);
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "explicit-stop-failure");
        control.start(actor, () -> "session-stop-failure");
        Instant originalControlDeadline = Instant.parse((String) control
                .controlSnapshot(Optional.of(actor))
                .get("expires_at"));
        Thread.sleep(5);

        CompletableFuture<Throwable> stopping = CompletableFuture.supplyAsync(
                () -> catchThrowable(() -> control.stop(actor, "session-stop-failure")));
        try {
            await(() -> remote.stopCount == 1);
            assertThat(stopping).isNotDone();
            assertThat(control.debuggingSnapshot("session-stop-failure"))
                    .containsEntry("status", "idle");
            assertThat(control.activeDebuggingSession()).isEmpty();
            assertThat(control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", true)
                    .containsEntry("owned_by_requester", true)
                    .satisfies(snapshot -> assertThat(Instant.parse((String) snapshot.get("expires_at")))
                            .isAfter(originalControlDeadline));
        } finally {
            remote.stop.complete(null);
        }
        Throwable thrown = stopping.get(1, TimeUnit.SECONDS);

        assertThat(thrown).isSameAs(cleanupFailure);
        assertThat(remote.stopCount).isEqualTo(1);
        control.debuggingSnapshot("session-stop-failure");
        control.activeDebuggingSession();
        verify(pauses).safeRelease("session-stop-failure", "debug_stopped");

        control.close();
        reportingLease.close();
    }

    @Test
    void controlLeaseExpiryClearsControlAndStopsReportingWhenPauseCleanupFails() throws Exception {
        ControllableScheduler scheduler = new ControllableScheduler();
        PendingRenewalRemote remote = new PendingRenewalRemote();
        ReportingLeaseCoordinator reportingLease = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                scheduler.monotonicClock(),
                scheduler.clock());
        ProductProperties properties = mock(ProductProperties.class);
        when(properties.controlLease()).thenReturn(
                new ProductProperties.ControlLease(Duration.ofSeconds(1)));
        PauseService pauses = mock(PauseService.class);
        DataAccessResourceFailureException cleanupFailure =
                new DataAccessResourceFailureException("pause cleanup failed");
        CountDownLatch expiryEntered = new CountDownLatch(1);
        when(pauses.safeRelease("session-control-expired", "lease_expired"))
                .thenAnswer(invocation -> {
                    assertThat(Thread.currentThread().getName())
                            .isEqualTo("breakhub-control-expiry");
                    expiryEntered.countDown();
                    throw cleanupFailure;
                });
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "control-expiry-failure");
        control.start(actor, () -> "session-control-expired");

        assertThat(expiryEntered.await(2, TimeUnit.SECONDS)).isTrue();
        await(() -> remote.stopCount == 1);

        assertThat(control.debuggingSnapshot("session-control-expired"))
                .containsEntry("status", "idle");
        assertThat(control.activeDebuggingSession()).isEmpty();
        assertThat(control.controlSnapshot(Optional.of(actor)))
                .containsEntry("held", false);
        control.debuggingSnapshot("session-control-expired");
        control.activeDebuggingSession();
        verify(pauses).safeRelease("session-control-expired", "lease_expired");

        control.close();
        reportingLease.close();
    }

    @Test
    void aDebuggingEntryConvergesLocallyWhileTheExpiryCallbackWaitsForItsLock() throws Exception {
        ControllableScheduler scheduler = new ControllableScheduler();
        PendingRenewalRemote remote = new PendingRenewalRemote();
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
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "expiry-race");
        control.start(actor, () -> "session-race");

        CompletableFuture<Void> expiry;
        synchronized (control) {
            expiry = CompletableFuture.runAsync(() -> scheduler.advanceBy(Duration.ofSeconds(30)));
            await(reportingLease::expired);

            assertThat(control.activeDebuggingSession()).isEmpty();
            assertThat(control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", true)
                    .containsEntry("owned_by_requester", true);
            verify(pauses).safeRelease("session-race", "reporting_lease_expired");
        }

        expiry.get(1, TimeUnit.SECONDS);
        assertThat(remote.stopCount).isEqualTo(1);
        control.close();
        reportingLease.close();
    }

    @Test
    void closePreventsQueuedWritesFromReacquiringControlOrCreatingAReportingLease() {
        ControllableScheduler scheduler = new ControllableScheduler();
        PendingRenewalRemote remote = new PendingRenewalRemote();
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
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "closing");
        control.start(actor, () -> "session-closing");

        control.close();
        control.close();

        assertShuttingDown(() -> control.start(actor, () -> "session-closing"));
        assertShuttingDown(() -> control.stop(actor, "session-closing"));
        assertShuttingDown(() -> control.heartbeat(actor));
        assertShuttingDown(() -> control.release(actor));
        assertShuttingDown(() -> control.performWrite(actor, () -> {
            throw new AssertionError("the write must not run after close");
        }));
        assertShuttingDown(() -> control.performWhileDebugging(active -> {
            throw new AssertionError("the debugging operation must not run after close");
        }));
        assertShuttingDown(() -> control.performWithDebuggingState(active -> {
            throw new AssertionError("the state operation must not run after close");
        }));
        assertShuttingDown(control::requireSessionSwitchAllowed);
        assertShuttingDown(() -> control.touch(actor));
        assertShuttingDown(() -> control.releaseIfOwner(actor, "late_release"));
        assertThat(remote.createCount).isEqualTo(1);
        assertThat(remote.stopCount).isEqualTo(1);
        verify(pauses).safeRelease("session-closing", "product_shutdown");
        assertThat(control.controlSnapshot(Optional.of(actor)))
                .containsEntry("held", false);
        assertThat(control.activeDebuggingSession()).isEmpty();
        reportingLease.close();
    }

    @Test
    void reportingCloseCannotTurnAnAlreadyCommittedConcurrentStartIntoAStaleSuccess() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledTask = mock(ScheduledFuture.class);
        when(scheduler.awaitTermination(1, TimeUnit.SECONDS)).thenReturn(true);
        AtomicInteger scheduleCount = new AtomicInteger();
        AtomicInteger observedCommittedDebugging = new AtomicInteger();
        AtomicReference<DebugControlService> controlReference = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> monitorBlocker = new AtomicReference<>();
        CountDownLatch postCommitMonitorHeld = new CountDownLatch(1);
        CountDownLatch coordinatorShutdown = new CountDownLatch(1);
        CountDownLatch allowCloseCallback = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        doAnswer(invocation -> {
            coordinatorShutdown.countDown();
            awaitLatch(allowCloseCallback);
            return null;
        }).when(scheduler).shutdownNow();
        when(scheduler.schedule(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    if (scheduleCount.incrementAndGet() == 2) {
                        controlReference.get().performWhileDebugging(active -> {
                            observedCommittedDebugging.incrementAndGet();
                            return null;
                        });
                        CompletableFuture<Void> blocker = CompletableFuture.runAsync(() -> {
                            synchronized (controlReference.get()) {
                                postCommitMonitorHeld.countDown();
                                awaitLatch(releaseMonitor);
                            }
                        });
                        monitorBlocker.set(blocker);
                        assertThat(postCommitMonitorHeld.await(1, TimeUnit.SECONDS)).isTrue();
                    }
                    return scheduledTask;
                });
        PendingRenewalRemote remote = new PendingRenewalRemote();
        ReportingLeaseCoordinator reportingLease = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                System::nanoTime,
                java.time.Clock.systemUTC());
        ProductProperties properties = mock(ProductProperties.class);
        when(properties.controlLease()).thenReturn(
                new ProductProperties.ControlLease(Duration.ofHours(1)));
        PauseService pauses = mock(PauseService.class);
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        controlReference.set(control);
        ControlIdentity actor = new ControlIdentity("web", "reporting-close-race");

        CompletableFuture<Map<String, Object>> starting = CompletableFuture.supplyAsync(
                () -> control.start(actor, () -> "session-reporting-close-race"));
        assertThat(postCommitMonitorHeld.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(observedCommittedDebugging).hasValue(1);
        CompletableFuture<Void> closing = CompletableFuture.runAsync(reportingLease::close);

        try {
            assertThat(coordinatorShutdown.await(1, TimeUnit.SECONDS)).isTrue();
            releaseMonitor.countDown();
            assertThatThrownBy(() -> starting.get(1, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ProductException.class);
            assertThat(control.activeDebuggingSession()).isEmpty();
            allowCloseCallback.countDown();
            closing.get(1, TimeUnit.SECONDS);
            verify(pauses, times(1)).safeRelease(
                    "session-reporting-close-race",
                    "reporting_lease_expired");
        } finally {
            releaseMonitor.countDown();
            allowCloseCallback.countDown();
            CompletableFuture<Void> blocker = monitorBlocker.get();
            if (blocker != null) {
                blocker.get(1, TimeUnit.SECONDS);
            }
            closing.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            control.close();
        }
    }

    @Test
    void closeStillClearsResourcesAndWaitsForReportingWhenPauseCleanupFails() throws Exception {
        ControllableScheduler scheduler = new ControllableScheduler();
        AwaitedStopRemote remote = new AwaitedStopRemote();
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
        when(pauses.safeRelease("session-close-failure", "product_shutdown"))
                .thenThrow(new IllegalStateException("pause cleanup failed"));
        Set<Thread> threadsBeforeControl = Thread.getAllStackTraces().keySet();
        DebugControlService control = new DebugControlService(properties, reportingLease, pauses);
        ControlIdentity actor = new ControlIdentity("web", "close-failure");
        control.start(actor, () -> "session-close-failure");
        Thread controlExpiryThread = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "breakhub-control-expiry".equals(thread.getName()))
                .filter(thread -> !threadsBeforeControl.contains(thread))
                .filter(Thread::isAlive)
                .findFirst()
                .orElseThrow();

        CompletableFuture<Void> closing = CompletableFuture.runAsync(control::close);
        try {
            await(() -> remote.stopCount == 1);
            assertThat(closing).isNotDone();
            control.close();
            assertThat(remote.stopCount).isEqualTo(1);
            assertThat(control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", false);
            assertThat(control.activeDebuggingSession()).isEmpty();
            await(() -> !controlExpiryThread.isAlive());
        } finally {
            remote.stop.complete(null);
        }

        closing.get(1, TimeUnit.SECONDS);
        control.close();
        assertThat(remote.stopCount).isEqualTo(1);
        verify(pauses).safeRelease("session-close-failure", "product_shutdown");
        reportingLease.close();
    }

    private static void assertShuttingDown(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ProductException.class)
                .satisfies(error -> {
                    ProductException productError = (ProductException) error;
                    assertThat(productError.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(productError.code()).isEqualTo("PRODUCT_SHUTTING_DOWN");
                });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while awaiting the test latch");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static final class PendingRenewalRemote implements ReportingLeaseRemote {

        private final CompletableFuture<LeaseAcknowledgement> pendingRenewal =
                new CompletableFuture<>();
        private volatile int createCount;
        private volatile int stopCount;

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            createCount++;
            return CompletableFuture.completedFuture(new LeaseAcknowledgement(
                    "lease-race",
                    Duration.ofSeconds(30),
                    "healthy",
                    null));
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            return pendingRenewal;
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount++;
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class AwaitedStopRemote implements ReportingLeaseRemote {

        private final CompletableFuture<Void> stop = new CompletableFuture<>();
        private volatile int stopCount;

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            return CompletableFuture.completedFuture(new LeaseAcknowledgement(
                    "lease-close-failure",
                    Duration.ofSeconds(30),
                    "healthy",
                    null));
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            return CompletableFuture.completedFuture(new LeaseAcknowledgement(
                    leaseId,
                    Duration.ofSeconds(30),
                    "healthy",
                    null));
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount++;
            return stop;
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
