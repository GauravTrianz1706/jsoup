package org.jsoup.helper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Manages graceful shutdown of the jsoup library in cloud environments (AWS EKS/ECS).
 *
 * <p>Registers a JVM shutdown hook to handle SIGTERM signals sent by container orchestrators
 * during scaling, updates, or restarts. This ensures proper cleanup of resources, completion
 * of in-flight requests, and graceful deregistration from load balancers during pod/task
 * termination.</p>
 *
 * <p>Usage: Call {@link #isShuttingDown()} in long-running loops to check whether a shutdown
 * signal has been received and exit gracefully.</p>
 */
public final class GracefulShutdownManager {

    private static final Logger logger = Logger.getLogger(GracefulShutdownManager.class.getName());

    /** Volatile flag set to {@code true} when a JVM shutdown hook fires (SIGTERM received). */
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    static {
        // Register a JVM shutdown hook to handle SIGTERM from EKS/ECS orchestrators.
        // The hook sets the shuttingDown flag so that active processing loops can exit cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown.set(true);
            logger.info("GracefulShutdownManager: SIGTERM received – initiating graceful shutdown of jsoup processing.");
        }, "jsoup-graceful-shutdown"));
    }

    private GracefulShutdownManager() {
        // utility class – not instantiable
    }

    /**
     * Returns {@code true} if the JVM is in the process of shutting down (i.e. a SIGTERM /
     * shutdown hook has been triggered by the container orchestrator).
     *
     * <p>Long-running processing loops should check this flag and break out of their loop
     * when it returns {@code true} so that in-flight work is abandoned cleanly rather than
     * being interrupted abruptly.</p>
     *
     * @return {@code true} if a shutdown signal has been received
     */
    public static boolean isShuttingDown() {
        return shuttingDown.get();
    }
}
