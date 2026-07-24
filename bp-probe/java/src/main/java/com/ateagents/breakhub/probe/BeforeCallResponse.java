package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.annotation.JsonProperty;

public class BeforeCallResponse {

    private boolean success;
    @JsonProperty("interaction_id")
    private String interactionId;
    private String operation;
    private boolean tracked;
    private boolean proceed = true;
    @JsonProperty("wait_required")
    private boolean waitRequired;
    private Integer callIndex;
    private String action;
    private String reason;
    private Long waitTimeoutMs;
    private String breakpointId;
    private String interfaceId;
    private String breakpointName;

    public BeforeCallResponse() {
    }

    public BeforeCallResponse(
            boolean success,
            Integer callIndex,
            String action,
            String reason,
            Long waitTimeoutMs,
            String breakpointId,
            String interfaceId,
            String breakpointName
    ) {
        this.success = success;
        this.callIndex = callIndex;
        this.action = action;
        this.reason = reason;
        this.waitTimeoutMs = waitTimeoutMs;
        this.breakpointId = breakpointId;
        this.interfaceId = interfaceId;
        this.breakpointName = breakpointName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getInteractionId() {
        return interactionId;
    }

    public void setInteractionId(String interactionId) {
        this.interactionId = interactionId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public boolean isTracked() {
        return tracked;
    }

    public void setTracked(boolean tracked) {
        this.tracked = tracked;
    }

    public boolean isProceed() {
        return proceed;
    }

    public void setProceed(boolean proceed) {
        this.proceed = proceed;
    }

    public boolean isWaitRequired() {
        return waitRequired;
    }

    public void setWaitRequired(boolean waitRequired) {
        this.waitRequired = waitRequired;
    }

    public boolean shouldWait(String expectedInteractionId) {
        return success
                && tracked
                && !proceed
                && waitRequired
                && expectedInteractionId != null
                && expectedInteractionId.equals(interactionId);
    }

    public Integer getCallIndex() {
        return callIndex;
    }

    public void setCallIndex(Integer callIndex) {
        this.callIndex = callIndex;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getWaitTimeoutMs() {
        return waitTimeoutMs;
    }

    public void setWaitTimeoutMs(Long waitTimeoutMs) {
        this.waitTimeoutMs = waitTimeoutMs;
    }

    public String getBreakpointId() {
        return breakpointId;
    }

    public void setBreakpointId(String breakpointId) {
        this.breakpointId = breakpointId;
    }

    public String getInterfaceId() {
        return interfaceId;
    }

    public void setInterfaceId(String interfaceId) {
        this.interfaceId = interfaceId;
    }

    public String getBreakpointName() {
        return breakpointName;
    }

    public void setBreakpointName(String breakpointName) {
        this.breakpointName = breakpointName;
    }
}
