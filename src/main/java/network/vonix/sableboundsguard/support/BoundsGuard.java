package network.vonix.sableboundsguard.support;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limits used by every guard in this mod, plus rate-limited reporting.
 *
 * <h2>Why these limits are about extents, never about coordinates</h2>
 *
 * <p>A Sable sub-level's local space is not near the origin. The plot grid starts at {@code origin 10000}
 * in chunk units, so plot 0 occupies blocks {@code 20,480,000 .. 20,482,047}. Every legitimate sub-level
 * local coordinate is therefore around +/-2.0e7. A guard that reacts to large <em>coordinates</em> would
 * break every sub-level in the pack while catching nothing. The numbers that matter are always the
 * <em>extent</em> of a region and the <em>length</em> of a motion.
 *
 * <h2>Why the per-axis ceilings are provable rather than chosen</h2>
 *
 * <p>Three bounds follow from the geometry, not from taste:
 * <ul>
 *   <li>{@link #maxSweepXZ} — a plot is {@code 1 << logPlotSize} chunks on a side, 128 by default, so
 *       2048 blocks. A sub-level's own blocks cannot span more than one plot, and the swept region is the
 *       entity box plus the sub-level's blocks plus the motion. 2048 is therefore a ceiling, not a guess.</li>
 *   <li>{@link #maxSweepY} — a sub-level's blocks live in chunk sections, and the level is 384 blocks tall.
 *       512 leaves headroom for the entity and the fence margin without permitting anything unbounded.</li>
 *   <li>{@link #maxSweepVolume} — measured. 35,502 observed collision passes with sub-levels at rest never
 *       came within an order of magnitude of 262,144 blocks; in a broken session 120 of 120 passes exceeded
 *       it, at 1.1 to 2.3 million positions each. This is the binding limit in practice.</li>
 * </ul>
 *
 * <p>Sable's own ceiling is 125,000,000, which is demonstrably too high: a region of 57,863,825 blocks
 * passes straight through it and held a real server tick for 207 seconds.
 *
 * <p>The volume cap can be widened without a rebuild for a pack that finds a legitimate need:
 * {@code -Dsableboundsguard.maxSweepVolume=1048576}.
 */
public final class BoundsGuard {

    private static final Logger LOG = LoggerFactory.getLogger("SableBoundsGuard");

    /** Largest motion, in blocks per tick, any entity may be given. */
    public static final double maxMotionPerTick = readDouble("sableboundsguard.maxMotion", 64.0D);

    /** Largest block region a single collision sweep may walk. Measured; configurable. */
    public static final long maxSweepVolume = (long) readDouble("sableboundsguard.maxSweepVolume", 262_144.0D);

    /** Largest lateral extent of one swept region: one plot. Provable. */
    public static final int maxSweepXZ = 2048;

    /** Largest vertical extent of one swept region. Provable from level height. */
    public static final int maxSweepY = 512;

    private static long lastMotionLogAt = 0L;
    private static long lastSweepLogAt = 0L;
    private static long lastBoundsLogAt = 0L;

    private BoundsGuard() {
    }

    private static double readDouble(final String key, final double fallback) {
        final String raw = System.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            final double parsed = Double.parseDouble(raw.trim());
            return parsed > 0.0D && !Double.isNaN(parsed) ? parsed : fallback;
        } catch (final NumberFormatException e) {
            LOG.warn("[SableBoundsGuard] ignoring unparseable {}={}", key, raw);
            return fallback;
        }
    }

    /**
     * @return true when a motion vector is not a physically possible single-tick displacement.
     */
    public static boolean isImpossibleMotion(final Vec3 motion) {
        if (motion == null) {
            return false;
        }
        return !(motion.lengthSqr() <= maxMotionPerTick * maxMotionPerTick);
    }

    /**
     * Clamps a motion vector to {@link #maxMotionPerTick}, preserving direction.
     *
     * <p>A NaN or infinite vector has no direction to preserve, so it collapses to zero: an entity that
     * cannot be moved is preferable to one handed a motion of no defined size.
     */
    public static Vec3 clampMotion(final Vec3 motion) {
        if (motion == null) {
            return null;
        }
        final double lengthSqr = motion.lengthSqr();
        if (Double.isNaN(lengthSqr) || Double.isInfinite(lengthSqr)) {
            return Vec3.ZERO;
        }
        if (lengthSqr <= maxMotionPerTick * maxMotionPerTick) {
            return motion;
        }
        return motion.scale(maxMotionPerTick / Math.sqrt(lengthSqr));
    }

    /**
     * @return true when an axis-aligned block region is larger than one sweep may walk.
     *
     * <p>Tested per axis before the multiply so an absurd input cannot overflow into a small number.
     */
    public static boolean isOversizedRegion(final long dx, final long dy, final long dz) {
        if (dx <= 0L || dy <= 0L || dz <= 0L) {
            return true;
        }
        if (dx > maxSweepXZ || dz > maxSweepXZ || dy > maxSweepY) {
            return true;
        }
        if (dx > maxSweepVolume || dy > maxSweepVolume || dz > maxSweepVolume) {
            return true;
        }
        return dx * dy * dz > maxSweepVolume;
    }

    /**
     * Volume of the smallest box containing both inputs, computed without allocating.
     */
    public static double unionVolume(final BoundingBox3dc a, final BoundingBox3dc b) {
        final double dx = Math.max(a.maxX(), b.maxX()) - Math.min(a.minX(), b.minX());
        final double dy = Math.max(a.maxY(), b.maxY()) - Math.min(a.minY(), b.minY());
        final double dz = Math.max(a.maxZ(), b.maxZ()) - Math.min(a.minZ(), b.minZ());
        return dx * dy * dz;
    }

    public static void reportClampedMotion(final Vec3 original, final Vec3 applied) {
        final long now = System.currentTimeMillis();
        if (now - lastMotionLogAt < 5_000L) {
            return;
        }
        lastMotionLogAt = now;
        LOG.warn("[SableBoundsGuard] clamped an impossible entity motion of {} blocks/tick to {} blocks/tick."
                        + " Further reports suppressed for 5s.", original.length(), applied.length());
    }

    public static void reportOversizedSweep(final long dx, final long dy, final long dz, final String where) {
        final long now = System.currentTimeMillis();
        if (now - lastSweepLogAt < 5_000L) {
            return;
        }
        lastSweepLogAt = now;
        LOG.warn("[SableBoundsGuard] refused a collision region of {}x{}x{} blocks at {}. Without this cap the"
                + " sweep would have walked every chunk section in that region and frozen the tick."
                + " Further reports suppressed for 5s.", dx, dy, dz, where);
    }

    public static void reportNarrowedBounds(final double volume) {
        final long now = System.currentTimeMillis();
        if (now - lastBoundsLogAt < 5_000L) {
            return;
        }
        lastBoundsLogAt = now;
        LOG.warn("[SableBoundsGuard] narrowed a sub-level collision region of {} blocks to the sub-level's"
                + " current pose. Its two poses are implausibly far apart; collision still applies where it is,"
                + " the sweep back to where it was does not. Further reports suppressed for 5s.", (long) volume);
    }

    /**
     * Sets {@code dest} to the union of {@code a} and {@code b}, or to {@code b} alone when the union is
     * larger than one sweep may walk.
     *
     * <p>Only the union is ever oversized. Each half is about the size of the entity, because that is what
     * it is: the same entity box seen from two poses in one tick. Keeping the current-pose half preserves
     * collision against where the sub-level actually is; what is given up is the sweep back to where it
     * used to be, which is what stops an entity tunnelling through a fast rotation. Trading some tunnelling
     * for a frozen tick is a good trade; trading all collision for one is a worse one.
     */
    public static BoundingBox3d narrowedUnion(final BoundingBox3d a, final BoundingBox3dc b, final BoundingBox3d dest) {
        final double volume = unionVolume(a, b);

        // NaN lands here too and is left for Sable's own guard to refuse.
        if (!(volume > (double) maxSweepVolume)) {
            return a.expandTo(b, dest);
        }

        reportNarrowedBounds(volume);
        return dest.set(b);
    }
}
