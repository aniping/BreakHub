package com.ateagents.breakhub;

import java.nio.file.Path;

public final class BreakHubStop {

    private BreakHubStop() {
    }

    public static void main(String[] args) throws Exception {
        Path installation = HubInstallation.installationDirectory();
        HubControl.requestStop(HubInstallation.stateDirectory(installation));
    }
}
