package net.bitsar.sonify.core;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.bitsar.sonify.core.event.SonifyEvent;
import net.bitsar.sonify.core.jfr.JfrEventPump;
import net.bitsar.sonify.core.metrics.AllocationSampler;
import net.bitsar.sonify.core.metrics.JvmVitals;
import net.bitsar.sonify.core.metrics.MetricsAccumulator;
import net.bitsar.sonify.core.metrics.TickAssembler;

/**
 * The entry point to JVM Sonify: hands out subscriptions and owns the measurement pipeline.
 *
 * <p>Nothing is measured until the first client subscribes, and the pipeline is torn down a grace
 * period after the last one leaves, so an application nobody is watching pays nothing at all.
 */
public final class SonifyBroker implements AutoCloseable {

    private static final Logger LOG = System.getLogger(SonifyBroker.class.getName());

    private final SonifyConfig config;
    private final List<Subscription> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong published = new AtomicLong();

    private MetricsAccumulator accumulator;
    private JfrEventPump pump;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;
    private ScheduledFuture<?> idleShutdownTask;
    private boolean running;
    private boolean jfrAvailable;

    public SonifyBroker(SonifyConfig config) {
        this.config = config;
    }

    public SonifyConfig config() {
        return config;
    }

    /**
     * Registers a client, starting the pipeline if this is the first one.
     *
     * @throws SonifyCapacityException if {@code sonify.max-clients} is already reached
     */
    public synchronized Subscription subscribe() {
        if (clients.size() >= config.maxClients()) {
            throw new SonifyCapacityException(config.maxClients());
        }
        if (idleShutdownTask != null) {
            idleShutdownTask.cancel(false);
            idleShutdownTask = null;
        }
        if (!running) {
            startPipeline();
        }
        Subscription subscription = new Subscription(this, config.bufferCapacity());
        clients.add(subscription);
        return subscription;
    }

    synchronized void unsubscribe(Subscription subscription) {
        if (!clients.remove(subscription) || !clients.isEmpty() || !running) {
            return;
        }
        idleShutdownTask = scheduler.schedule(this::stopIfStillIdle,
                config.gracePeriod().toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void stopIfStillIdle() {
        idleShutdownTask = null;
        if (clients.isEmpty()) {
            stopPipeline();
        }
    }

    private void startPipeline() {
        accumulator = new MetricsAccumulator();
        pump = new JfrEventPump(config, accumulator, this::publish);
        jfrAvailable = pump.start();

        TickAssembler assembler = new TickAssembler(accumulator, new JvmVitals(),
                new AllocationSampler(), config.maxThreadSlices(), config.allocationWindow().toMillis());
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory());
        long period = config.tick().toMillis();
        tickTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                publish(assembler.assemble());
            } catch (Throwable ignored) {
                // A failed tick must not cancel the schedule.
            }
        }, period, period, TimeUnit.MILLISECONDS);
        running = true;
        LOG.log(Level.DEBUG, "JVM Sonify: pipeline started, jfrAvailable={0}", jfrAvailable);
    }

    private void stopPipeline() {
        running = false;
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        if (pump != null) {
            pump.close();
            pump = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        jfrAvailable = false;
        LOG.log(Level.DEBUG, "JVM Sonify: pipeline stopped, no clients connected");
    }

    private void publish(SonifyEvent event) {
        published.incrementAndGet();
        for (Subscription client : clients) {
            client.deliver(event);
        }
    }

    public SonifyStatus status() {
        long dropped = 0;
        for (Subscription client : clients) {
            dropped += client.dropped();
        }
        return new SonifyStatus(running, jfrAvailable, clients.size(), published.get(), dropped);
    }

    @Override
    public synchronized void close() {
        if (idleShutdownTask != null) {
            idleShutdownTask.cancel(false);
            idleShutdownTask = null;
        }
        clients.clear();
        if (running) {
            stopPipeline();
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "sonify-tick");
            thread.setDaemon(true);
            return thread;
        };
    }
}
