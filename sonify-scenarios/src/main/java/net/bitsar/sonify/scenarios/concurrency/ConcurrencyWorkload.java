package net.bitsar.sonify.scenarios.concurrency;

import java.io.IOException;
import java.sql.SQLException;
import net.bitsar.sonify.scenarios.upstream.UpstreamWorkload;
import org.springframework.stereotype.Component;

/** Pathologies of the concurrency model itself, rather than of any particular dependency. */
@Component
public class ConcurrencyWorkload {

    /** One shared monitor on a path every request goes through. */
    private final Object hotPathLock = new Object();

    private final UpstreamWorkload upstream;

    private long guardedCounter;

    public ConcurrencyWorkload(UpstreamWorkload upstream) {
        this.upstream = upstream;
    }

    /**
     * Blocking I/O, which on a servlet container holds a platform thread for its whole duration.
     *
     * <p>Kept shorter than the slow-upstream scenario so the thread pool fills gradually and the
     * climb is visible rather than instantaneous.
     */
    public void blockingCall(int userId) throws IOException, InterruptedException {
        upstream.slowUpstreamShort(userId);
    }

    /**
     * A synchronized method on a shared object in the request path.
     *
     * <p>Harmless at low concurrency and a queue at high concurrency. Intrinsic locks are not
     * fair, so under load an unlucky thread can wait orders of magnitude longer than the average,
     * which is why the contention lane shows waits of seconds when the work itself takes
     * microseconds.
     */
    public void lockConvoy(int userId) {
        synchronized (hotPathLock) {
            // Small but real: enough work that the lock is genuinely held, not just touched.
            long accumulator = 0;
            for (int index = 1; index <= 60_000; index++) {
                accumulator += Math.floorMod(index * 2654435761L, 97);
            }
            guardedCounter += accumulator;
        }
    }

    public long guardedCounter() {
        return guardedCounter;
    }
}
