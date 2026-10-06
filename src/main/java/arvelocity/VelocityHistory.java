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
     * the frame, by more than this and more than once its length; and a state without one is not taken at once
     * by a draw further from it than this.
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

    /** Nanoseconds. A frame that begins this long after the one before it is not paired with it. */
    public static final long PAUSE = 1_000_000_000L;

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
        // While this is a state of the previous frame: a draw was paired with it; and the draw whose
        // UNCONFIRMED displacement was measured from it.
        boolean taken;
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
        /** Where the delta entry of the first draw of the frame is, while that draw is paired only as the single draw of its renderer. */
        VelocityRing pending;
        long pendingEntry;
        /** A delta entry of this renderer could not be taken back any more: its first draw is not paired like that again. */
        boolean late;
    }

    // A history dies with its owner; this list only lets the sweep reach the histories of owners that are
    // still loaded but no longer drawn.
    private static final ArrayList<WeakReference<VelocityHistory>> ALL = new ArrayList<>();

    private static long examined;
    private static long refused;

    private final Reference2ObjectOpenHashMap<Object, Track> tracks = new Reference2ObjectOpenHashMap<>();

    public VelocityHistory() {
        ALL.add(new WeakReference<>(this));
    }

    /**
     * The ratio {@link #pair} takes, from the length in nanoseconds of the frame that is being drawn and of the
     * one before it (not positive: not known): 1 when one of the two is not known, 0 for a frame after a pause.
     */
    public static float ratio(long duration, long before) {
        if (duration > PAUSE) {
            return 0.0f;
        }
        if (duration <= 0L || before <= 0L || before > PAUSE) {
            return 1.0f;
        }
        return Math.min(Math.max((float) ((double) duration / (double) before), MIN_RATIO), MAX_RATIO);
    }

    /**
     * Records a draw of the renderer in the given frame and returns M * P of the same draw in the frame before
     * it, or null when the draw has no history: nothing of this renderer was drawn in that frame (a longer gap
     * counts as none), the draw is new, or what it would be paired with is not plausibly the same thing.
     *
     * What is returned for the first draw of a frame may be taken back by the second: see {@link VelocityRing#arvelocity$withdraw}.
     *
     * @param modelToView M * P of the draw
     * @param pose        P of the draw
     * @param cameraDx    how far the camera position went since the previous frame (and Dy, Dz)
     * @param ratio       the length of this frame over that of the frame before it, see {@link #ratio}
     * @param ring        where the delta entry of the draw is written, or null when it cannot be taken back later
     * @param address     the address of that entry now
     */
    public Matrix4f pair(Object renderer, int frame, Matrix4f modelToView, Matrix4f pose, float cameraDx, float cameraDy, float cameraDz,
                         float ratio, VelocityRing ring, long address) {
        Track track = tracks.get(renderer);
        if (track == null) {
            tracks.put(renderer, track = new Track());
        }
        if (track.frame != frame) {
            State[] spare = track.previous;
            track.previous = track.current;
            track.previousCount = track.frame == frame - 1 ? track.currentCount : 0;
            track.current = spare;
            track.currentCount = 0;
            track.shift = 0;
            track.frame = frame;
            track.pending = null;
        }
        int index = track.currentCount;
        if (index >= MAX_DRAWS) {
            return null;
        }
        if (index == 1 && track.pending != null) {
            withdraw(track);
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
        now.taken = false;
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
            plausible = change <= Math.max(AMBIGUITY * AMBIGUITY, 4.0f * speed) || stretched <= Math.max(AMBIGUITY * AMBIGUITY, speed * stretch * stretch);
        } else if (matchIndex == expected) {
            plausible = jump <= AMBIGUITY * AMBIGUITY || (match.status == UNCONFIRMED && Math.min(change, stretched) <= AMBIGUITY * AMBIGUITY);
        } else {
            // When the state at the draw's place does not say where it was heading either, the draw may
            // as well be the one of that place, having gone further: not decided in this frame.
            boolean open = inOrder && previous[expected].status != TRACKED;
            plausible = !open && jump + turned(now, match) <= AMBIGUITY * AMBIGUITY;
        }
        boolean alone = false;
        // The guess of a draw that was not paired is not how far anything went.
        float went = match.status == UNCONFIRMED ? 0.0f : speed;
        if (!plausible && index == 0 && count == 1 && ring != null && !track.late
                && jump <= Math.max(SINGLE_REACH * SINGLE_REACH, SINGLE_GROWTH * SINGLE_GROWTH * went * stretch * stretch)) {
            // One draw then and, so far, one now: the same draw. A second draw of this frame takes this back.
            track.pending = ring;
            track.pendingEntry = ring.arvelocity$entry(address);
            // Unless it goes on as it went when it was paired like this the frame before: that is how it moves, then.
            alone = !(match.status == ALONE && Math.min(change, stretched) <= AMBIGUITY * AMBIGUITY);
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
        match.taken = true;
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

    // The first draw of the frame was paired as the only draw of its renderer, against the rules every other
    // draw is held to, and now there is a second one: the first is left as those rules leave it.
    private static void withdraw(Track track) {
        State first = track.current[0];
        State only = track.previous[0];
        only.taken = false;
        if (only.status != TRACKED) {
            first.status = UNCONFIRMED;
            only.guess = first;
        } else {
            first.dx = 0.0f;
            first.dy = 0.0f;
            first.dz = 0.0f;
            first.status = FRESH;
        }
        if (!track.pending.arvelocity$withdraw(track.pendingEntry)) {
            track.late = true;
            refused++;
        }
        track.pending = null;
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

    /** First draws whose motion vector could not be taken back any more when a second draw followed, since the start. */
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
