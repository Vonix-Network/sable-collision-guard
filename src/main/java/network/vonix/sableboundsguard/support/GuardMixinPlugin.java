package network.vonix.sableboundsguard.support;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides which of this mod's mixins may apply, from what is actually on the classpath.
 *
 * <p>Both guards target classes this mod does not ship. Sable and Lithium are provided by the pack, and a
 * mixin whose target class cannot be resolved aborts the whole configuration. This plugin gates each entry
 * on the target being resolvable, so a pack that drops Lithium gets the Sable-side guard instead of a failed
 * boot.
 *
 * <p>Resolution deliberately uses the <em>class resource</em> rather than {@code Class.forName}. Loading the
 * target class from inside a mixin plugin runs a recursive transformation during mixin preparation, which is
 * not a supported position; reading the resource only asks whether the class is present on the path.
 *
 * <p>Every decision is logged once, because a gate that silently returns false is indistinguishable from a
 * gate that works — which is exactly the failure this mod exists to remove.
 */
public final class GuardMixinPlugin implements IMixinConfigPlugin {

    private static final String LITHIUM_SWEEPER =
            "net.caffeinemc.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper";
    private static final String SABLE_COLLISION =
            "dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision";

    @Override
    public void onLoad(final String mixinPackage) {
        log("mixins.json loaded (package " + mixinPackage + ")");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith(".LithiumSweeperMixin")) {
            return decide("LithiumSweeperMixin", LITHIUM_SWEEPER);
        }
        if (mixinClassName.endsWith(".SubLevelEntityCollisionMixin")) {
            return decide("SubLevelEntityCollisionMixin", SABLE_COLLISION);
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass,
                         final String mixinClassName, final IMixinInfo mixinInfo) {
        log("applying " + simpleName(mixinClassName) + " into " + targetClassName);
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass,
                          final String mixinClassName, final IMixinInfo mixinInfo) {
        log("applied " + simpleName(mixinClassName) + " into " + targetClassName);
    }

    private static boolean decide(final String label, final String targetClassName) {
        final boolean present = isPresent(targetClassName);
        log(label + (present ? " ENABLED" : " SKIPPED") + " (target " + targetClassName + " "
                + (present ? "present" : "absent") + ")");
        return present;
    }

    private static boolean isPresent(final String className) {
        final String resource = className.replace('.', '/') + ".class";
        final ClassLoader loader = GuardMixinPlugin.class.getClassLoader();
        if (loader != null && loader.getResource(resource) != null) {
            return true;
        }
        return ClassLoader.getSystemResource(resource) != null;
    }

    private static String simpleName(final String mixinClassName) {
        final int dot = mixinClassName.lastIndexOf('.');
        return dot < 0 ? mixinClassName : mixinClassName.substring(dot + 1);
    }

    private static void log(final String message) {
        // System.out on purpose: the mixin plugin runs before the mod's logger is guaranteed to exist, and a
        // binding gate that produces no output cannot be verified.
        System.out.println("[sableboundsguard/plugin] " + message);
    }
}
