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

package arvelocity.mixin.acceleratedrendering;

import arvelocity.VelocityShaders;
import arvelocity.VelocitySidecar;
import com.github.argon4w.acceleratedrendering.core.backends.programs.BarrierFlags;
import com.github.argon4w.acceleratedrendering.core.programs.LoadComputeShaderEvent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Every compute shader of Accelerated Rendering is registered through this event, as (program key, file), and
// build() hands the list to the loader that compiles them. The two programs of this mod are added to that
// list under keys of their own, in this mod's resource namespace: no key or file of Accelerated Rendering is
// replaced, and no resource pack order is involved.
@Mixin(value = LoadComputeShaderEvent.class, remap = false)
public abstract class LoadComputeShaderEventMixin {
    @Shadow
    public abstract void loadComputeShader(ResourceLocation key, ResourceLocation location, BarrierFlags... barrierFlags);

    // The list is an ImmutableMap.Builder: a key that is put in twice makes build() fail.
    @Unique
    private boolean arvelocity$added;

    @Inject(method = "build", at = @At("HEAD"))
    private void arvelocity$addVelocityPrograms(CallbackInfoReturnable<?> cir) {
        // The programs write the velocity buffer; without the sidecar nothing would bind one for them.
        if (this.arvelocity$added || !VelocitySidecar.supported()) {
            return;
        }
        this.arvelocity$added = true;
        this.loadComputeShader(VelocityShaders.TRANSFORM_KEY, VelocityShaders.TRANSFORM_FILE, BarrierFlags.SHADER_STORAGE);
        this.loadComputeShader(VelocityShaders.UPLOADING_KEY, VelocityShaders.UPLOADING_FILE, BarrierFlags.SHADER_STORAGE);
        // This is a thread that loads resources: the thread that draws says it (VelocitySidecar.announce).
        VelocitySidecar.programsListed();
    }
}
