package net.bitsar.sonify.scenarios.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Formats invoice dates.
 *
 * <p>{@link SimpleDateFormat} is famously not thread safe, and sharing one across requests
 * produces corrupted dates and occasional exceptions. The fix below is the one every guide
 * recommends and every reviewer approves: guard the shared instance with a lock.
 *
 * <p>It is correct. It is also a queue. Every request in the application now serialises through
 * one monitor to do a few microseconds of work, and at low traffic that is invisible. At high
 * traffic the lock becomes the slowest thing in the request path, and because intrinsic locks
 * are not fair, one unlucky thread can wait seconds while others sail past it.
 *
 * <p>The real fix is a {@code DateTimeFormatter}, which is immutable and needs no lock at all.
 * That is the lesson: the bottleneck is not a bug anyone introduced, it is a correct solution
 * that stopped being appropriate once concurrency rose.
 */
@Service
public class InvoiceFormatter {

    private final SimpleDateFormat sharedFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /** Thread safe, and a convoy under load. */
    public String formatInvoiceDate(long orderId) {
        Date when = new Date(System.currentTimeMillis() - ThreadLocalRandom.current().nextInt(100_000));
        synchronized (sharedFormat) {
            return sharedFormat.format(when) + "-" + orderId;
        }
    }
}
