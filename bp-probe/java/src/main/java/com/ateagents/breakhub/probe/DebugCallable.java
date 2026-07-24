package com.ateagents.breakhub.probe;

@FunctionalInterface
public interface DebugCallable<T> {
    T call() throws Exception;
}
