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
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererFrameMixin {
    // Minecraft.runTick calls this method once for every frame of the game, whether or not a level is drawn in
    // it; the level renders of a screenshot (panorama, huge) are made between two such calls. Before it ticks,
    // runTick has advanced the timer it hands over here: the game moves what it draws by that time, not by the
    // time at which this method is reached. It is counted in every frame: other mods switch the level render of
    // a frame off after the timer was advanced for it (a map screen), and the time of such frames has gone by.
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void arvelocity$beginGameFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VelocityContext.beginGameFrame(deltaTracker.getGameTimeDeltaTicks(), System.nanoTime(), renderLevel);
    }

    // The game's own way to the level render of a frame: this call, the method it calls, the call that
    // method makes and the method that calls (LevelRendererFrameMixin). The calls say where a level render
    // comes from, and the methods are wrapped whole so that this is known before anything else in them runs:
    // a second view that another mod draws from the head of one of them is then not taken for the game's own.
    @WrapOperation(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void arvelocity$ownGameLevel(GameRenderer renderer, DeltaTracker deltaTracker, Operation<Void> original) {
        int outside = VelocityContext.enter(VelocityContext.GAME_CALL);
        try {
            original.call(renderer, deltaTracker);
        } finally {
            VelocityContext.leave(outside);
        }
    }

    @WrapMethod(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    private void arvelocity$gameLevel(DeltaTracker deltaTracker, Operation<Void> original) {
        int outside = VelocityContext.enter(VelocityContext.GAME_LEVEL);
        try {
            original.call(deltaTracker);
        } finally {
            VelocityContext.leave(outside);
        }
    }

    @WrapOperation(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void arvelocity$ownLevel(LevelRenderer levelRenderer, DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                     LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, Operation<Void> original) {
        int outside = VelocityContext.enter(VelocityContext.LEVEL_CALL);
        try {
            original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
        } finally {
            VelocityContext.leave(outside);
        }
    }
}
