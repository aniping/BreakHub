package com.ateagents.breakhub.domain;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

interface ReportingLeaseRemote {

    CompletableFuture<LeaseAcknowledgement> create();

    CompletableFuture<LeaseAcknowledgement> renew(String leaseId);

    CompletableFuture<Void> stop(String leaseId);

    record LeaseAcknowledgement(
            String leaseId,
            Duration leaseTimeout,
            String channelStatus,
            String channelLastError) {
    }
}
