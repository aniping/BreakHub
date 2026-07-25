package com.ateagents.breakhub.probe;

import java.util.Objects;

final class ReportingChannel {

    private State state = State.IDLE;
    private long leaseGeneration;
    private long healthEpoch;
    private boolean probeCredit;
    private boolean probeInFlight;
    private String lastError;

    synchronized Health activate() {
        leaseGeneration++;
        healthEpoch++;
        state = State.HEALTHY;
        probeCredit = false;
        probeInFlight = false;
        lastError = null;
        return snapshotLocked();
    }

    synchronized Health renewAndSnapshot() {
        if (state == State.DEGRADED) {
            probeCredit = true;
        }
        return snapshotLocked();
    }

    synchronized Health deactivate() {
        leaseGeneration++;
        healthEpoch++;
        state = State.IDLE;
        probeCredit = false;
        probeInFlight = false;
        lastError = null;
        return snapshotLocked();
    }

    synchronized Permit tryAcquire() {
        if (state == State.IDLE) {
            return new Permit(leaseGeneration, healthEpoch, false, false);
        }
        if (state == State.HEALTHY) {
            return new Permit(leaseGeneration, healthEpoch, true, false);
        }
        if (!probeCredit || probeInFlight) {
            return new Permit(leaseGeneration, healthEpoch, false, false);
        }
        probeCredit = false;
        probeInFlight = true;
        return new Permit(leaseGeneration, healthEpoch, true, true);
    }

    synchronized boolean canStart(Permit permit) {
        return permit.allowed()
                && permit.leaseGeneration() == leaseGeneration
                && permit.healthEpoch() == healthEpoch
                && state != State.IDLE
                && (!permit.probe() || probeInFlight);
    }

    synchronized boolean succeeded(Permit permit) {
        if (!currentLease(permit) || state == State.IDLE) {
            return false;
        }
        if (!permit.probe()) {
            return true;
        }
        if (permit.healthEpoch() != healthEpoch || !probeInFlight) {
            return false;
        }
        state = State.HEALTHY;
        healthEpoch++;
        probeCredit = false;
        probeInFlight = false;
        lastError = null;
        return true;
    }

    synchronized void failed(Permit permit, String errorSummary) {
        if (!currentHealthEpoch(permit) || state == State.IDLE) {
            return;
        }
        if (permit.probe()) {
            probeInFlight = false;
        }
        if (state != State.DEGRADED) {
            state = State.DEGRADED;
            healthEpoch++;
        }
        lastError = Objects.requireNonNull(errorSummary, "errorSummary");
    }

    synchronized Health snapshot() {
        return snapshotLocked();
    }

    synchronized boolean isActive() {
        return state != State.IDLE;
    }

    private boolean currentLease(Permit permit) {
        return permit.allowed() && permit.leaseGeneration() == leaseGeneration;
    }

    private boolean currentHealthEpoch(Permit permit) {
        return currentLease(permit) && permit.healthEpoch() == healthEpoch;
    }

    private Health snapshotLocked() {
        return switch (state) {
            case IDLE -> new Health("idle", null);
            case HEALTHY -> new Health("healthy", null);
            case DEGRADED -> new Health("degraded", lastError);
        };
    }

    record Permit(long leaseGeneration, long healthEpoch, boolean allowed, boolean probe) {
    }

    record Health(String status, String lastError) {
    }

    private enum State {
        IDLE,
        HEALTHY,
        DEGRADED
    }
}
