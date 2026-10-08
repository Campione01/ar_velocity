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

import arvelocity.VelocityContext;
import arvelocity.VelocityDeltas;
import arvelocity.VelocityRing;
import arvelocity.VelocitySidecar;
import arvelocity.VelocityWriter;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AcceleratedBufferBuilder.class, remap = false)
public abstract class AcceleratedBufferBuilderVelocityMixin implements VelocityWriter {
    @Shadow
    @Final
    private AcceleratedRingBuffers.Buffers buffer;

    @Shadow
    @Final
    private VertexLayout layout;

    // 0 = not looked at yet, 1 = the vertices of this builder go through the velocity programs, 2 = they do not.
    // A builder is made for one cycle of its ring buffer set, so what the set says for the cycle holds for it.
    @Unique
    private int arvelocity$writes;

    @Override
    public boolean arvelocity$writesVelocities() {
        int state = this.arvelocity$writes;
        if (state == 0) {
            this.arvelocity$writes = state = VelocitySidecar.isVelocityLayout(this.layout) && this.buffer instanceof VelocityRing ring
                    && ring.arvelocity$velocityCycle() ? 1 : 2;
        }
        return state == 1;
    }

    // beginTransform is the only writer of a per-draw transform entry; its matrix is the pose P of the draw.
    // The velocity programs read the delta of a transform from the entry behind it, so every transform of a
    // builder they process gets that second entry, whether or not there is a delta to put into it.
    @Inject(method = "beginTransform", at = @At("TAIL"))
    private void arvelocity$appendDelta(Matrix4f transform, Matrix3f normal, CallbackInfo ci) {
        if (!this.arvelocity$writesVelocities()) {
            return;
        }
        long address = this.buffer.reserveSharing();
        this.buffer.getSharing();
        VelocityDeltas.clear(address);
        if (RenderSystem.isOnRenderThread()) {
            VelocityContext.writeDelta(address, transform, (VelocityRing) this.buffer);
        }
    }

    // doRender is where the renderer object (model part, bone, baked model) of a draw is known, and what it draws with.
    @Inject(method = "doRender", at = @At("HEAD"))
    private void arvelocity$enterRenderer(IAcceleratedRenderer<?> renderer, Object context, Matrix4f transform, Matrix3f normal, int light, int overlay,
                                          int color, CallbackInfo ci) {
        if (this.arvelocity$writesVelocities() && RenderSystem.isOnRenderThread()) {
            VelocityContext.pushRenderer(renderer, context);
        }
    }

    @Inject(method = "doRender", at = @At("RETURN"))
    private void arvelocity$leaveRenderer(CallbackInfo ci) {
        if (this.arvelocity$writesVelocities() && RenderSystem.isOnRenderThread()) {
            VelocityContext.popRenderer();
        }
    }
}
