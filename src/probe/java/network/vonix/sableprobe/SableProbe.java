package network.vonix.sableprobe;

import net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Negative control. Ships no fix of any kind — built from a separate source set that cannot reference the
 * guard mod.
 *
 * <p>It hands Lithium's real {@code ChunkAwareBlockCollisionSweeper} the real swept AABB from the incident —
 * an item entity's box expanded towards a motion of {@code (2.1967e7, 9.698e6, -1.6208e7)} — and measures
 * whether the sweep ever returns.
 *
 * <h2>Why the verdict is "did it return", and why a watchdog sets it</h2>
 *
 * <p>In this defect a single {@code hasNext()} call does not merely take a long time; it does not return. The
 * incident log shows the server thread inside {@code nextSection} across eleven ModernFix watchdog dumps,
 * 40,001 ms at the first and 413,414 ms at the eleventh, with an identical stack every time. A cooperative
 * ceiling checked between iterations therefore never gets a chance to fire — which is itself the finding.
 *
 * <p>So the walk runs on its own thread and a watchdog decides the verdict from wall time:
 * <ul>
 *   <li>no return within {@code -Dsableprobe.ceilingMs} (default 20000) → {@code VERDICT=UNBOUNDED};</li>
 *   <li>returned in under a second → {@code VERDICT=IMMEDIATE};</li>
 *   <li>returned but slowly → {@code VERDICT=SLOW}.</li>
 * </ul>
 *
 * <p>The hung thread is left to be killed by the JVM exit. Nothing is interrupted, and nothing else is
 * disturbed: the probe's only other action is halting the server once the verdict is written.
 *
 * <p>Halts the server afterwards so each run is disposable.
 */
@Mod(SableProbe.MOD_ID)
public final class SableProbe {

    public static final String MOD_ID = "sableprobe";

    private static final Logger LOG = LoggerFactory.getLogger("SableProbe");

    public SableProbe(final IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SableProbe::onServerStarted);
    }

    private static void onServerStarted(final ServerStartedEvent event) {
        if (!Boolean.getBoolean("sableprobe.run")) {
            return;
        }

        final ServerLevel level = event.getServer().overworld();
        final boolean controlOnly = Boolean.getBoolean("sableprobe.controlOnly");
        final long ceilingMs = Long.getLong("sableprobe.ceilingMs", 20_000L);

        if (!controlOnly) {
            // The incident's swept box, reproduced verbatim from the log line:
            //   AABB[1815.55, 67.0, -1.620864198436978E7] -> [2.1967152361004844E7, 9698766.05, -6001.80]
            final AABB incident = new AABB(
                    1815.5532449438017D, 67.0D, -1.620864198436978E7D,
                    2.1967152361004844E7D, 9698766.048670202D, -6001.80310362498D);

            LOG.info("SABLEPROBE-SWEEP incident box {}", describe(incident));
            report("incident", watch(level, incident, ceilingMs));
        }

        // Regression half: a legitimate entity step over loaded ground, expected to return in both runs.
        // Runs on the server thread on purpose. ServerChunkCache.getChunk blocks when called from any other
        // thread, so an off-thread control measures chunk-cache thread affinity rather than the guard — it
        // reports "unbounded" even on a server with no defect at all.
        final AABB normal = new AABB(8.0D, 64.0D, 8.0D, 8.6D, 65.8D, 8.6D)
                .expandTowards(0.4D, -0.08D, 0.3D);
        report("control", inline(level, normal));

        LOG.info("SABLEPROBE-SWEEP halting server");
        event.getServer().halt(false);
    }

    /**
     * Runs a legitimate sweep inline, on the calling (server) thread.
     *
     * <p>Bounded by a shape-count ceiling rather than a clock: a legitimate sweep over loaded ground yields
     * within a few shapes, so a high count means something is wrong and the loop should stop rather than hang
     * the gate.
     */
    private static SweepResult inline(final ServerLevel level, final AABB box) {
        final ChunkAwareBlockCollisionSweeper sweeper =
                new ChunkAwareBlockCollisionSweeper(level, null, box);

        final long start = System.nanoTime();
        final int ceiling = 100_000;
        int shapes = 0;
        boolean returned = false;

        while (true) {
            if (sweeper.hasNext()) {
                sweeper.next();
                shapes++;
                if (shapes >= ceiling) {
                    break;
                }
            } else {
                returned = true;
                break;
            }
        }

        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new SweepResult(shapes, elapsedMs, returned);
    }

    /**
     * Runs the real sweep on a separate thread and judges it by wall time.
     */
    private static SweepResult watch(final ServerLevel level, final AABB box, final long ceilingMs) {
        final Object done = new Object();
        final boolean[] finished = {false};
        final int[] shapes = {0};

        final Thread worker = new Thread(() -> {
            final ChunkAwareBlockCollisionSweeper sweeper =
                    new ChunkAwareBlockCollisionSweeper(level, null, box);
            int count = 0;
            // Deliberately unbounded: that is the defect. The watchdog, not this loop, ends the measurement.
            while (sweeper.hasNext()) {
                sweeper.next();
                count++;
            }
            synchronized (done) {
                shapes[0] = count;
                finished[0] = true;
                done.notifyAll();
            }
        }, "sableprobe-sweep");
        worker.setDaemon(true);

        final long start = System.nanoTime();
        worker.start();

        synchronized (done) {
            final long deadline = start + ceilingMs * 1_000_000L;
            while (!finished[0]) {
                final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0L) {
                    break;
                }
                try {
                    done.wait(remainingMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        synchronized (done) {
            return new SweepResult(shapes[0], elapsedMs, finished[0]);
        }
    }

    private static void report(final String label, final SweepResult result) {
        if (!result.returned) {
            LOG.error("SABLEPROBE-SWEEP {} VERDICT=UNBOUNDED shapes={} elapsedMs={} (the sweep never returned)",
                    label, result.shapes, result.elapsedMs);
        } else if (result.elapsedMs < 1_000L) {
            LOG.info("SABLEPROBE-SWEEP {} VERDICT=IMMEDIATE shapes={} elapsedMs={}",
                    label, result.shapes, result.elapsedMs);
        } else {
            LOG.info("SABLEPROBE-SWEEP {} VERDICT=SLOW shapes={} elapsedMs={}",
                    label, result.shapes, result.elapsedMs);
        }
    }

    private static String describe(final AABB box) {
        return String.format("x=%.0f y=%.0f z=%.0f volume=%.3e",
                box.getXsize(), box.getYsize(), box.getZsize(),
                box.getXsize() * box.getYsize() * box.getZsize());
    }

    private record SweepResult(int shapes, long elapsedMs, boolean returned) {
    }
}
