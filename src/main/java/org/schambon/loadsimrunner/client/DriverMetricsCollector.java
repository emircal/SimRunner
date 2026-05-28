package org.schambon.loadsimrunner.client;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckOutStartedEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionPoolListener;

import java.util.concurrent.TimeUnit;

/**
 * Collects per-thread MongoDB driver timing metrics using ThreadLocal storage.
 *
 * Implements CommandListener to capture the round-trip command execution time
 * (BSON serialization + network send + server execution + network receive +
 * BSON deserialization), and ConnectionPoolListener to capture the time spent
 * waiting for a connection from the pool.
 *
 * Because mongodb-driver-sync is fully synchronous, all event callbacks fire on
 * the same thread that issued the database call, making ThreadLocal an exact and
 * lock-free correlation mechanism.
 *
 * Usage pattern:
 *   - Register a single instance as both a CommandListener and a
 *     ConnectionPoolListener on MongoClientSettings.
 *   - Share the same instance with the Reporter via
 *     reporter.setDriverMetricsCollector(collector).
 *   - Reporter.reportOp() reads and resets the thread-locals after every
 *     doRun() invocation, so accumulation across cursor getMore calls is
 *     handled automatically.
 */
public class DriverMetricsCollector implements CommandListener, ConnectionPoolListener {

    /**
     * Accumulated command elapsed time (nanoseconds) since the last reportOp() read.
     * Adds across multiple commands (e.g. find + getMore) within a single doRun().
     */
    private final ThreadLocal<Long> commandTimeNanos = ThreadLocal.withInitial(() -> 0L);

    /**
     * Accumulated connection pool checkout wait time (nanoseconds) since the last
     * reportOp() read.
     */
    private final ThreadLocal<Long> poolWaitNanos = ThreadLocal.withInitial(() -> 0L);

    /**
     * Transient: System.nanoTime() captured when pool checkout started on this thread.
     * Reset to 0 once the checkout completes or fails.
     */
    private final ThreadLocal<Long> poolCheckoutStartNanos = ThreadLocal.withInitial(() -> 0L);

    // -------------------------------------------------------------------------
    // CommandListener
    // -------------------------------------------------------------------------

    @Override
    public void commandStarted(CommandStartedEvent event) {
        // Elapsed time is not available here; handled in commandSucceeded/commandFailed.
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        commandTimeNanos.set(commandTimeNanos.get() + event.getElapsedTime(TimeUnit.NANOSECONDS));
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        // Still count time spent on failed commands — it was wall-clock time spent.
        commandTimeNanos.set(commandTimeNanos.get() + event.getElapsedTime(TimeUnit.NANOSECONDS));
    }

    // -------------------------------------------------------------------------
    // ConnectionPoolListener
    // -------------------------------------------------------------------------

    @Override
    public void connectionCheckOutStarted(ConnectionCheckOutStartedEvent event) {
        poolCheckoutStartNanos.set(System.nanoTime());
    }

    @Override
    public void connectionCheckedOut(ConnectionCheckedOutEvent event) {
        long start = poolCheckoutStartNanos.get();
        if (start > 0) {
            poolWaitNanos.set(poolWaitNanos.get() + (System.nanoTime() - start));
            poolCheckoutStartNanos.set(0L);
        }
    }

    @Override
    public void connectionCheckOutFailed(ConnectionCheckOutFailedEvent event) {
        long start = poolCheckoutStartNanos.get();
        if (start > 0) {
            poolWaitNanos.set(poolWaitNanos.get() + (System.nanoTime() - start));
            poolCheckoutStartNanos.set(0L);
        }
    }

    // -------------------------------------------------------------------------
    // Read / reset API (called by Reporter.reportOp() after each operation)
    // -------------------------------------------------------------------------

    /**
     * Returns the accumulated command elapsed time in milliseconds for the calling
     * thread since the last invocation, then resets the accumulator to zero.
     *
     * The value covers: BSON serialization + network send + server execution +
     * network receive + BSON deserialization for all commands issued in the
     * current doRun() call (including getMore commands for cursors).
     */
    public double getAndClearCommandTimeMs() {
        long nanos = commandTimeNanos.get();
        commandTimeNanos.set(0L);
        return nanos / 1_000_000.0;
    }

    /**
     * Returns the accumulated connection pool checkout wait time in milliseconds
     * for the calling thread since the last invocation, then resets the accumulator.
     */
    public double getAndClearPoolWaitMs() {
        long nanos = poolWaitNanos.get();
        poolWaitNanos.set(0L);
        return nanos / 1_000_000.0;
    }
}
