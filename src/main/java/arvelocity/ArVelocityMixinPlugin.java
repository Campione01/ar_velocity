/*
 * ar_velocity: per-vertex motion vectors (at_velocity) for the compute path of Accelerated Rendering.
 * The motion vector math is adapted from the Iris velocity extension of Super Resolution,
 * Copyright (c) 2025-2026. 187J3X1-114514.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package arvelocity;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * The switch of the mod. Without Accelerated Rendering and Iris, or with -Darvelocity.disable=true, no mixin
 * is applied and nothing else of this mod ever runs. With a build of Accelerated Rendering this build of the
 * mod has not been verified with (VERIFIED_AR_VERSIONS), or when the installed Iris or Accelerated Rendering
 * lacks something this build refers to (VelocityLinkage), the mod is inert: Accelerated Rendering is left as it
 * is and no motion vector is written. The one mixin that still applies then needs neither mod: it keeps
 * at_velocity on its fixed attribute location, where nothing feeds it and shader packs read "no data".
 * -Darvelocity.anyArVersion=true lifts the version check and leaves the decision to VelocityLinkage.
 */
public class ArVelocityMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("ar_velocity");

    private static final String ATTRIBUTE_MIXIN = ".vanilla.ShaderInstanceVelocityMixin";

    private static final String AR_MOD_ID = "acceleratedrendering";

    /** The versions of Accelerated Rendering, as its mods.toml states them, that this build has been verified with. */
    public static final List<String> VERIFIED_AR_VERSIONS = List.of("1.0.14-1.21.1-alpha", "1.0.16-1.21.1-alpha");

    public static final String ANY_AR_VERSION_PROPERTY = "arvelocity.anyArVersion";

    private boolean apply;
    private boolean inert;

    @Override
    public void onLoad(String mixinPackage) {
        boolean disabled = Boolean.getBoolean("arvelocity.disable");
        boolean acceleratedRendering = isLoaded(AR_MOD_ID);
        boolean iris = isLoaded("iris");
        apply = !disabled && acceleratedRendering && iris;
        if (!apply) {
            LOGGER.info("ar_velocity: no mixin is applied (-Darvelocity.disable: {}, Accelerated Rendering present: {}, Iris present: {})",
                    disabled, acceleratedRendering, iris);
            return;
        }
        String version = installedVersion(AR_MOD_ID);
        if (version == null || !VERIFIED_AR_VERSIONS.contains(version)) {
            String installed = version == null ? "of a version that could not be read" : "version " + version;
            if (!Boolean.getBoolean(ANY_AR_VERSION_PROPERTY)) {
                inert = true;
                LOGGER.error("ar_velocity: inert, no motion vectors: the installed Accelerated Rendering is {}, and this build of ar_velocity is verified "
                        + "only with {}. Accelerated Rendering is left unchanged; only the binding of at_velocity to its attribute location stays. "
                        + "Start with -D{}=true to let the check of the classes this build uses decide instead", installed,
                        String.join(" and ", VERIFIED_AR_VERSIONS), ANY_AR_VERSION_PROPERTY);
                return;
            }
            LOGGER.warn("ar_velocity: the installed Accelerated Rendering is {}, not one this build is verified with ({}); -D{}=true lifts that check, "
                    + "and the check of the classes this build uses decides alone", installed, String.join(", ", VERIFIED_AR_VERSIONS), ANY_AR_VERSION_PROPERTY);
        }
        VelocityLinkage.Member missing = missingMember();
        inert = missing != null;
        if (inert) {
            LOGGER.error("ar_velocity: inert, no motion vectors: the installed {} does not have {} as this build of ar_velocity uses it. Accelerated Rendering "
                    + "is left unchanged; only the binding of at_velocity to its attribute location stays", missing.mod(), missing);
        } else {
            LOGGER.info("ar_velocity: applying mixins (Accelerated Rendering and Iris are present)");
        }
    }

    private static boolean isLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    /** The version the mod loader has read for the mod, or null when there is none to read. */
    private static String installedVersion(String modId) {
        try {
            for (IModInfo mod : LoadingModList.get().getModFileById(modId).getMods()) {
                if (modId.equals(mod.getModId())) {
                    return mod.getVersion().toString();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("ar_velocity: the version of {} could not be read", modId, e);
        }
        return null;
    }

    private static VelocityLinkage.Member missingMember() {
        try {
            IClassBytecodeProvider provider = MixinService.getService().getBytecodeProvider();
            return VelocityLinkage.firstMissing(name -> {
                try {
                    return provider.getClassNode(name);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            });
        } catch (Throwable t) {
            // Not being able to look is not the same as something missing: VelocityContext still catches a
            // linkage error when it happens.
            LOGGER.warn("ar_velocity: the classes of Iris and Accelerated Rendering could not be checked before use: {}", t.toString());
            return null;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return apply && (!inert || mixinClassName.endsWith(ATTRIBUTE_MIXIN));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
