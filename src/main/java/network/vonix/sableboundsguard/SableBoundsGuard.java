package network.vonix.sableboundsguard;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import network.vonix.sableboundsguard.support.SelfCheck;

/**
 * Validates what Sable publishes for a sub-level before it becomes work for the rest of the server.
 *
 * <h2>The defect</h2>
 *
 * <p>A Sable sub-level's collision region is built by inverse-transforming the entity's swept box into the
 * sub-level's local frame twice — once at its previous pose, once at its current pose — and taking the
 * union. When those two poses are far apart the union spans the gap between them, and its size is unrelated
 * to the size of the ship or the entity.
 *
 * <p>Sable refuses to <em>walk</em> such a region: it compares the union's volume against 125,000,000 and
 * logs {@code Enormous local sub-level collision bounds, quitting.} Twenty of those lines appear in the
 * incident log, ten on the server thread. It was not enough, for two reasons:
 *
 * <ol>
 *   <li>The ceiling is high enough that a thin slab of 57,863,825 blocks passes through it. That case held a
 *       real server tick for 207 seconds.</li>
 *   <li>More importantly, <b>that guard only skips Sable's own block walk.</b> The motion it was about to
 *       collide is still returned unchanged. Sable's {@code EntityMixin} then calls vanilla collision with
 *       it, vanilla expands the entity box by that motion, and Lithium's {@code ChunkAwareBlockCollisionSweeper}
 *       — which has no cap of any kind — walks every chunk section in the result. Sable protected its loop
 *       and handed the same unrepresentable number to code it does not own.</li>
 * </ol>
 *
 * <p>Observed in the report: one item entity, one tick, 40,001 ms at the first ModernFix watchdog dump and
 * 413,414 ms at the eleventh, with an identical stack all eleven times.
 *
 * <h2>What this mod changes</h2>
 *
 * <p>Three guards, on the seam rather than at the symptom:
 * <ul>
 *   <li><b>Narrow the pose union</b> — keep the half describing where the sub-level actually is. Collision
 *       keeps working; only the sweep back in time is dropped, which is what protects against tunnelling
 *       through a fast rotation.</li>
 *   <li><b>Contain the returned motion</b> — a pass that declines the work must also decline to publish the
 *       number that justified it. This is the guard that was missing.</li>
 *   <li><b>Cap the sweep</b> — the safety net, under everything, where the freeze actually happened.</li>
 * </ul>
 *
 * <p>Nothing is deleted, teleported, or repaired. No sub-level's pose, bounds, blocks or NBT are touched,
 * the world is never written, and no entity is removed. A guard that fires costs one pass of collision, not
 * a player's progress.
 */
@Mod(SableBoundsGuard.MOD_ID)
public final class SableBoundsGuard {

    public static final String MOD_ID = "sableboundsguard";

    public SableBoundsGuard(final IEventBus modBus) {
        // Guards are mixins; the only runtime work is the opt-in binding self-check.
        SelfCheck.register(modBus);
    }
}
