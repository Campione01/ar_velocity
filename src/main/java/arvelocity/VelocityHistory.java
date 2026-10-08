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

package arvelocity;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.joml.Matrix4f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * What one owner (an entity or a block entity) drew in the previous frame and in this one: for every renderer
 * object, its draws in the order they were made. A draw gets a motion vector when {@link #pair} finds the same
 * draw among those of the previous frame.
 *
 * A renderer object does not say which of its draws is which: a belt draws every item it carries with one
 * renderer, and Accelerated Rendering draws every item without a mesh of its own through one shared renderer,
 * seven times each. The place of a draw in the order is right as long as the number and the order of the draws
 * stay the same, and wrong for every draw behind one that was added or removed. So the place in the order is
 * only where the search starts. States are compared in world space (the translation of the pose is relative to
 * the camera, whose own movement is taken out) and against where each state was heading, so neither a turn of
 * the camera nor a steady movement of the owner makes one draw look like another. A state of a draw that was
 * not paired itself does not say where it was heading; where that leaves a draw between two states, or far from
 * the only one, it gets no motion vector in that frame, and the frame after tells.
 *
 * Most renderers draw once per frame for an owner: a model part, a bone. Such a draw is the draw of the frame
 * before unless it is impossibly far from it: {@link #MAX_JUMP}, and {@link #SINGLE_REACH} or
 * {@link #SINGLE_GROWTH} times as far as it went before, whichever is more. How far it goes in a frame says
 * nothing else against it: a limb that swings, a start from rest or a frame that took long must not cost it
 * its motion vector. Whether a renderer draws once is only known when the frame is over, so the first draw of
 * a renderer that drew once in the frame before is paired like that for the time being. When a second draw
 * follows, the first may as well have been a new one in front of the old: it is judged again like any other
 * draw, and where that goes against it the motion vector it was given is taken back
 * ({@link VelocityRing#arvelocity$withdraw}).
 *
 * That can be done until the compute programs are given the draws. What a renderer draws after that is another
 * compute cycle of the frame. Every draw is still looked for among all draws of the frame before, whichever
 * cycle they were made in: where a cycle ends may differ from frame to frame. Only the rule for the single
 * draw goes by cycles: the first draw of a cycle is the only draw of the same cycle of the frame before, for
 * the time being, and a second draw of that cycle takes it back, which it always can. A draw of a later cycle
 * cannot. When it turns out to be the one the state belonged to, the first draw of an earlier cycle was a new
 * thing drawn at an earlier point of the frame than the old one, and has a false motion vector in that frame;
 * its renderer is held to the rules for several draws from then on ({@link #refused}).
 *
 * A single draw that is too far for the rules among several draws is the same thing that went far, or another
 * thing in its stead: where it is does not tell. For an item the draw may say which stack it is made for. A
 * stack that was the same object in two frames running and is another one now is taken for another thing,
 * unless it is the stack that was there, changed ({@link Things}); a stack that is new in every frame says
 * nothing, and neither does a draw that names none, like that of a model part.
 *
 * Distances are per frame, and frames differ in length: a state is expected to go on as far as it went, or
 * as far as that comes to in a frame of this length ({@link #ratio}).
 */
public final class VelocityHistory {
    /** States of the previous frame that one draw is compared with, at most. */
    public static final int WINDOW = 32;

    /** Draws of one renderer for one owner that are followed in a frame; the ones behind them get no motion vector. */
    public static final int MAX_DRAWS = 1024;

    /**
     * Blocks. Below this, two candidates are not told apart: the state at a draw's place in the order is given
     * up only for one that explains the draw better by more than this; a state that has a displacement of its
     * own is not taken by a draw that would change that displacement by more than this and by more than twice
     * its length (a stop is once, a bounce twice), nor, where the displacement is stretched to the length of
     * the frame, by more than this and more than once its length; in a frame that is shorter than the one
     * before, both by more than this and more than twice what the displacement comes to in it; and a state
     * without one is not taken at once by a draw further from it than this.
     */
    public static final float AMBIGUITY = 0.5f;

    /** Blocks in one frame. Vanilla sends an entity move that long as a teleport; such a draw has no image history. */
    public static final float MAX_JUMP = 8.0f;

    /** Blocks. The only draw of a renderer is the only draw of the frame before when it is no further from it than this. */
    public static final float SINGLE_REACH = 4.0f;

    /** Or no further than this many times as far as it went in the frame before, in a frame of the same length. */
    public static final float SINGLE_GROWTH = 3.0f;

    /** The length of a frame over that of the frame before it is taken to lie between these. */
    public static final float MIN_RATIO = 0.125f;
    public static final float MAX_RATIO = 8.0f;

    /** Nanoseconds of real time. A frame that begins this long after the one before it is not paired with it. */
    public static final long PAUSE = 1_000_000_000L;

    /** Knows what the objects are that draws name as what they are made for. */
    public interface Things {
        /**
         * True when the object a draw names is what the object of the frame before has become: the same thing,
         * changed. Asked only for two different objects.
         */
        boolean same(Object before, Object now);
    }

    static final int NEVER = Integer.MIN_VALUE;

    /** The draw was paired with nothing: it has no displacement. */
    private static final int FRESH = 0;
    /**
     * The draw was paired with nothing, but it may be the draw of the state at its place in the order, which
     * said nothing about where it was heading: the displacement is what that would mean. Only a draw that
     * moves like that goes on like that, so the next frame tells.
     */
    private static final int UNCONFIRMED = 1;
    /** The draw was paired, and the displacement is how far it went. */
    private static final int TRACKED = 2;
    /**
     * The draw was paired as the only draw of its renderer, where the rules that hold among several draws would
     * not have paired it: the displacement is how far it went if it is that draw, and only the next single draw
     * goes by it (one that goes on like that is TRACKED). Among several draws the state says as little about
     * where it was heading as a FRESH one.
     */
    private static final int ALONE = 3;

    private static final class State {
        /** M * P of the draw. */
        final Matrix4f modelToView = new Matrix4f();
        // P of the draw: its 3x3 part and its translation (relative to the camera position of its frame).
        float a00, a01, a02, a10, a11, a12, a20, a21, a22;
        float x, y, z;
        // How far the origin went in the world since the state this one was compared with; zero while FRESH.
        float dx, dy, dz;
        int status;
        // Which compute cycle of its frame the draw was made in, counted from the first in which the renderer drew.
        int cycle;
        // While this is a state of the previous frame: a draw was paired with it; it was as the only draw of
        // its cycle, which still stands; and the draw whose UNCONFIRMED displacement was measured from it.
        boolean taken;
        boolean single;
        State guess;
    }

    private static final class Track {
        /** The frame the draws in {@link #current} were made in. */
        int frame = NEVER;
        State[] previous = new State[0];
        int previousCount;
        State[] current = new State[1];
        int currentCount;
        /** Index of the last state taken, minus the index of the draw that took it. */
        int shift;
        /** The compute cycle of the frame that is being drawn, and the index of its first draw. */
        int cycle;
        int start;
        /** Where the delta entry of the first draw of that cycle is, as long as it is known. */
        VelocityRing ring;
        long entry;
        /** The first draw of that cycle is paired only as the single draw of its cycle, with the state at this index. */
        boolean pending;
        int only;
        /** What the first draw of the frame was made for, and whether that was the same in the frame before it. */
        Object thing;
        boolean steady;
        /** A first draw that was paired like that turned out to be another thing when it could no longer be taken back: none is paired like that again. */
        boolean late;
    }

    // A history dies with its owner; this list only lets the sweep reach the histories of owners that are
    // still loaded but no longer drawn.
    private static final ArrayList<WeakReference<VelocityHistory>> ALL = new ArrayList<>();

    private static long examined;
    private static long cycles;
    private static long refused;

    private final Reference2ObjectOpenHashMap<Object, Track> tracks = new Reference2ObjectOpenHashMap<>();

    public VelocityHistory() {
        ALL.add(new WeakReference<>(this));
    }

    /**
     * The ratio {@link #pair} takes, from the length of the frame that is being drawn and of the one before it
     * (in any unit, the game's own time for one; not positive: not known) and from the real time in nanoseconds
     * since the frame before began: 0 after a pause, 1 when one of the two lengths is not known.
     */
    public static float ratio(long duration, long before, long real) {
        if (real > PAUSE) {
            return 0.0f;
        }
        if (duration <= 0L || before <= 0L) {
            return 1.0f;
        }
        return Math.min(Math.max((float) ((double) duration / (double) before), MIN_RATIO), MAX_RATIO);
    }

    /**
     * Records a draw of the renderer in the given frame and returns M * P of the same draw in the frame before
     * it, or null when the draw has no history: nothing of this renderer was drawn in that frame (a longer gap
     * counts as none), the draw is new, or what it would be paired with is not plausibly the same thing.
     *
     * What is returned for the first draw of a compute cycle may be taken back by the second: see {@link VelocityRing#arvelocity$withdraw}.
     *
     * @param modelToView M * P of the draw
     * @param pose        P of the draw
     * @param cameraDx    how far the camera position went since the previous frame (and Dy, Dz)
     * @param ratio       the length of this frame over that of the frame before it, see {@link #ratio}
     * @param ring        where the delta entry of the draw is written, or null when it cannot be taken back later
     * @param address     the address of that entry now
     * @param thing       what the draw is made for, when the draw says (the stack of an item), or null
     * @param things      what knows such objects, or null when every other object is another thing
     */
    public Matrix4f pair(Object renderer, int frame, Matrix4f modelToView, Matrix4f pose, float cameraDx, float cameraDy, float cameraDz,
                         float ratio, VelocityRing ring, long address, Object thing, Things things) {
        Track track = tracks.get(renderer);
        if (track == null) {
            tracks.put(renderer, track = new Track());
        }
        if (track.frame != frame) {
            begin(track, frame);
        } else if (track.ring != null && !track.ring.arvelocity$open(track.entry)) {
            // The compute programs have been given what the renderer drew so far: this draw begins another cycle of the frame.
            track.cycle++;
            track.start = track.currentCount;
            track.pending = false;
            track.ring = null;
            cycles++;
        }
        int index = track.currentCount;
        if (index >= MAX_DRAWS) {
            return null;
        }
        boolean first = index == track.start;
        if (index == track.start + 1 && track.pending) {
            withdraw(track);
        }
        if (first) {
            long entry = ring == null ? -1L : ring.arvelocity$entry(address);
            if (entry >= 0L) {
                track.ring = ring;
                track.entry = entry;
            }
        }
        boolean replaced = false;
        if (index == 0) {
            replaced = track.steady && thing != track.thing && !(thing != null && things != null && things.same(track.thing, thing));
            track.steady = thing != null && thing == track.thing;
            track.thing = thing;
        }
        State now = slot(track, index);
        track.currentCount = index + 1;
        now.modelToView.set(modelToView);
        now.a00 = pose.m00();
        now.a01 = pose.m01();
        now.a02 = pose.m02();
        now.a10 = pose.m10();
        now.a11 = pose.m11();
        now.a12 = pose.m12();
        now.a20 = pose.m20();
        now.a21 = pose.m21();
        now.a22 = pose.m22();
        now.x = pose.m30();
        now.y = pose.m31();
        now.z = pose.m32();
        now.dx = 0.0f;
        now.dy = 0.0f;
        now.dz = 0.0f;
        now.status = FRESH;
        now.cycle = track.cycle;
        now.taken = false;
        now.single = false;
        now.guess = null;

        int count = track.previousCount;
        if (count == 0 || !(ratio > 0.0f)) {
            return null;
        }
        float stretch = Math.min(Math.max(ratio, MIN_RATIO), MAX_RATIO);
        State[] previous = track.previous;
        State match = null;
        int matchIndex = -1;
        int expected = index + track.shift;
        boolean inOrder = expected >= 0 && expected < count;
        // A candidate is taken when its squared error is below this.
        float bound = Float.POSITIVE_INFINITY;
        int looked = 0;
        if (inOrder) {
            match = previous[expected];
            matchIndex = expected;
            float kept = (float) Math.sqrt(error(now, match, cameraDx, cameraDy, cameraDz, stretch)) - AMBIGUITY;
            bound = kept > 0.0f ? kept * kept : 0.0f;
            looked = 1;
        }
        int centre = expected < 0 ? 0 : Math.min(expected, count - 1);
        for (int step = inOrder ? 1 : 0; bound > 0.0f && looked < WINDOW; step++) {
            int after = centre + step;
            int before = centre - step;
            if (after >= count && before < 0) {
                break;
            }
            if (after < count) {
                float error = error(now, previous[after], cameraDx, cameraDy, cameraDz, stretch);
                looked++;
                if (error < bound) {
                    bound = error;
                    match = previous[after];
                    matchIndex = after;
                }
            }
            if (step > 0 && before >= 0 && looked < WINDOW) {
                float error = error(now, previous[before], cameraDx, cameraDy, cameraDz, stretch);
                looked++;
                if (error < bound) {
                    bound = error;
                    match = previous[before];
                    matchIndex = before;
                }
            }
        }
        examined += looked;
        if (match == null) {
            return null;
        }

        float wx = now.x - match.x + cameraDx;
        float wy = now.y - match.y + cameraDy;
        float wz = now.z - match.z + cameraDz;
        float jump = wx * wx + wy * wy + wz * wz;
        if (!(jump <= MAX_JUMP * MAX_JUMP)) {
            return null;
        }
        float px = wx - match.dx;
        float py = wy - match.dy;
        float pz = wz - match.dz;
        float change = px * px + py * py + pz * pz;
        // The same against a displacement that is longer by as much as the frame is.
        float qx = wx - match.dx * stretch;
        float qy = wy - match.dy * stretch;
        float qz = wz - match.dz * stretch;
        float stretched = qx * qx + qy * qy + qz * qz;
        float speed = match.dx * match.dx + match.dy * match.dy + match.dz * match.dz;
        boolean plausible;
        if (match.status == TRACKED) {
            // After a longer frame the displacement is that of the longer frame: twice as far is twice what is left of it in this one.
            float stride = speed * stretch * stretch;
            plausible = change <= Math.max(AMBIGUITY * AMBIGUITY, 4.0f * Math.min(speed, stride))
                    || stretched <= Math.max(AMBIGUITY * AMBIGUITY, stretch < 1.0f ? 4.0f * stride : stride);
        } else if (matchIndex == expected) {
            plausible = jump <= AMBIGUITY * AMBIGUITY || (match.status == UNCONFIRMED && Math.min(change, stretched) <= AMBIGUITY * AMBIGUITY);
        } else {
            // When the state at the draw's place does not say where it was heading either, the draw may
            // as well be the one of that place, having gone further: not decided in this frame.
            boolean open = inOrder && previous[expected].status != TRACKED;
            plausible = !open && jump + turned(now, match) <= AMBIGUITY * AMBIGUITY;
        }
        boolean alone = false;
        boolean single = false;
        // The guess of a draw that was not paired is not how far anything went.
        float went = match.status == UNCONFIRMED ? 0.0f : speed;
        if (!plausible && first && matchIndex == expected && !match.taken && match.cycle == track.cycle && only(previous, count, matchIndex)
                && track.ring != null && !track.late && !replaced
                && jump <= Math.max(SINGLE_REACH * SINGLE_REACH, SINGLE_GROWTH * SINGLE_GROWTH * went * stretch * stretch)) {
            // One draw in this cycle of the frame then and, so far, one now: the same draw. A second draw of this cycle takes this back.
            track.pending = true;
            track.only = matchIndex;
            // Unless it goes on as it went when it was paired like this the frame before: that is how it moves, then.
            alone = !(match.status == ALONE && Math.min(change, stretched) <= AMBIGUITY * AMBIGUITY);
            single = true;
            plausible = true;
        }
        if (!plausible) {
            if (matchIndex == expected && match.status != TRACKED && !match.taken && match.guess == null) {
                // Nothing explains the draw better than the state at its place, which does not say where it
                // was heading, and the draw is too far from it to be believed at once: a fast draw, or a new
                // one in its place. The next frame tells: only a draw that is this fast goes on like this.
                now.dx = wx;
                now.dy = wy;
                now.dz = wz;
                now.status = UNCONFIRMED;
                match.guess = now;
            }
            return null;
        }
        if (match.single && !track.late) {
            // The state goes on in this draw, and the first draw of an earlier cycle has it as the only draw of
            // that cycle: that one was a new thing, and its vertices are with the compute programs.
            track.late = true;
            refused++;
        }
        match.taken = true;
        match.single |= single;
        State guessed = match.guess;
        if (guessed != null) {
            // The state went on in this draw, so the draw that might have been its draw is a new one.
            guessed.dx = 0.0f;
            guessed.dy = 0.0f;
            guessed.dz = 0.0f;
            guessed.status = FRESH;
            match.guess = null;
        }
        now.dx = wx;
        now.dy = wy;
        now.dz = wz;
        now.status = alone ? ALONE : TRACKED;
        track.shift = matchIndex - index;
        return match.modelToView;
    }

    // The draws of a renderer in the given frame begin: those it made in the frame before are what they are paired with.
    private static void begin(Track track, int frame) {
        State[] spare = track.previous;
        track.previous = track.current;
        boolean follows = track.frame == frame - 1;
        track.previousCount = follows ? track.currentCount : 0;
        track.current = spare;
        track.currentCount = 0;
        track.shift = 0;
        track.frame = frame;
        track.cycle = 0;
        track.start = 0;
        track.pending = false;
        track.ring = null;
        if (!follows) {
            track.thing = null;
        }
    }

    /** The state at the index is that of the only draw the renderer made in its compute cycle. */
    private static boolean only(State[] states, int count, int index) {
        int cycle = states[index].cycle;
        return (index == 0 || states[index - 1].cycle != cycle) && (index + 1 >= count || states[index + 1].cycle != cycle);
    }

    // The first draw of the cycle was paired as the only draw of its cycle, against the rules every other
    // draw is held to, and now there is a second one: the first is left as those rules leave it. Its entry
    // is within reach, or the second draw would have begun another cycle.
    private static void withdraw(Track track) {
        State first = track.current[track.start];
        State only = track.previous[track.only];
        only.taken = false;
        only.single = false;
        if (only.status != TRACKED) {
            first.status = UNCONFIRMED;
            only.guess = first;
        } else {
            first.dx = 0.0f;
            first.dy = 0.0f;
            first.dz = 0.0f;
            first.status = FRESH;
        }
        track.ring.arvelocity$withdraw(track.entry);
        track.pending = false;
    }

    private static State slot(Track track, int index) {
        State[] current = track.current;
        if (index >= current.length) {
            track.current = current = Arrays.copyOf(current, Math.max(index + 1, current.length * 2));
        }
        State state = current[index];
        if (state == null) {
            current[index] = state = new State();
        }
        return state;
    }

    /**
     * How badly a state of the previous frame explains a draw, squared, in blocks: how far the origin of the
     * draw is from where the state was heading (from where it was, when it has no displacement; the better of
     * the two, when its displacement is a guess), plus how much the 3x3 part of the pose changed. Where it was
     * heading is as far on as it went, or further by as much as the frame is longer, whichever fits better.
     */
    private static float error(State now, State then, float cameraDx, float cameraDy, float cameraDz, float stretch) {
        float ex = now.x - then.x + cameraDx;
        float ey = now.y - then.y + cameraDy;
        float ez = now.z - then.z + cameraDz;
        float moved = ex * ex + ey * ey + ez * ez;
        if (then.status == TRACKED || then.status == UNCONFIRMED) {
            float ax = ex - then.dx;
            float ay = ey - then.dy;
            float az = ez - then.dz;
            float bx = ex - then.dx * stretch;
            float by = ey - then.dy * stretch;
            float bz = ez - then.dz * stretch;
            float led = Math.min(ax * ax + ay * ay + az * az, bx * bx + by * by + bz * bz);
            moved = then.status == TRACKED ? led : Math.min(moved, led);
        }
        return moved + turned(now, then);
    }

    /** How much the 3x3 part of the pose changed, squared: how far the points one unit from the origin went on top of the origin. */
    private static float turned(State now, State then) {
        float m00 = now.a00 - then.a00;
        float m01 = now.a01 - then.a01;
        float m02 = now.a02 - then.a02;
        float m10 = now.a10 - then.a10;
        float m11 = now.a11 - then.a11;
        float m12 = now.a12 - then.a12;
        float m20 = now.a20 - then.a20;
        float m21 = now.a21 - then.a21;
        float m22 = now.a22 - then.a22;
        return m00 * m00 + m01 * m01 + m02 * m02 + m10 * m10 + m11 * m11 + m12 * m12 + m20 * m20 + m21 * m21 + m22 * m22;
    }

    public int size() {
        return tracks.size();
    }

    /** States of previous frames that draws were compared with since the start, over all histories. */
    public static long examined() {
        return examined;
    }

    /** Times a renderer went on drawing for an owner in another compute cycle of a frame, since the start, over all histories. */
    public static long cycles() {
        return cycles;
    }

    /** First draws of a compute cycle whose motion vector turned out to be another draw's when it could no longer be taken back, since the start. */
    public static long refused() {
        return refused;
    }

    // Only the draws of the frame before the current one can be paired with, so a renderer that was not drawn
    // in it starts from nothing, exactly like a new one: dropping it changes nothing but the memory held.
    private void drop(int frame) {
        if (tracks.isEmpty()) {
            return;
        }
        tracks.values().removeIf(track -> frame - track.frame > 1);
        tracks.trim();
    }

    /** Drops every renderer entry that was not drawn in the frame before the given one, or in it. */
    public static void sweep(int frame) {
        int kept = 0;
        for (int i = 0; i < ALL.size(); i++) {
            WeakReference<VelocityHistory> reference = ALL.get(i);
            VelocityHistory history = reference.get();
            if (history != null) {
                history.drop(frame);
                ALL.set(kept++, reference);
            }
        }
        ALL.subList(kept, ALL.size()).clear();
    }

    public static int liveHistories() {
        return ALL.size();
    }
}
