package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AfterCallResponse(
        @JsonProperty("interaction_id") String interactionId,
        String operation,
        Boolean tracked,
        Boolean proceed,
        @JsonProperty("wait_required") Boolean waitRequired,
        String lifecycle,
        String reason) {

    static AfterCallResponse failOpen(String reason) {
        return new AfterCallResponse(null, "failed", false, true, false, null, reason);
    }

    boolean shouldWait(String expectedInteractionId) {
        return Boolean.TRUE.equals(tracked)
                && Boolean.FALSE.equals(proceed)
                && Boolean.TRUE.equals(waitRequired)
                && expectedInteractionId != null
                && expectedInteractionId.equals(interactionId);
    }
}
