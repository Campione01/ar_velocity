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

package arvelocity.mixin.vanilla;

import arvelocity.VelocityAttribute;
import arvelocity.VelocityContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

// Only render(...) is wrapped: renderItem(...) draws shared dummy block entities (chest, shield, banner
// items), which belong to whoever is rendering the item.
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherOwnerMixin {
    @WrapMethod(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    private void arvelocity$trackOwner(BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                                       Operation<Void> original) {
        // While no program reads velocities nobody asks who is being drawn.
        if (!RenderSystem.isOnRenderThread() || !VelocityAttribute.hasReaders()) {
            original.call(blockEntity, partialTick, poseStack, bufferSource);
            return;
        }
        VelocityContext.pushOwner(blockEntity);
        try {
            original.call(blockEntity, partialTick, poseStack, bufferSource);
        } finally {
            VelocityContext.popOwner();
        }
    }
}
