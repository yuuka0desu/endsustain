# EndSustain 0.2.1

## Highlights

- Improved Finale Endsustain death-tail reliability across dimensions and damage systems.
- Added server-side presence tracking to recover the death tail when the boss disappears unexpectedly.
- Added compatibility coverage for damage paths that bypass the normal `LivingHurtEvent`, including Revelation Cage damage.
- Added a three-dimensional spherical instant-kill field with a 3-block radius around tail-kill star arrows.
- Unified player instant-kill handling with the EndSustain Blade terminal-death pipeline.

## Combat and Death Handling

- Tail-kill star arrows now fire immediately and track their target.
- Tail-kill detection no longer depends exclusively on a single damage event.
- Server ticks compare the previous and current Finale Endsustain presence state.
- Unexpected disappearance recovery ignores unloaded chunks, unloaded dimensions, intentional removal, dimension changes, and explosive cleanup.
- Players inside the star-arrow kill sphere bypass invulnerability frames and enter the normal terminal death transaction.
- Added a fallback death transaction for external revival effects that interrupt player death, preventing zero-health ghost states in every dimension.

## Sleep and Social Behavior

- Sleep remains neutral and invulnerable while Qun U summons attack nearby players.
- Qun U summons are not cleared when the boss wakes.
- Killing Qun U grants the player a sleep damage-bonus stack and removes the sleep invulnerability gate.
- Player attacks wake Finale Endsustain immediately and restore hostile targeting.
- Added explicit animation-controller resets and direct bone restoration when leaving sleep.

## Environment and Rendering

- Purple rain is now driven by the Finale Endsustain presence tracker.
- Purple rain is forcibly disabled when the boss disappears and re-enabled when the boss appears.
- Added dedicated item-frame transforms for the Zhajiang Doll and Small Zhajiang item models.
- Restored awake eye, eyelid, and head bone transforms after the sleep animation.

## EndSustain Blade

- Charged EndSustain Blade entities are returned to the owner when they exceed a 128-block horizontal distance.
- Blades are also recovered when their entity leaves the loaded area and stops ticking.
- Cross-dimension owner changes return the carried blade to the player's inventory.
- Recovery avoids duplicate returns during normal hits and normal return handling.

## Compatibility

- Improved compatibility with Goety Revelation, RevelationFix, Iron's Spellbooks, Curios, Champions, Cataclysm, and Yes Steve Model integrations.
