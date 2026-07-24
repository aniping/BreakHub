package com.ateagents.breakhub.domain;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

@Component
public class CurrentSessionBootstrap implements SmartInitializingSingleton {

    private final CurrentSessionService sessions;
    private final PauseService pauses;

    public CurrentSessionBootstrap(CurrentSessionService sessions, PauseService pauses) {
        this.sessions = sessions;
        this.pauses = pauses;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sessions.initializeDefault();
        pauses.safeRelease(sessions.current().sessionId(), "product_restart");
    }
}
