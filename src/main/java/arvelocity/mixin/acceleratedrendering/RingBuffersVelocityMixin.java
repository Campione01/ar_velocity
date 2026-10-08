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

import arvelocity.VelocityAttribute;
import arvelocity.VelocityDeltas;
import arvelocity.VelocityPrograms;
import arvelocity.VelocityRing;
import arvelocity.VelocitySidecar;
import arvelocity.VelocityWriter;
import com.github.argon4w.acceleratedrendering.core.backends.VertexArray;
import com.github.argon4w.acceleratedrendering.core.backends.buffers.MappedBuffer;
import com.github.argon4w.acceleratedrendering.core.backends.buffers.MutableBuffer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.LayerKey;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.pools.StagingBufferPool;
import com.github.argon4w.acceleratedrendering.core.buffers.environments.IBufferEnvironment;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ProgramOverride;
import org.lwjgl.opengl.GL44C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Gives a ring buffer set that holds Iris entity vertices a velocity buffer next to its shared vertex
 * buffer: as many slots as the vertex buffer has vertices, written by the same compute dispatches, drawn
 * through the same vertex array, deleted together. All of it only in cycles that start while a shader pack
 * program reads velocities; in any other cycle the set is left as Accelerated Rendering runs it.
 */
@Mixin(value = AcceleratedRingBuffers.Buffers.class, remap = false)
public abstract class RingBuffersVelocityMixin implements VelocityRing {
    @Shadow
    @Final
    private IBufferEnvironment environment;

    @Shadow
    @Final
    private StagingBufferPool vertexBuffer;

    @Shadow
    @Final
    private VertexArray vertexArray;

    @Shadow
    @Final
    private Map<LayerKey, AcceleratedBufferBuilder> builders;

    @Shadow
    @Final
    private MappedBuffer sharingBuffer;

    @Unique
    private MutableBuffer arvelocity$velocities;

    // The current cycle: 0 = no builder has asked yet, 1 = its builders write velocities, 2 = they do not.
    @Unique
    private int arvelocity$cycle;

    // True when the compute dispatches of the current cycle write the velocity buffer.
    @Unique
    private boolean arvelocity$written;

    // The buffer that feeds attribute 9 of this set's vertex array; 0 while the attribute array is off.
    @Unique
    private int arvelocity$attached;

    // Which cycle of the set is being filled, and whether its entries can still be changed: they are in mapped
    // memory, which the compute programs read once they have been given the buffer.
    @Unique
    private int arvelocity$serial;

    @Unique
    private boolean arvelocity$sealed;

    @Override
    public boolean arvelocity$velocityCycle() {
        int state = this.arvelocity$cycle;
        if (state == 0) {
            this.arvelocity$cycle = state = VelocityAttribute.hasReaders() && VelocityPrograms.available() ? 1 : 2;
        }
        return state == 1;
    }

    @Override
    public boolean arvelocity$writesVelocities() {
        return this.arvelocity$written;
    }

    @Override
    public long arvelocity$entry(long address) {
        long offset = address - this.sharingBuffer.getAddress();
        return offset < 0L || offset > Integer.MAX_VALUE ? -1L : ((long) this.arvelocity$serial << 32) | offset;
    }

    @Override
    public boolean arvelocity$open(long entry) {
        return entry >= 0L && (int) (entry >>> 32) == this.arvelocity$serial && !this.arvelocity$sealed
                && (entry & 0xFFFFFFFFL) + VelocityDeltas.ENTRY_SIZE <= this.sharingBuffer.getPosition();
    }

    @Override
    public void arvelocity$withdraw(long entry) {
        if (this.arvelocity$open(entry)) {
            VelocityDeltas.clear(this.sharingBuffer.addressAt(entry & 0xFFFFFFFFL));
        }
    }

    // The end of a cycle: the builders are gone, the next ones ask again.
    @Inject(method = "reset", at = @At("TAIL"))
    private void arvelocity$endCycle(CallbackInfo ci) {
        this.arvelocity$serial = (this.arvelocity$serial + 1) & Integer.MAX_VALUE;
        this.arvelocity$sealed = false;
        this.arvelocity$cycle = 0;
        this.arvelocity$written = false;
    }

