package com.ateagents.breakhub.probe;

public class AfterCallRequest {

    private String callId;
    private Object result;

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
