package com.ateagents.breakhub;

public final class BreakHubStop {

    private BreakHubStop() {
    }

    public static void main(String[] args) throws Exception {
        HubControl.requestStop(HubInstallation.stateDirectory());
    }
}
