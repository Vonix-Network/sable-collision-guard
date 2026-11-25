package network.vonix.sableboundsguard.mixin;

import net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper;
import network.vonix.sableboundsguard.support.BoundsGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The safety net, underneath everything else.
 *
 * <p>This is the exact code that froze the server. {@code ChunkAwareBlockCollisionSweeper} takes a swept
 * AABB and iterates every chunk section intersecting it, with no volume limit and no section-count limit.
 * Given a box spanning 2.2e7 blocks on an axis that is millions of sections and millions of
 * {@code Level.getChunk} calls, and the tick never completes: 40,001 ms at the first ModernFix dump,
 * 413,414 ms at the eleventh, identical stack in every one.
 *
 * <p>It is deliberately <em>not</em> the primary fix. Capping here stops the freeze but gives the entity no
 * block collision for that pass, and does nothing about the invalid data still being published and still
 * being consumed by other code paths. The Sable-side guards are expected to make this unreachable. It
 * exists because "expected to be unreachable" is not "cannot happen", and because when it does happen the
 * failure mode without this is a hung server rather than one pass with collision skipped.
 *
 * <p>Attaches only when Lithium is present; the mixin config plugin decides that before this class is
 * applied, so no target resolution failure can occur on a Lithium-free pack.
 *
 * <p>The measurement is taken on the first {@code nextSection} call rather than in a constructor: the
 * sweeper has two constructors with different signatures, and an {@code @Inject} whose callback signature
 * matches only one of them would silently fail to apply — which is precisely the "guard that looks like it
 * works" failure this mod is meant to remove.
 */
@Mixin(value = ChunkAwareBlockCollisionSweeper.class, remap = false)
public abstract class LithiumSweeperMixin {

    @Shadow
    private int minX;
    @Shadow
    private int minY;
    @Shadow
    private int minZ;
    @Shadow
    private int maxX;
    @Shadow
    private int maxY;
    @Shadow
    private int maxZ;

    @Unique
    private byte sableboundsguard$state;

    /**
     * On the first section transition, decide once whether this sweep may proceed.
     *
     * <p>Returns false for an oversized sweep. That is the sweeper's own "no more sections to iterate"
     * signal, so the sweep ends through the existing control flow with no collisions found rather than
     * being short-circuited somewhere the {@code AbstractIterator} does not expect.
     */
    @Inject(method = "nextSection", at = @At("HEAD"), cancellable = true, require = 1)
    private void sableboundsguard$refuseOversizedSweep(final CallbackInfoReturnable<Boolean> cir) {
        if (this.sableboundsguard$state == 0) {
            final long dx = Math.abs((long) this.maxX - (long) this.minX) + 1L;
            final long dy = Math.abs((long) this.maxY - (long) this.minY) + 1L;
            final long dz = Math.abs((long) this.maxZ - (long) this.minZ) + 1L;

            if (BoundsGuard.isOversizedRegion(dx, dy, dz)) {
                this.sableboundsguard$state = 2;
                BoundsGuard.reportOversizedSweep(dx, dy, dz, "lithium chunk sweep");
            } else {
                this.sableboundsguard$state = 1;
            }
        }

        if (this.sableboundsguard$state == 2) {
            cir.setReturnValue(false);
        }
    }
}
