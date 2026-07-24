package com.ateagents.breakhub.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ateagents.breakhub.api.ProductException;

import jakarta.annotation.PreDestroy;

@Component
final class ReportingLeaseCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportingLeaseCoordinator.class);
    private static final Duration RENEW_DELAY = Duration.ofSeconds(10);
    private static final Duration LEASE_TIMEOUT = Duration.ofSeconds(30);

    private final ReportingLeaseRemote remote;
    private final Duration renewDelay;
    private final Duration expectedLeaseTimeout;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier monotonicClock;
    private final Clock diagnosticClock;
    private final ReentrantLock localCommitGate = new ReentrantLock();

    private String activeLeaseId;
    private LeaseState state = LeaseState.IDLE;
    private String channelStatus;
    private String lastError;
    private Instant lastConfirmedAt;
    private Instant serverDeadlineAt;
    private long deadlineNanos;
    private long generation;
    private long confirmationVersion;
    private Runnable expiryCallback;
    private BooleanSupplier recoveryAllowed;
    private ScheduledFuture<?> nextAttemptTask;
    private Object nextAttemptTaskToken;
    private ScheduledFuture<?> expiryTask;
    private CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> createInFlight;
    private CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renewalInFlight;
    private RecoveryAttempt recoveryInFlight;
    private final Map<String, StopAttempt> stopAttempts = new LinkedHashMap<>();
    private final List<StopAttempt> deferredStops = new ArrayList<>();
    private CompletableFuture<Void> latestStop = CompletableFuture.completedFuture(null);
    private int localCleanupsInProgress;
    private boolean closed;

    @Autowired
    ReportingLeaseCoordinator(ReportingLeaseRemote remote) {
        this(
                remote,
                RENEW_DELAY,
                LEASE_TIMEOUT,
                newScheduler(),
                System::nanoTime,
                Clock.systemUTC());
    }

    ReportingLeaseCoordinator(
            ReportingLeaseRemote remote,
            Duration renewDelay,
            ScheduledExecutorService scheduler) {
        this(
                remote,
                renewDelay,
                LEASE_TIMEOUT,
                scheduler,
                System::nanoTime,
                Clock.systemUTC());
    }

    ReportingLeaseCoordinator(
            ReportingLeaseRemote remote,
            Duration renewDelay,
            Duration expectedLeaseTimeout,
            ScheduledExecutorService scheduler) {
        this(
                remote,
                renewDelay,
                expectedLeaseTimeout,
                scheduler,
                System::nanoTime,
                Clock.systemUTC());
    }

    ReportingLeaseCoordinator(
            ReportingLeaseRemote remote,
            Duration renewDelay,
            Duration expectedLeaseTimeout,
            ScheduledExecutorService scheduler,
            LongSupplier monotonicClock,
            Clock diagnosticClock) {
        if (renewDelay.isNegative() || renewDelay.isZero()) {
            throw new IllegalArgumentException("Reporting lease renew delay must be positive");
        }
        if (expectedLeaseTimeout.isNegative() || expectedLeaseTimeout.isZero()) {
            throw new IllegalArgumentException("Reporting lease timeout must be positive");
        }
        this.remote = Objects.requireNonNull(remote, "remote");
        this.renewDelay = renewDelay;
        this.expectedLeaseTimeout = expectedLeaseTimeout;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.diagnosticClock = Objects.requireNonNull(diagnosticClock, "diagnosticClock");
    }

    <T> T start(Supplier<T> commitLocalStart) {
        return start(commitLocalStart, () -> {
        });
    }

    <T> T start(Supplier<T> commitLocalStart, Runnable onExpired) {
        return start(commitLocalStart, onExpired, () -> true);
    }

    <T> T start(
            Supplier<T> commitLocalStart,
            Runnable onExpired,
            BooleanSupplier canRecover) {
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create;
        synchronized (this) {
            requireOpen();
            if (activeLeaseId != null || createInFlight != null) {
                throw new IllegalStateException("A reporting lease is already active or being created");
            }
            create = Objects.requireNonNull(remote.create(), "remote.create()");
            createInFlight = create;
        }

        ReportingLeaseRemote.LeaseAcknowledgement acknowledgement;
        try {
            acknowledgement = await(create);
            validateAcknowledgement(acknowledgement, null);
        } catch (RuntimeException error) {
            synchronized (this) {
                if (createInFlight == create) {
                    createInFlight = null;
                }
                if (closed) {
                    throw shuttingDown();
                }
            }
            throw error;
        }

        String leaseId = acknowledgement.leaseId();
        long leaseGeneration;
        StopAttempt orphanedLease = null;
        synchronized (this) {
            if (createInFlight == create) {
                createInFlight = null;
            }
            if (closed) {
                orphanedLease = reserveStopLocked(leaseId);
                leaseGeneration = generation;
            } else {
                activeLeaseId = leaseId;
                state = LeaseState.IDLE;
                expiryCallback = Objects.requireNonNull(onExpired, "onExpired");
                recoveryAllowed = Objects.requireNonNull(canRecover, "canRecover");
                leaseGeneration = ++generation;
                confirmLocked(acknowledgement);
            }
        }
        if (orphanedLease != null) {
            startRemoteStop(orphanedLease);
            orphanedLease.cancelForShutdown();
            throw shuttingDown();
        }

        try {
            localCommitGate.lock();
            T result;
            try {
                synchronized (this) {
                    if (closed) {
                        throw shuttingDown();
                    }
                    if (!isCurrent(leaseId, leaseGeneration)) {
                        throw new IllegalStateException("Reporting lease ended before local start could begin");
                    }
                }
                result = commitLocalStart.get();
                synchronized (this) {
                    if (closed) {
                        throw shuttingDown();
                    }
                    if (!isCurrent(leaseId, leaseGeneration)) {
                        throw new IllegalStateException("Reporting lease ended before local start completed");
                    }
                    state = LeaseState.HEALTHY;
                    scheduleRenewalLocked(leaseId, leaseGeneration);
                }
            } finally {
                localCommitGate.unlock();
            }
            return result;
        } catch (RuntimeException error) {
            await(stopBestEffort());
            throw error;
        }
    }

    synchronized String status() {
        return state.value;
    }

    synchronized boolean expired() {
        return state == LeaseState.EXPIRED;
    }

    synchronized boolean hasActiveLease() {
        return !closed && activeLeaseId != null;
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", state.value);
        if (channelStatus != null) {
            snapshot.put("channel_status", channelStatus);
        }
        if (lastConfirmedAt != null) {
            snapshot.put("last_confirmed_at", lastConfirmedAt.toString());
        }
        if (serverDeadlineAt != null) {
            snapshot.put("server_deadline_at", serverDeadlineAt.toString());
        }
        if (lastError != null) {
            snapshot.put("last_error", lastError);
        }
        return snapshot;
    }

    CompletableFuture<Void> stopBestEffort() {
        StopAttempt stopAttempt;
        synchronized (this) {
            if (activeLeaseId == null) {
                return latestStop;
            }
            String leaseId = activeLeaseId;
            activeLeaseId = null;
            state = LeaseState.IDLE;
            channelStatus = null;
            lastError = null;
            lastConfirmedAt = null;
            serverDeadlineAt = null;
            expiryCallback = null;
            recoveryAllowed = null;
            generation++;
            confirmationVersion++;
            cancelTasksLocked();
            stopAttempt = reserveStopLocked(leaseId);
        }
        startRemoteStop(stopAttempt);
        return stopAttempt.completion;
    }

    @Override
    @PreDestroy
    public void close() {
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> pendingCreate;
        StopAttempt activeStop = null;
        Runnable localShutdown = null;
        List<StopAttempt> pendingStops;
        localCommitGate.lock();
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pendingCreate = createInFlight;
                createInFlight = null;
                if (activeLeaseId != null) {
                    String leaseId = activeLeaseId;
                    localShutdown = expiryCallback;
                    if (localShutdown != null) {
                        localCleanupsInProgress++;
                    }
                    activeLeaseId = null;
                    state = LeaseState.IDLE;
                    channelStatus = null;
                    lastError = null;
                    lastConfirmedAt = null;
                    serverDeadlineAt = null;
                    expiryCallback = null;
                    recoveryAllowed = null;
                    generation++;
                    confirmationVersion++;
                    activeStop = reserveStopLocked(leaseId);
                }
                cancelTasksLocked();
                pendingStops = new ArrayList<>(stopAttempts.values());
                if (activeStop != null) {
                    pendingStops.remove(activeStop);
                }
                stopAttempts.clear();
            }
        } finally {
            localCommitGate.unlock();
        }

        if (pendingCreate != null) {
            pendingCreate.cancel(true);
        }
        scheduler.shutdownNow();
        if (localShutdown != null) {
            try {
                localShutdown.run();
            } catch (RuntimeException error) {
                LOGGER.error("Business reporting lease closed but local cleanup failed", error);
            } finally {
                localCleanupCompleted();
            }
        }
        if (activeStop != null) {
            startAfterLocalCleanup(activeStop);
        }
        pendingStops.forEach(StopAttempt::cancelForShutdown);
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.warn("Reporting lease scheduler did not terminate within the shutdown grace period");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw error;
        }
    }

    private void confirmLocked(ReportingLeaseRemote.LeaseAcknowledgement acknowledgement) {
        validateAcknowledgement(acknowledgement, activeLeaseId);
        Instant confirmedAt = diagnosticClock.instant();
        long confirmedNanos = monotonicClock.getAsLong();
        lastConfirmedAt = confirmedAt;
        serverDeadlineAt = confirmedAt.plus(acknowledgement.leaseTimeout());
        deadlineNanos = confirmedNanos + acknowledgement.leaseTimeout().toNanos();
        channelStatus = acknowledgement.channelStatus();
        lastError = "degraded".equals(channelStatus)
                ? acknowledgement.channelLastError()
                : null;
        confirmationVersion++;
        scheduleExpiryLocked(activeLeaseId, generation, confirmationVersion);
    }

    private void scheduleRenewalLocked(String leaseId, long leaseGeneration) {
        Object taskToken = new Object();
        ScheduledFuture<?> scheduled = scheduler.schedule(
                () -> beginRenewal(leaseId, leaseGeneration, taskToken),
                renewDelay.toNanos(),
                TimeUnit.NANOSECONDS);
        nextAttemptTaskToken = taskToken;
        nextAttemptTask = scheduled;
    }

    private void beginRenewal(String leaseId, long leaseGeneration, Object taskToken) {
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renewal;
        synchronized (this) {
            if (!claimNextAttemptTaskLocked(taskToken)) {
                return;
            }
            if (!isCurrent(leaseId, leaseGeneration)) {
                return;
            }
            try {
                renewal = remote.renew(leaseId);
            } catch (RuntimeException error) {
                renewal = CompletableFuture.failedFuture(error);
            }
            renewalInFlight = renewal;
        }
        CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> expectedRenewal = renewal;
        renewal.whenComplete((acknowledgement, error) -> renewalCompleted(
                leaseId,
                leaseGeneration,
                expectedRenewal,
                acknowledgement,
                error));
    }

    private void renewalCompleted(
            String leaseId,
            long leaseGeneration,
            CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> completedRenewal,
            ReportingLeaseRemote.LeaseAcknowledgement acknowledgement,
            Throwable error) {
        boolean expireNow = false;
        long expectedConfirmation = 0;
        RecoveryContext recovery = null;
        synchronized (this) {
            if (!isCurrent(leaseId, leaseGeneration) || renewalInFlight != completedRenewal) {
                return;
            }
            renewalInFlight = null;
            if (deadlineReachedLocked()) {
                expireNow = true;
                expectedConfirmation = confirmationVersion;
            } else if (error != null) {
                state = LeaseState.DEGRADED;
                lastError = sanitizedError(error);
                if (isLeaseNotFound(error)) {
                    recovery = new RecoveryContext(
                            leaseId,
                            leaseGeneration,
                            confirmationVersion,
                            deadlineNanos,
                            recoveryAllowed);
                } else {
                    LOGGER.warn("Business reporting lease renewal failed; retrying after the fixed delay");
                    scheduleRenewalLocked(leaseId, leaseGeneration);
                }
            } else {
                try {
                    validateAcknowledgement(acknowledgement, leaseId);
                    confirmLocked(acknowledgement);
                    state = LeaseState.HEALTHY;
                    scheduleRenewalLocked(leaseId, leaseGeneration);
                } catch (RuntimeException invalidAcknowledgement) {
                    state = LeaseState.DEGRADED;
                    lastError = "INVALID_REPORTING_LEASE_ACK";
                    scheduleRenewalLocked(leaseId, leaseGeneration);
                }
            }
        }
        if (expireNow) {
            expire(leaseId, leaseGeneration, expectedConfirmation);
        } else if (recovery != null) {
            startRecovery(recovery);
        }
    }

    private void startRecovery(RecoveryContext context) {
        if (!isRecoveryAllowed(context)) {
            StopAttempt abandonedLease;
            synchronized (this) {
                abandonedLease = abandonRecoveryContextLocked(context);
            }
            if (abandonedLease != null) {
                startRemoteStop(abandonedLease);
            }
            return;
        }

        RecoveryAttempt attempt = null;
        boolean expireNow = false;
        synchronized (this) {
            if (!isCurrentRecoveryContext(context)) {
                return;
            }
            if (deadlineReached(context.deadlineNanos)) {
                expireNow = true;
            } else if (recoveryInFlight == null) {
                CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> create;
                try {
                    create = Objects.requireNonNull(remote.create(), "remote.create()");
                } catch (RuntimeException error) {
                    create = CompletableFuture.failedFuture(error);
                }
                attempt = new RecoveryAttempt(context, create);
                recoveryInFlight = attempt;
            }
        }
        if (expireNow) {
            expire(context.leaseId, context.generation, context.confirmationVersion);
            return;
        }
        if (attempt != null) {
            RecoveryAttempt expectedAttempt = attempt;
            attempt.source.whenComplete((acknowledgement, error) -> recoveryCompleted(
                    expectedAttempt,
                    acknowledgement,
                    error));
        }
    }

    private void recoveryCompleted(
            RecoveryAttempt attempt,
            ReportingLeaseRemote.LeaseAcknowledgement acknowledgement,
            Throwable error) {
        boolean validAcknowledgement = false;
        boolean invalidAcknowledgement = false;
        Throwable completionError = error;
        if (completionError == null) {
            try {
                validateAcknowledgement(acknowledgement, null);
                if (attempt.context.leaseId.equals(acknowledgement.leaseId())) {
                    throw new IllegalArgumentException("A recovered lease must have a new identity");
                }
                validAcknowledgement = true;
            } catch (RuntimeException invalidAck) {
                completionError = invalidAck;
                invalidAcknowledgement = true;
            }
        }

        boolean localRecoveryAllowed = false;
        if (validAcknowledgement) {
            boolean currentCandidate;
            synchronized (this) {
                currentCandidate = recoveryInFlight == attempt
                        && isCurrentRecoveryContext(attempt.context)
                        && !deadlineReached(attempt.context.deadlineNanos);
            }
            if (currentCandidate) {
                localRecoveryAllowed = isRecoveryAllowed(attempt.context);
            }
        }

        boolean expireNow = false;
        StopAttempt abandonedLease = null;
        StopAttempt discardedLease = null;
        synchronized (this) {
            if (recoveryInFlight != attempt || !isCurrentRecoveryContext(attempt.context)) {
                if (validAcknowledgement
                        && !acknowledgement.leaseId().equals(activeLeaseId)) {
                    discardedLease = reserveStopLocked(acknowledgement.leaseId());
                }
            } else {
                recoveryInFlight = null;
                if (deadlineReached(attempt.context.deadlineNanos)) {
                    expireNow = true;
                    if (validAcknowledgement) {
                        discardedLease = reserveStopLocked(acknowledgement.leaseId());
                    }
                } else if (completionError != null) {
                    state = LeaseState.DEGRADED;
                    lastError = invalidAcknowledgement
                            ? "INVALID_REPORTING_LEASE_ACK"
                            : sanitizedError(completionError);
                    scheduleRecoveryLocked(attempt.context);
                } else if (!localRecoveryAllowed) {
                    abandonedLease = abandonRecoveryContextLocked(attempt.context);
                    discardedLease = reserveStopLocked(acknowledgement.leaseId());
                } else {
                    activeLeaseId = acknowledgement.leaseId();
                    long recoveredGeneration = ++generation;
                    confirmLocked(acknowledgement);
                    state = LeaseState.HEALTHY;
                    scheduleRenewalLocked(activeLeaseId, recoveredGeneration);
                }
            }
        }

        if (expireNow) {
            expire(
                    attempt.context.leaseId,
                    attempt.context.generation,
                    attempt.context.confirmationVersion);
        }
        if (abandonedLease != null) {
            startAfterLocalCleanup(abandonedLease);
        }
        if (discardedLease != null) {
            startAfterLocalCleanup(discardedLease);
        }
    }

    private void scheduleRecoveryLocked(RecoveryContext context) {
        Object taskToken = new Object();
        ScheduledFuture<?> scheduled = scheduler.schedule(
                () -> beginScheduledRecovery(context, taskToken),
                renewDelay.toNanos(),
                TimeUnit.NANOSECONDS);
        nextAttemptTaskToken = taskToken;
        nextAttemptTask = scheduled;
    }

    private void beginScheduledRecovery(RecoveryContext context, Object taskToken) {
        synchronized (this) {
            if (!claimNextAttemptTaskLocked(taskToken)) {
                return;
            }
        }
        startRecovery(context);
    }

    private boolean claimNextAttemptTaskLocked(Object taskToken) {
        if (nextAttemptTaskToken != taskToken) {
            return false;
        }
        nextAttemptTaskToken = null;
        nextAttemptTask = null;
        return true;
    }

    private boolean isCurrentRecoveryContext(RecoveryContext context) {
        return isCurrent(context.leaseId, context.generation)
                && confirmationVersion == context.confirmationVersion
                && deadlineNanos == context.deadlineNanos;
    }

    private boolean isRecoveryAllowed(RecoveryContext context) {
        return context.allowed.getAsBoolean();
    }

    private StopAttempt abandonRecoveryContextLocked(RecoveryContext context) {
        if (!isCurrentRecoveryContext(context)) {
            return null;
        }
        String leaseId = activeLeaseId;
        activeLeaseId = null;
        state = LeaseState.IDLE;
        channelStatus = null;
        lastError = null;
        lastConfirmedAt = null;
        serverDeadlineAt = null;
        expiryCallback = null;
        recoveryAllowed = null;
        generation++;
        confirmationVersion++;
        cancelTasksLocked();
        return reserveStopLocked(leaseId);
    }

    private void scheduleExpiryLocked(String leaseId, long leaseGeneration, long expectedConfirmation) {
        if (expiryTask != null) {
            expiryTask.cancel(false);
        }
        long remainingNanos = remainingNanosLocked();
        expiryTask = scheduler.schedule(
                () -> expire(leaseId, leaseGeneration, expectedConfirmation),
                remainingNanos,
                TimeUnit.NANOSECONDS);
    }

    private void expire(String leaseId, long leaseGeneration, long expectedConfirmation) {
        Runnable localExpiry;
        StopAttempt stopAttempt;
        synchronized (this) {
            if (!isCurrent(leaseId, leaseGeneration)
                    || confirmationVersion != expectedConfirmation) {
                return;
            }
            long remainingNanos = remainingNanosLocked();
            if (remainingNanos > 0) {
                scheduleExpiryLocked(leaseId, leaseGeneration, expectedConfirmation);
                return;
            }
            activeLeaseId = null;
            state = LeaseState.EXPIRED;
            if (lastError == null) {
                lastError = "REPORTING_LEASE_EXPIRED";
            }
            localExpiry = expiryCallback;
            expiryCallback = null;
            if (localExpiry != null) {
                localCleanupsInProgress++;
            }
            recoveryAllowed = null;
            generation++;
            confirmationVersion++;
            cancelTasksLocked();
            stopAttempt = reserveStopLocked(leaseId);
        }

        if (localExpiry != null) {
            try {
                localExpiry.run();
            } catch (RuntimeException error) {
                LOGGER.error("Business reporting lease expired but local cleanup failed", error);
            } finally {
                localCleanupCompleted();
            }
        }
        startAfterLocalCleanup(stopAttempt);
    }

    private StopAttempt reserveStopLocked(String leaseId) {
        StopAttempt stopAttempt = stopAttempts.computeIfAbsent(leaseId, StopAttempt::new);
        latestStop = stopAttempt.completion;
        return stopAttempt;
    }

    private void startAfterLocalCleanup(StopAttempt stopAttempt) {
        boolean cancelForShutdown;
        synchronized (this) {
            if (localCleanupsInProgress > 0) {
                if (!deferredStops.contains(stopAttempt)) {
                    deferredStops.add(stopAttempt);
                }
                return;
            }
            cancelForShutdown = closed;
        }
        startRemoteStop(stopAttempt);
        if (cancelForShutdown) {
            stopAttempt.cancelForShutdown();
        }
    }

    private void localCleanupCompleted() {
        List<StopAttempt> ready = List.of();
        synchronized (this) {
            if (localCleanupsInProgress == 0) {
                return;
            }
            localCleanupsInProgress--;
            if (localCleanupsInProgress == 0 && !deferredStops.isEmpty()) {
                ready = new ArrayList<>(deferredStops);
                deferredStops.clear();
            }
        }
        ready.forEach(this::startAfterLocalCleanup);
    }

    private void startRemoteStop(StopAttempt stopAttempt) {
        CompletableFuture<Void> source;
        try {
            source = stopAttempt.begin(() -> remote.stop(stopAttempt.leaseId));
            if (source == null) {
                return;
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Local debugging has stopped but remote reporting lease stop could not be started");
            stopAttempt.completion.complete(null);
            removeStopAttempt(stopAttempt);
            return;
        }

        source.whenComplete((ignored, error) -> {
            if (error != null && !stopAttempt.shutdownCancellationRequested()) {
                LOGGER.warn("Local debugging has stopped but the remote reporting lease could not be stopped");
            }
            stopAttempt.completion.complete(null);
            removeStopAttempt(stopAttempt);
        });
    }

    private synchronized void removeStopAttempt(StopAttempt stopAttempt) {
        stopAttempts.remove(stopAttempt.leaseId, stopAttempt);
    }

    private void requireOpen() {
        if (closed) {
            throw shuttingDown();
        }
    }

    private static ProductException shuttingDown() {
        return new ProductException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PRODUCT_SHUTTING_DOWN",
                "BreakHub product is shutting down");
    }

    private void cancelTasksLocked() {
        if (nextAttemptTask != null) {
            nextAttemptTask.cancel(false);
            nextAttemptTask = null;
        }
        nextAttemptTaskToken = null;
        if (expiryTask != null) {
            expiryTask.cancel(false);
            expiryTask = null;
        }
        if (renewalInFlight != null) {
            CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> renewal = renewalInFlight;
            renewalInFlight = null;
            renewal.cancel(true);
        }
        if (recoveryInFlight != null) {
            RecoveryAttempt recovery = recoveryInFlight;
            recoveryInFlight = null;
            recovery.source.cancel(true);
        }
    }

    private long remainingNanosLocked() {
        long remaining = deadlineNanos - monotonicClock.getAsLong();
        return Math.max(0, remaining);
    }

    private boolean deadlineReachedLocked() {
        return deadlineReached(deadlineNanos);
    }

    private boolean deadlineReached(long expectedDeadlineNanos) {
        return monotonicClock.getAsLong() - expectedDeadlineNanos >= 0;
    }

    private boolean isCurrent(String leaseId, long leaseGeneration) {
        return generation == leaseGeneration && leaseId.equals(activeLeaseId);
    }

    private void validateAcknowledgement(
            ReportingLeaseRemote.LeaseAcknowledgement acknowledgement,
            String expectedLeaseId) {
        if (acknowledgement == null
                || acknowledgement.leaseId() == null
                || acknowledgement.leaseId().isBlank()
                || (expectedLeaseId != null && !acknowledgement.leaseId().equals(expectedLeaseId))
                || !expectedLeaseTimeout.equals(acknowledgement.leaseTimeout())
                || !("healthy".equals(acknowledgement.channelStatus())
                        || "degraded".equals(acknowledgement.channelStatus()))
                || ("healthy".equals(acknowledgement.channelStatus())
                        && acknowledgement.channelLastError() != null)
                || ("degraded".equals(acknowledgement.channelStatus())
                        && (acknowledgement.channelLastError() == null
                                || acknowledgement.channelLastError().isBlank()))) {
            throw new IllegalArgumentException("Invalid reporting lease acknowledgement");
        }
    }

    private static String sanitizedError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ProductException productError) {
            return productError.code();
        }
        if (cause instanceof CancellationException) {
            return "REPORTING_LEASE_CANCELLED";
        }
        return "REPORTING_LEASE_UNAVAILABLE";
    }

    private static boolean isLeaseNotFound(Throwable error) {
        return "REPORTING_LEASE_NOT_FOUND".equals(sanitizedError(error));
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "breakhub-reporting-renewal");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private record RecoveryContext(
            String leaseId,
            long generation,
            long confirmationVersion,
            long deadlineNanos,
            BooleanSupplier allowed) {
    }

    private record RecoveryAttempt(
            RecoveryContext context,
            CompletableFuture<ReportingLeaseRemote.LeaseAcknowledgement> source) {
    }

    private static final class StopAttempt {

        private final String leaseId;
        private final StopCompletion completion;
        private CompletableFuture<Void> source;
        private boolean started;
        private boolean cancellationRequested;
        private boolean shutdownCancellationRequested;

        private StopAttempt(String leaseId) {
            this.leaseId = leaseId;
            this.completion = new StopCompletion(this);
        }

        private synchronized CompletableFuture<Void> begin(
                Supplier<CompletableFuture<Void>> request) {
            if (started || cancellationRequested) {
                return null;
            }
            started = true;
            source = Objects.requireNonNull(request.get(), "remote.stop()");
            return source;
        }

        private void cancelForShutdown() {
            CompletableFuture<Void> sourceToCancel;
            synchronized (this) {
                cancellationRequested = true;
                shutdownCancellationRequested = true;
                sourceToCancel = source;
            }
            completion.complete(null);
            if (sourceToCancel != null) {
                sourceToCancel.cancel(true);
            }
        }

        private synchronized boolean shutdownCancellationRequested() {
            return shutdownCancellationRequested;
        }

        private void cancelSource(boolean mayInterruptIfRunning) {
            CompletableFuture<Void> sourceToCancel;
            synchronized (this) {
                cancellationRequested = true;
                sourceToCancel = source;
            }
            if (sourceToCancel != null) {
                sourceToCancel.cancel(mayInterruptIfRunning);
            }
        }
    }

    private static final class StopCompletion extends CompletableFuture<Void> {

        private final StopAttempt owner;

        private StopCompletion(StopAttempt owner) {
            this.owner = owner;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                owner.cancelSource(mayInterruptIfRunning);
            }
            return cancelled;
        }
    }

    private enum LeaseState {
        IDLE("idle"),
        HEALTHY("healthy"),
        DEGRADED("degraded"),
        EXPIRED("expired");

        private final String value;

        LeaseState(String value) {
            this.value = value;
        }
    }
}
