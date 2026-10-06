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
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherOwnerMixin {
    // The whole method is wrapped, not its head and returns: other mods cancel this method from their own
    // head injections, and the owner has to be restored on those paths and on exceptions too.
    @WrapMethod(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void arvelocity$trackOwner(Entity entity, double x, double y, double z, float rotationYaw, float partialTicks, PoseStack poseStack,
                                       MultiBufferSource bufferSource, int packedLight, Operation<Void> original) {
        // While no program reads velocities nobody asks who is being drawn.
        if (!RenderSystem.isOnRenderThread() || !VelocityAttribute.hasReaders()) {
            original.call(entity, x, y, z, rotationYaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
        }
        VelocityContext.pushOwner(entity);
        try {
            original.call(entity, x, y, z, rotationYaw, partialTicks, poseStack, bufferSource, packedLight);
        } finally {
            VelocityContext.popOwner();
        }
    }
}
