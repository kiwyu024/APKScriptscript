package com.kiwyu.tapassist;

/** A bot running on its own thread (the full puzzle bot or the ads-only tester). */
public interface BotLoop {
    void start();
    void stop();
    boolean isRunning();
}
