# Changelog

## 0.1.3

Replaces 0.1.1. Version 0.1.2 was not released; its changes are part of this version.

- Motion vectors are written only while a shader pack program really reads `at_velocity`. In every other case Accelerated Rendering runs its own programs, as it does without this mod.
- A renderer that draws once per frame for its owner (a model part, a bone) keeps its motion vector through a swing, a start from rest and a long frame: up to 4 blocks per frame, or three times as far as it went the frame before, whichever is more.
- The length of a frame is taken into account. After a long frame a draw is expected to be as much further on as the frame was longer, and a frame that begins more than one second after the one before it is not paired with it.
- When a second draw of the same renderer follows in a frame, the motion vector the first one was given is judged again and taken back if it does not hold.
- A shader program that was dropped without being closed no longer counts as a reader of `at_velocity` once it has been collected, and a program that failed to link is not counted at all.

## 0.1.1

- `at_velocity` is bound to vertex attribute 9 in every shader program whose vertex format leaves that location free, whatever Accelerated Rendering and Iris are doing. A program that gets no motion vectors reads (0, 0, 0) instead of an unrelated vertex array.
- A draw is paired with a draw of the previous frame per owner and renderer, compared in world space with the camera movement taken out. A pairing that is not plausible gives no motion vector for that frame instead of a false one.
- Only draws of exactly the previous frame are paired.
- The members of Iris and Accelerated Rendering that the mod uses are checked at start-up. If one is missing, the mod stays inert and logs one error instead of failing in the middle of a frame.

## 0.1.0

- First version: per-vertex motion vectors (`at_velocity`) for what Accelerated Rendering draws through its compute path with an Iris shader pack. No vertex format is changed: two modified compute shaders write the vectors into a buffer of their own, which is attached to vertex attribute 9.
- `-Darvelocity.disable=true` switches the mod off.
