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

import arvelocity.VelocityPrograms;
import arvelocity.VelocitySidecar;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.TransformProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ProgramOverride;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TransformProgramDispatcher.class, remap = false)
public class TransformProgramDispatcherVelocityMixin {
    // The two ways the vertices of a builder get to a vertex transform program: all that the builder holds
    // itself, and the sparse server meshes, one mesh buffer at a time. Both ask the builder for its programs.
    @WrapOperation(method = {"dispatch(Ljava/util/Collection;)V",
            "dispatch(Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/builders/AcceleratedBufferBuilder;Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/pools/StagingBufferPool$StagingBuffer;Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/pools/StagingBufferPool$StagingBuffer;JJJJ)I"},
            at = @At(value = "INVOKE", target = "Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/builders/AcceleratedBufferBuilder;getProgramOverride()Lcom/github/argon4w/acceleratedrendering/core/programs/overrides/ProgramOverride;"))
    private ProgramOverride arvelocity$useVelocityPrograms(AcceleratedBufferBuilder builder, Operation<ProgramOverride> original) {
        return VelocityPrograms.of(builder, original.call(builder));
    }

    // dispatch(Collection) is the last step of the compute phase of a ring buffer set that writes vertices:
    // the mesh uploading dispatches and the per-mesh transform dispatches come before it.
    @Inject(method = "dispatch(Ljava/util/Collection;)V", at = @At("TAIL"))
    private void arvelocity$restoreVelocityBinding(CallbackInfo ci) {
        VelocitySidecar.restoreOutput();
    }
}
