package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class WaitResponse {

    private String action;
    private boolean tracked;
    private boolean proceed;
    private boolean released;
    private String result;
    @JsonProperty("interaction_id")
    private String interactionId;
    @JsonProperty("pause_point")
    private String pausePoint;
    @JsonProperty("content_kind")
    private String contentKind;
    @JsonProperty("resolved_at")
    private String resolvedAt;
    private String resolution;
    private JsonNode content;

    public WaitResponse() {
    }

    public WaitResponse(String action) {
        this.action = action;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
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

    public boolean isReleased() {
        return released;
    }

    public void setReleased(boolean released) {
        this.released = released;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getInteractionId() {
        return interactionId;
    }

    public void setInteractionId(String interactionId) {
        this.interactionId = interactionId;
    }

    public String getPausePoint() {
        return pausePoint;
    }

    public void setPausePoint(String pausePoint) {
        this.pausePoint = pausePoint;
    }

    public String getContentKind() {
        return contentKind;
    }

    public void setContentKind(String contentKind) {
        this.contentKind = contentKind;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public JsonNode getContent() {
        return content;
    }

    public void setContent(JsonNode content) {
        this.content = content;
    }

    public boolean hasApplicableBeforeContent(String expectedInteractionId) {
        return hasApplicableContent(expectedInteractionId, "before", "params")
                && content.isObject();
    }

    public boolean hasApplicableAfterContent(String expectedInteractionId) {
        return hasApplicableContent(expectedInteractionId, "after", "result");
    }

    private boolean hasApplicableContent(
            String expectedInteractionId,
            String expectedPausePoint,
            String expectedContentKind) {
        return tracked
                && proceed
                && released
                && "continued".equals(result)
                && expectedInteractionId != null
                && expectedInteractionId.equals(interactionId)
                && expectedPausePoint.equals(pausePoint)
                && expectedContentKind.equals(contentKind)
                && content != null;
    }
}
