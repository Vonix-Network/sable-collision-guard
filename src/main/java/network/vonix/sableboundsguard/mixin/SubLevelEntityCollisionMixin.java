package network.vonix.sableboundsguard.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import network.vonix.sableboundsguard.support.BoundsGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;

/**
 * The primary guard, at the point where Sable's invalid collision data is consumed.
 *
 * <p>{@code SubLevelEntityCollision.collide} builds the region it will walk like this:
 * <ol>
 *   <li>take the entity's box swept from where it was to where it is going;</li>
 *   <li>inverse-transform it into the sub-level's frame at the sub-level's <em>previous</em> pose;</li>
 *   <li>inverse-transform it again at the sub-level's <em>current</em> pose;</li>
 *   <li>union the two;</li>
 *   <li>walk every block position in the union, four times over.</li>
 * </ol>
 *
 * <p>Steps 2 and 3 are the same entity in the same tick, seen from two frames. When those frames are far
 * apart the union spans the gap between them, and its size has nothing to do with the size of the ship or
 * the entity. Step 4 is where a region of millions of blocks is born.
 *
 * <p>Sable already refuses to <em>walk</em> such a region: it compares the union's volume against
 * 125,000,000 and logs {@code Enormous local sub-level collision bounds, quitting.} That line appears
 * twenty times in the report, ten on the server thread. It was not enough, for two reasons, and the second
 * one is the whole defect:
 *
 * <ol>
 *   <li>The ceiling is a volume, so a thin slab of 57,863,825 blocks passes straight through it and held a
 *       real tick for 207 seconds.</li>
 *   <li><b>When Sable's guard does fire it only skips its own block walk.</b> The {@code continue} leaves
 *       {@code collisionInfo.motion} equal to the entity's original motion, Sable's {@code EntityMixin}
 *       then calls vanilla {@code collide()} with exactly that value, and vanilla expands the entity box by
 *       it — producing the swept AABB that Lithium's sweeper then walks without a cap. Sable protected its
 *       own loop and handed the same unrepresentable number to code it does not own.</li>
 * </ol>
 *
 * <p>So the defect is a seam, not a size, and the guards below sit on the seam:
 * <ul>
 *   <li><b>Narrow the union</b> ({@code expandTo}) — keep the half describing where the sub-level actually
 *       is, so collision keeps working and only the sweep back in time is lost.</li>
 *   <li><b>Cap the region</b> ({@code betweenClosed}) — if an oversized region still arrives, walk nothing
 *       rather than walk it. Safety net, deliberately second.</li>
 *   <li><b>Contain the returned motion</b> ({@code CollisionInfo.motion}) — the guard that was missing. A
 *       pass that declines the work must also decline to publish the number that justified it.</li>
 * </ul>
 *
 * <p>Pinned to Sable {@code 2.0.6} (see {@code verifySablePin}). Nothing here edits a sub-level's pose,
 * bounds, blocks, or NBT, and nothing is written to the world.
 */
@Mixin(value = dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.class, remap = false)
public class SubLevelEntityCollisionMixin {

    /**
     * Narrows the pose union at the instruction that builds it.
     *
     * <p>{@code require = 0}: if Sable restructures this method the mod runs on the cap and the motion
     * guard instead of refusing to load.
     */
    @Redirect(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/companion/math/BoundingBox3d;expandTo(Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;Ldev/ryanhcode/sable/companion/math/BoundingBox3d;)Ldev/ryanhcode/sable/companion/math/BoundingBox3d;"
            ),
            require = 0,
            remap = false
    )
    private static BoundingBox3d sableboundsguard$narrowPoseUnion(final BoundingBox3d atLastPose,
                                                                 final BoundingBox3dc atCurrentPose,
                                                                 final BoundingBox3d dest) {
        return BoundsGuard.narrowedUnion(atLastPose, atCurrentPose, dest);
    }

    /**
     * Hard cap on the block region one collision pass may walk.
     *
     * <p>Required: without it the mod has no safety net and should fail at boot rather than appear to be
     * running.
     */
    @Redirect(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"
            ),
            require = 1,
            remap = false
    )
    private static Iterable<BlockPos> sableboundsguard$capRegion(final BlockPos min, final BlockPos max) {
        final long dx = Math.abs((long) max.getX() - (long) min.getX()) + 1L;
        final long dy = Math.abs((long) max.getY() - (long) min.getY()) + 1L;
        final long dz = Math.abs((long) max.getZ() - (long) min.getZ()) + 1L;

        if (!BoundsGuard.isOversizedRegion(dx, dy, dz)) {
            return BlockPos.betweenClosed(min, max);
        }

        BoundsGuard.reportOversizedSweep(dx, dy, dz, "sub-level local space");
        return Collections.emptyList();
    }

    /**
     * Contains the motion Sable returns when it declines to collide.
     *
     * <p>This is the guard that was absent upstream, and the one that actually breaks the freeze. Sable's
     * {@code continue} on an enormous region leaves {@code collisionInfo.motion} equal to the entity's
     * original motion; that value is then passed to vanilla collision, which expands the entity box by it.
     * Clamping it here means a declined pass results in the entity moving a physically possible distance
     * instead of millions of blocks — and the swept AABB Lithium walks is correspondingly finite.
     */
    @ModifyReturnValue(method = "collide", at = @At("RETURN"), require = 1, remap = false)
    private static SubLevelEntityCollision.CollisionInfo sableboundsguard$containMotion(
            final SubLevelEntityCollision.CollisionInfo info) {
        if (info == null || !BoundsGuard.isImpossibleMotion(info.motion)) {
            return info;
        }

        final Vec3 clamped = BoundsGuard.clampMotion(info.motion);
        BoundsGuard.reportClampedMotion(info.motion, clamped);
        info.motion = clamped;
        return info;
    }
}
