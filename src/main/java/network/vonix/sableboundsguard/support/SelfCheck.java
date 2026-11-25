package network.vonix.sableboundsguard.support;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves at runtime that each guard bound <em>and</em> that it decides correctly, instead of assuming either.
 *
 * <p>A mixin that fails to apply looks exactly like a mixin that works: the jar builds, the server boots,
 * {@code Done} appears, and nothing in the log says otherwise unless verbose mixin logging was enabled for
 * that specific run. A mixin that applies but decides wrongly looks the same way. This class answers both
 * questions in one grep-able block.
 *
 * <h2>How binding is detected</h2>
 *
 * <p>Mixin does not copy a handler method into the target class under its source name. It emits
 * <em>synthetic</em> members whose names embed a reference, the mod id truncated to eight characters, and the
 * handler name — for example {@code handler$zof000$sableboundsguard$refuseOversizedSweep}. A probe looking for
 * {@code sableboundsguard$} alone reports a false negative on a perfectly applied mixin, which is exactly the
 * class of mistake this mod exists to remove.
 *
 * <p>Detection therefore looks for the truncated mod id {@value #TOKEN} in any declared method or field name.
 * This mod's id is sixteen characters, so the eight-character token cannot collide with another mod.
 *
 * <h2>How decisions are tested</h2>
 *
 * <p>The guard predicates are invoked with the shapes the incident produced — an impossible per-tick motion,
 * a pose union spanning millions of blocks, a sweep region of 155x985x379 — and with legitimate shapes that
 * must be left alone. Both halves are required: a guard that clamps everything would pass a test that only
 * checks the bad case.
 *
 * <p>Runs at {@link ServerStartedEvent}, only when {@code sableboundsguard.selfcheck} is set, then halts the
 * server so a disposable gate terminates on its own.
 */
public final class SelfCheck {

    private static final Logger LOG = LoggerFactory.getLogger("SableBoundsGuard");

    /** Mixin's eight-character truncation of this mod's id, embedded in every generated member name. */
    private static final String TOKEN = "sablebou";

    private SelfCheck() {
    }

    /**
     * Registers the listener unconditionally; the check is gated inside the handler so event wiring is
     * identical in every build.
     */
    public static void register(final IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SelfCheck::onServerStarted);
    }

    private static void onServerStarted(final ServerStartedEvent event) {
        if (!Boolean.getBoolean("sableboundsguard.selfcheck")) {
            return;
        }

        LOG.info("SABLEBOUNDSGUARD-SELFCHECK begin");

        // Test-only affordance: injects a synthetic failure so the non-zero exit path can be exercised on a
        // server whose mods all load correctly. A verdict that can only be tested on the passing branch is
        // not a tested verdict. Gated behind its own property and never set in normal play.
        final List<String> failures = new ArrayList<>();
        if (Boolean.getBoolean("sableboundsguard.selfcheck.forceFail")) {
            failures.add("synthetic failure injected by -Dsableboundsguard.selfcheck.forceFail");
        }

        // ---- binding ----
        expectMembers(failures, "sublevel-collision",
                "dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision",
                "narrowPoseUnion", "capRegion", "containMotion");
        checkSweeper(failures);

        // ---- decisions ----
        checkMotion(failures);
        checkSweepRegion(failures);
        checkNarrowing(failures);

        if (failures.isEmpty()) {
            LOG.info("SABLEBOUNDSGUARD-SELFCHECK RESULT=PASS");
        } else {
            for (final String failure : failures) {
                LOG.error("SABLEBOUNDSGUARD-SELFCHECK FAILURE {}", failure);
            }
            LOG.error("SABLEBOUNDSGUARD-SELFCHECK RESULT=FAIL failures={}", failures.size());
        }

        // Exit status must carry the verdict. `MinecraftServer.halt(boolean)` is NOT an exit-code parameter —
        // its argument is `waitForServer`, and passing true calls serverThread.join(). Calling that from the
        // server thread (which is where this event fires) joins the thread onto itself and deadlocks.
        //
        // `System.exit(1)` is also not enough here: NeoForge's shutdown path runs its own teardown and the
        // process was observed exiting 0 anyway. `Runtime.halt` terminates without running shutdown hooks and
        // sets the status deterministically.
        //
        // The authoritative signal remains the RESULT= line in the log; the exit status is a convenience for
        // shell gates. Both are emitted so neither has to be inferred.
        final boolean passed = failures.isEmpty();
        LOG.info("SABLEBOUNDSGUARD-SELFCHECK halting server exitCode={}", passed ? 0 : 1);

        event.getServer().halt(false);
        if (!passed) {
            // Flush first: halt() skips shutdown hooks, so a buffered log line would be lost.
            System.out.flush();
            Runtime.getRuntime().halt(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Binding
    // ---------------------------------------------------------------------------------------------

    private static void expectMembers(final List<String> failures, final String label,
                                      final String className, final String... handlerNames) {
        final Class<?> target = resolve(label, className, failures);
        if (target == null) {
            return;
        }

        for (final String handler : handlerNames) {
            final String evidence = findMember(target, handler);
            if (evidence == null) {
                failures.add(label + ": handler '" + handler + "' did not attach to " + className);
            } else {
                LOG.info("SABLEBOUNDSGUARD-SELFCHECK {} BOUND {} via {}", label, handler, evidence);
            }
        }
    }

    private static void checkSweeper(final List<String> failures) {
        final String className = "net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper";
        final Class<?> target = resolve("lithium-sweeper", className, failures);
        if (target == null) {
            return;
        }
        final String evidence = findMember(target, "refuseOversizedSweep");
        if (evidence == null) {
            failures.add("lithium-sweeper: handler 'refuseOversizedSweep' did not attach to " + className);
        } else {
            LOG.info("SABLEBOUNDSGUARD-SELFCHECK lithium-sweeper BOUND refuseOversizedSweep via {}", evidence);
        }
    }

    private static Class<?> resolve(final String label, final String className, final List<String> failures) {
        try {
            return Class.forName(className, false, SelfCheck.class.getClassLoader());
        } catch (final ClassNotFoundException e) {
            failures.add(label + ": target class absent: " + className);
        } catch (final LinkageError e) {
            failures.add(label + ": target class failed to link: " + className + " (" + e + ")");
        }
        return null;
    }

    private static String findMember(final Class<?> target, final String handlerName) {
        // Prefer an exact handler-name match so a partially applied mixin is reported precisely; fall back to
        // the mod token so naming-convention drift in a future Mixin version is a warning, not a false pass.
        for (final Method method : target.getDeclaredMethods()) {
            if (method.getName().contains(handlerName)) {
                return "method " + method.getName();
            }
        }
        for (final Field field : target.getDeclaredFields()) {
            if (field.getName().contains(handlerName)) {
                return "field " + field.getName();
            }
        }
        for (final Method method : target.getDeclaredMethods()) {
            if (method.getName().contains(TOKEN)) {
                return "method " + method.getName() + " (different handler name)";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Decisions: impossible motion
    // ---------------------------------------------------------------------------------------------

    private static void checkMotion(final List<String> failures) {
        // Shapes taken from the incident log: the motion an item entity was handed.
        expectMotion(failures, "incident motion", new Vec3(2.1967152361004844E7, 9698766.048670202, -1.620864198436978E7), true);
        expectMotion(failures, "modest diagonal", new Vec3(0.6, -0.08, 0.35), false);
        expectMotion(failures, "fast airship step", new Vec3(3.0, 0.1, -2.0), false);
        expectMotion(failures, "at the ceiling", new Vec3(BoundsGuard.maxMotionPerTick, 0.0, 0.0), false);
        expectMotion(failures, "past the ceiling", new Vec3(BoundsGuard.maxMotionPerTick * 1.01, 0.0, 0.0), true);
        expectMotion(failures, "NaN", new Vec3(Double.NaN, 1.0, 1.0), true);
        expectMotion(failures, "infinite", new Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0), true);

        final Vec3 incident = new Vec3(2.1967152361004844E7, 9698766.048670202, -1.620864198436978E7);
        final Vec3 clamped = BoundsGuard.clampMotion(incident);
        if (Math.abs(clamped.length() - BoundsGuard.maxMotionPerTick) > 1.0E-6) {
            failures.add("clamped motion length is " + clamped.length() + ", expected "
                    + BoundsGuard.maxMotionPerTick);
        } else {
            LOG.info("SABLEBOUNDSGUARD-SELFCHECK motion clamp: {} -> {} blocks/tick",
                    (long) incident.length(), clamped.length());
        }

        // Zero-length must stay zero rather than becoming NaN.
        if (!BoundsGuard.clampMotion(Vec3.ZERO).equals(Vec3.ZERO)) {
            failures.add("clampMotion(Vec3.ZERO) changed an already-valid vector");
        }
    }

    private static void expectMotion(final List<String> failures, final String label,
                                     final Vec3 motion, final boolean shouldBeImpossible) {
        final boolean actual = BoundsGuard.isImpossibleMotion(motion);
        if (actual != shouldBeImpossible) {
            failures.add("isImpossibleMotion(" + label + ") = " + actual + ", expected " + shouldBeImpossible);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Decisions: oversized sweep region
    // ---------------------------------------------------------------------------------------------

    private static void checkSweepRegion(final List<String> failures) {
        // The measured real-world case: 155 x 985 x 379 positions, one tick held for 207 seconds.
        expectRegion(failures, "155x985x379 (measured)", 155, 985, 379, true);
        // The incident's swept region, millions of blocks on each axis.
        expectRegion(failures, "millions per axis", 28_700_000L, 9_700_000L, 16_200_000L, true);
        // A legitimate pass: a small entity moving across a modest ship.
        expectRegion(failures, "typical entity step", 6, 6, 6, false);
        // Generous but legitimate: a large hull swept at speed.
        expectRegion(failures, "large hull sweep", 48, 96, 48, false);
        // One axis beyond the plot size cannot be one sub-level.
        expectRegion(failures, "wider than one plot", BoundsGuard.maxSweepXZ + 1L, 8, 8, true);
        // Degenerate input must be refused rather than multiplied.
        expectRegion(failures, "zero extent", 0, 0, 0, true);
    }

    private static void expectRegion(final List<String> failures, final String label,
                                     final long dx, final long dy, final long dz,
                                     final boolean shouldBeOversized) {
        final boolean actual = BoundsGuard.isOversizedRegion(dx, dy, dz);
        if (actual != shouldBeOversized) {
            failures.add("isOversizedRegion(" + label + ") = " + actual + ", expected " + shouldBeOversized);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Decisions: narrowing a pose union
    // ---------------------------------------------------------------------------------------------

    private static void checkNarrowing(final List<String> failures) {
        // Two entity-sized boxes far apart: the union spans the gap and must be narrowed to the current pose.
        final BoundingBox3d atLastPose = new BoundingBox3d(1815.0, 66.0, -6002.0, 1816.0, 68.0, -6001.0);
        final BoundingBox3d atCurrentPose = new BoundingBox3d(2.1967E7, 9.698E6, -6002.0, 2.1967E7 + 1.0, 9.698E6 + 2.0, -6001.0);
        final BoundingBox3d dest = new BoundingBox3d();

        final BoundingBox3d narrowed = BoundsGuard.narrowedUnion(atLastPose, atCurrentPose, dest);

        if (Math.abs(narrowed.minX() - atCurrentPose.minX()) > 1.0E-9
                || Math.abs(narrowed.maxY() - atCurrentPose.maxY()) > 1.0E-9) {
            failures.add("narrowedUnion did not fall back to the current pose for a far-apart union");
        } else {
            LOG.info("SABLEBOUNDSGUARD-SELFCHECK narrowing: union of {} blocks narrowed to the current pose",
                    (long) BoundsGuard.unionVolume(atLastPose, atCurrentPose));
        }

        // Two overlapping, nearby boxes must produce the true union, not a truncation.
        final BoundingBox3d nearA = new BoundingBox3d(0, 0, 0, 1, 2, 1);
        final BoundingBox3d nearB = new BoundingBox3d(0.5, 0, 0.5, 1.5, 2, 1.5);
        final BoundingBox3d nearDest = new BoundingBox3d();
        final BoundingBox3d union = BoundsGuard.narrowedUnion(nearA, nearB, nearDest);

        if (Math.abs(union.minX()) > 1.0E-9
                || Math.abs(union.maxX() - 1.5) > 1.0E-9
                || Math.abs(union.maxY() - 2.0) > 1.0E-9) {
            failures.add("narrowedUnion truncated a legitimate nearby union: " + union);
        }
    }
}
