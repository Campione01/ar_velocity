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
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.meshes.MeshUploadingProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.IUploadingOverride;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ProgramOverride;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MeshUploadingProgramDispatcher.class, remap = false)
public class MeshUploadingProgramDispatcherVelocityMixin {
    // The dense server meshes of a ring buffer set: one dispatch of a mesh uploading program for a mesh and
    // an override, over the uploads of every builder of the set that has that override.
    @WrapOperation(method = "dispatch(Ljava/util/Collection;Lcom/github/argon4w/acceleratedrendering/core/buffers/accelerated/AcceleratedRingBuffers$Buffers;)V",
            at = @At(value = "INVOKE", target = "Lcom/github/argon4w/acceleratedrendering/core/programs/overrides/ProgramOverride;uploading()Lcom/github/argon4w/acceleratedrendering/core/programs/overrides/IUploadingOverride;"))
    private IUploadingOverride arvelocity$useVelocityProgram(ProgramOverride override, Operation<IUploadingOverride> original,
                                                             @Local(argsOnly = true) AcceleratedRingBuffers.Buffers ringBuffer) {
        return VelocityPrograms.uploading(ringBuffer, override, original.call(override));
    }
}
