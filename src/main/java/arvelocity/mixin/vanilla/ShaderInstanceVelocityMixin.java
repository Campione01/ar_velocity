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
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceVelocityMixin {
    @Shadow
    @Final
    private int programId;

    // The constructor has just bound the attribute names of the format, and this is every program the game
    // builds through this class: the vanilla ones, those of other mods and those Iris makes from a shader pack,
    // for whichever vertex format. at_velocity is in no format, so the linker would put it anywhere.
    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/ProgramManager;linkShader(Lcom/mojang/blaze3d/shaders/Shader;)V"))
    private void arvelocity$bindVelocity(ResourceProvider resourceProvider, ResourceLocation shaderLocation, VertexFormat vertexFormat, CallbackInfo ci) {
        VelocityAttribute.bindBeforeLink(this.programId, vertexFormat, shaderLocation);
    }

    @Unique
    private Object arvelocity$reader;

    // Binding the name does not make a program read it: one that does not declare the attribute, or does not
    // use it, links without it. The program itself is asked, once it is linked.
    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V", at = @At("RETURN"))
    private void arvelocity$countReader(ResourceProvider resourceProvider, ResourceLocation shaderLocation, VertexFormat vertexFormat, CallbackInfo ci) {
        this.arvelocity$reader = VelocityAttribute.linked(this, this.programId, shaderLocation);
    }

    // Iris deletes the programs of a shader pack through this method too, when the pack is switched or reloaded.
    @Inject(method = "close()V", at = @At("HEAD"))
    private void arvelocity$forgetReader(CallbackInfo ci) {
        Object reader = this.arvelocity$reader;
        if (reader != null) {
            this.arvelocity$reader = null;
            VelocityAttribute.closed(reader);
        }
    }
}
