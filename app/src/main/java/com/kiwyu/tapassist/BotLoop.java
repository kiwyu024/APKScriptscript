package com.kiwyu.tapassist;

/** A bot running on its own thread: the Gold Rush puzzle bot or the resource collector. */
public interface BotLoop {
    void start();
    void stop();
    boolean isRunning();
    Config config();
}
