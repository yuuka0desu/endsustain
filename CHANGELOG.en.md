# EndSustain 0.2.4

## Highlights

- Reworked the EndSustain Blade terminal-kill pipeline into a full normal-death simulation: targets now play their own death animations, drop their own loot tables, and trigger split/event chains naturally.
- Death events now fire normally while cancellation attempts from external mods are ignored for terminal-marked targets.
- Finale Endsustain death-tail loot now completes even when vanilla death animation removes the boss early.
- Some targets killed by the EndSustain Blade now drop multiple loot copies.
- Added the Void Breaker weapon: a trident-style straight-line projectile with no gravity.
- Version bumped to 0.2.4.

## EndSustain Blade

- Terminal settlement now fakes a real player kill: health is staged to a living value, then the full hurt/death chain runs.
- Terminal-marked entities have their health writes forced to zero, healing blocked, and death-entry cancellation ignored.
- Loot events remain live for terminal targets; external loot cancellation is ignored so drops always reach the world.
- The blade tooltip now documents multi-drop behavior for some targets.

## Finale Endsustain

- Bound the boss loot table (`endsustain:entities/finale`) so its exclusive rewards roll correctly.
- Death-tail finalization now runs even when the vanilla death animation removes the boss mid-tail.
- Client-side death-animation packets no longer start a second death tail (client-only short-circuit).
- Fixed presence-tracker false recovery after a normal death-tail kill.

## Miscellaneous

- Removed leftover debug and observation logging from the combat and boss code paths.

## Small Zhanjiang Companion

- The companion now uses its own baked geo model cache instead of sharing Finale Endsustain's model instance.
- Companion spawning now recovers from Curios sync windows, equip timing, and transient slot refreshes.
- Equip and unequip callbacks now create and remove the companion immediately when the guide slot changes.
- The companion entity now has a real tracked size so it stays visible to the client renderer.

## Rendering

- Split the companion geo resource from the boss geo resource to avoid pose leakage between shared bones.
- Added bone reset fallback for the companion renderer so sleep-state transforms do not survive into the follower model.

## Changelog from 0.2.1

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
