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

import arvelocity.VelocityContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Two things are asked of this method. The head and the returns say when a level render runs: a mod that
// draws a second view by calling what it wraps twice would be seen once by a wrapper outside its own, and the
// two views have to be told apart. The wrapper says how the method was got to, before anything in it runs:
// the last step of the game's own way to its level render (GameRendererFrameMixin).
@Mixin(LevelRenderer.class)
public class LevelRendererFrameMixin {
    @WrapMethod(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V")
    private void arvelocity$level(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                  Matrix4f frustumMatrix, Matrix4f projectionMatrix, Operation<Void> original) {
        int outside = VelocityContext.enter(VelocityContext.LEVEL);
        try {
            original.call(deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
        } finally {
            VelocityContext.leave(outside);
        }
    }

    // Everything this method draws is placed relative to the position of this camera.
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", at = @At("HEAD"))
    private void arvelocity$beginLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                       Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        Vec3 position = camera.getPosition();
        VelocityContext.beginLevel(position.x, position.y, position.z);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", at = @At("RETURN"))
    private void arvelocity$endLevel(CallbackInfo ci) {
        VelocityContext.endLevel();
    }
}
