# Changelog

## 0.1.4

- Two frames are compared by the game's own timer. A frame that is long because the game ticks long is no longer taken for long one frame early. The timer is counted in every frame, also in one in which another mod has switched the level render off (a map screen).
- A pause is still one second of real time, at any tick rate: a frame that begins more than a second after the one before it is not paired with it.
- The view that gets motion vectors is the level render the game itself makes in a frame. A second view drawn before, after or inside it (another camera, a portal, a mirror, a panorama) gets none and no longer takes them from the player's view. A mod that makes the game's own call twice in a frame makes two such renders, and the first is followed, as before. In a frame in which the game's own call is not made, the level render that starts first is followed, with one warning; the first such frame after frames with the game's own call gets no motion vectors, and the first frame after each change has none either.
- In the frame after a long one, a new thing is no longer taken for an old one several blocks away.
- A single draw of an item on a block entity (a belt, a campfire) is told apart by its stack: an equal stack in another object, or a stack of another item, in the place of one that had stayed the same is no longer taken for it up to 4 blocks away. A stack of the same item with other content is taken for the same stack changed. A block entity that reads its stacks again while one of them moves abruptly loses the motion vector of that item for that frame. What an entity holds is not told apart by its stack, as in 0.1.3.
- Draws a renderer makes after the compute programs have been given its earlier ones are another compute cycle of the frame; the rule for a renderer that draws once holds for each cycle, and a later cycle takes nothing back. The warning about a motion vector that "could not be taken back", and the restriction that came with it, remain for the one case in which they are true: a new thing drawn at an earlier point of the frame than the old one, within 4 blocks of it. The warning now reads "... turned out to be that draw ...". A new information line names, once, a renderer that draws in more than one compute cycle of a frame.
- The lines that say the mod is active, or why it is inactive, are logged once, by the render thread. A new line, logged once when a world is entered, names the view that gets motion vectors and says how frames are compared.

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
