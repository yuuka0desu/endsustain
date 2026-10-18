# Unreleased

## Terminal Kill Chain

- Added a death-axis resolver. The previous six neutralization layers all assumed
  an entity's real health lives in the vanilla health data item; entities that
  keep their death threshold in a separate object field (for example
  `CombatProgress.progress` on youkaishomecoming bosses) were never touched.
  The new layer probes candidate fields by writing zero and observing whether
  `isAlive()` flips, restoring the original value on a miss, so nothing is
  mutated speculatively.
- Hurt-gate bypass now runs before the pipeline damage in `terminate()`. Without
  this, targets that clamp incoming damage re-clamped `Float.MAX_VALUE` down to a
  small value and logged the attempt as illegal damage.
- Watchdog and retry paths now reapply the death-axis collapse every tick, giving
  the same sustained pressure the health-zeroing layers already had.
- `KillAgent` gained a virtual-method judge rule for no-arg boolean overrides, and
  `collectTargets` matches by UUID so a single kill cannot touch every entity of
  the same class.

## Boss Music

- Added a server-authoritative battle music system with four tracks mapped to boss
  phases (encounter / battle / ultimate / death tail).
- Track selection takes the highest-priority phase across all bosses and keeps a
  single global track, so multiple bosses never overlap.
- Music starts on the tick a boss joins the level and stops when it is fully
  removed. Range is limited to the same dimension, within the configured radius,
  and only while the boss's chunk is loaded.
- Client playback fades in and out per tick; missing audio files degrade silently
  instead of spamming the log.
- A `SoundManager.play` mixin suppresses other mods' MUSIC sources while ours is
  active, so competing boss themes cannot steal the channel.
- Client packet handling now goes through `ClientPacketGate`, which defers to
  `DistExecutor`, so adding client-bound packets no longer risks dedicated-server
  class loading failures.
- Audio assets are intentionally not committed (third-party material). Drop `.ogg`
  files into `src/main/resources/assets/endsustain/sounds/music/` locally.

# EndSustain 0.2.7

## Combat Fixes

- Fixed a data-item type corruption bug: terminal-zeroing now writes values by the accessor's declared serializer type (Float slots always receive Float 0). Slots polluted with Double values are repaired on the next tick instead of being re-polluted, eliminating the `ClassCastException: Double cannot be cast to Float` crashes with Fading Time (tutm) and Goety entities.
- Added neutralization layer 6 (death-animation entry): entities that only accept removal while their own death sequence is playing (e.g. tutm time manager's overridden `setRemoved`/`die`) are now put into that state via a generic boolean-setter matcher, so they run their normal death animation, advance `deathTime`, and are removed with proper loot.
- Hardened the watchdog: the timeout fallback starts the death animation before `remove`/`discard`, and each neutralization layer is exception-isolated so a failing layer can no longer crash the server tick.
- `getHealth`/`getMaxHealth` calls in the zeroing pipeline are now guarded against target overrides that throw.

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