    // Called once per cycle, right after prepare() has given the shared vertex buffer its size for the cycle
    // and before the first compute dispatch that writes into it.
    @Inject(method = "bindTransformBuffers", at = @At("TAIL"))
    private void arvelocity$bindVelocities(CallbackInfo ci) {
        this.arvelocity$sealed = true;
        boolean writes = false;
        boolean otherPrograms = false;
        // Every builder of the cycle is asked here, before any of its vertices is dispatched: the answer of
        // one that has not been asked yet must not come after the buffer for it was left unbound.
        for (AcceleratedBufferBuilder builder : this.builders.values()) {
            if (builder instanceof VelocityWriter writer && writer.arvelocity$writesVelocities()) {
                writes = true;
                ProgramOverride override = builder.getProgramOverride();
                if (override.overrideId() == 0) {
                    // The pair of programs this mod has a velocity version of.
                    VelocityPrograms.pair(override);
                } else {
                    // Any other pair comes from elsewhere and knows nothing of velocities.
                    otherPrograms = true;
                }
            }
        }
        // Without the programs of this mod the vertices of the cycle go through those of Accelerated Rendering,
        // which do not look at the delta entries the builders have left.
        writes &= VelocityPrograms.available();
        this.arvelocity$written = writes;
        if (!writes) {
            return;
        }
        long bytes = VelocitySidecar.bytesFor(this.vertexBuffer.getBuffer().getSize());
        MutableBuffer velocities = this.arvelocity$velocities;
        if (velocities == null) {
            this.arvelocity$velocities = velocities = new MutableBuffer(bytes, GL44C.GL_DYNAMIC_STORAGE_BIT);
            VelocitySidecar.announceBuffer();
        } else {
            velocities.resizeTo(bytes);
        }
        if (otherPrograms) {
            // Programs that know nothing of velocities write some of the vertices; their slots, which are
            // reused from cycle to cycle, must read "no data".
            VelocitySidecar.clear(velocities.getBufferHandle());
        }
        VelocitySidecar.bindOutput(velocities.getBufferHandle());
    }

    // The vertex array is bound here for the draws that follow; the base vertex of every draw is an index
    // into the shared vertex buffer, and with it into the velocity buffer.
    @Inject(method = "bindDrawBuffers", at = @At("TAIL"))
    private void arvelocity$attachVelocities(CallbackInfo ci) {
        MutableBuffer velocities = this.arvelocity$velocities;
        if (velocities == null) {
            return;
        }
        int wanted = this.arvelocity$written && VelocitySidecar.isVelocityLayout(this.environment.getLayout()) ? velocities.getBufferHandle() : 0;
        boolean readers = VelocityAttribute.hasReaders();
        if (!readers) {
            // The last program that read velocities was deleted, possibly after this cycle began: what is
            // left may have something else at the location.
            wanted = 0;
        }
        // A resized buffer is a new buffer object, possibly under the name of an older one.
        if (wanted != this.arvelocity$attached || (wanted != 0 && velocities.isResized())) {
            VelocitySidecar.attach(((VertexArrayAccessor) this.vertexArray).arvelocity$vaoHandle(), wanted);
            this.arvelocity$attached = wanted;
            velocities.mark();
        }
        if (!readers) {
            // Nothing of this mod stays on the set until a program reads velocities again.
            velocities.delete();
            this.arvelocity$velocities = null;
        }
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void arvelocity$deleteVelocities(CallbackInfo ci) {
        if (this.arvelocity$velocities != null) {
            this.arvelocity$velocities.delete();
            this.arvelocity$velocities = null;
        }
        this.arvelocity$cycle = 0;
        this.arvelocity$written = false;
        this.arvelocity$attached = 0;
        // The mapping of the transform buffer is gone with it.
        this.arvelocity$serial = (this.arvelocity$serial + 1) & Integer.MAX_VALUE;
        this.arvelocity$sealed = true;
    }
}
