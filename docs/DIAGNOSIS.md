# Diagnosis: the Otherworld sub-level collision freeze

This is the full write-up of the defect, the evidence it rests on, and the reasoning that located the
seam. It is written to be readable by someone who has never seen the pack — specifically so it can be
pointed at Sable's author when the upstream issue is filed.

- **Pack:** Raven's High Fantasy 0.3.1 (the pack behind the Otherworld server)
- **Environment:** Minecraft 1.21.1, NeoForge 21.1.265, Java 21.0.7, Windows 11, RX 6600
- **Log:** client singleplayer session, 2026-11-16, 136,846 lines
- **Relevant mods:** Sable 2.0.6, Lithium 0.15.4, C2ME 0.4.0-alpha, ModernFix 5.27.27, Neruina 3.3.5

## Verdict

A permanent single-tick freeze, not a crash. One item entity's move never returns; the server thread is
pinned inside Lithium's block collision sweeper, which is iterating an effectively unbounded region.

## Timeline

All times log-local, 2026-11-16.

| Time | Event |
|---|---|
| 23:11:34.047 | `New unregistered ship detected: id=559b8029-c14b-4527-b8ba-843a0125eed4 name=ship` (aeroclaims) |
| 23:11:34.297 | `Received a sub-level movement packet for a non-existent sub-level` |
| 23:11:34.560 | First `Aborting entity get for abnormally large AABB` |
| 23:11:39.810 | `Enormous local sub-level collision bounds, quitting.` — 10× Render thread, 10× Server thread |
| 23:12:05 | spark: `Timed out waiting for world statistics` |
| 23:12:19.784 | ModernFix watchdog: single tick **40,001 ms** |
| 23:18:33.197 | Same tick now **413,414 ms**, eleventh dump, identical stack |

The tick grows ~41 s per watchdog dump: 40,001 / 81,316 / 123,043 / 164,990 / 206,513 / 247,919 /
289,293 / 330,899 / 372,053 / 413,414 ms. It never completes.

## The abort line and its bounds

```
[23:11:34.560] [Render thread/ERROR] [dev.ryanhcode.sable.Sable/]:
Aborting entity get for abnormally large AABB:
AABB[1815.5532449438017, 67.0, -1.620864198436978E7]
 -> [2.1967152361004844E7, 9698766.048670202, -6001.80310362498]
```

That is roughly **x: 2.20e7, y: 9.70e6, z: 1.62e7** blocks of extent. It repeats **4,291** times, all on
the Render thread, with slowly drifting values:

| Axis | Observed extremes |
|---|---|
| x | up to `2.896e7`, and negative to `-1.564e7` |
| y | down to `-2.160e6`, up to `6.05e5` |
| z | down to `-2.772e7`, up to `1.746e7` |

These are not ship coordinates. They are millions of blocks away from anything the player was near.

## The server-thread stack

Identical in every one of the eleven watchdog dumps:

```
ServerLevel.tick
  Neruina TickHandler.safelyTickEntities        (wraps the tick; cannot interrupt one move)
  ItemEntity.tick
    Entity.move
      vanillabackport wrapOperation (captureMovementAndVelocity)
      Sable redirect (collideRedirect)
      Lithium Entity.lithium$CollideMovement
        Shapes.collide
          Lithium LazyList
            ChunkAwareBlockCollisionSweeper.computeNext / nextSection
              Level.getChunk -> C2ME-instrumented ServerChunkCache.getChunk
```

`ChunkAwareBlockCollisionSweeper` iterates **every chunk section intersecting the swept AABB**. Given a box
spanning 2.2e7 blocks on an axis, that is millions of sections and millions of `getChunk` calls.

## Root cause

`SubLevelEntityCollision.collide` builds the region it will walk in five steps:

1. take the entity's box swept from where it was to where it is going;
2. inverse-transform it into the sub-level's frame at the sub-level's **previous** pose;
3. inverse-transform it again at the sub-level's **current** pose;
4. union the two;
5. walk every block position in the union, four times over.

Steps 2 and 3 are the same entity, in the same tick, seen from two frames. When those frames are far
apart, the union spans the gap between them — and its size has nothing to do with the size of the ship or
the entity. Step 4 is where a region of millions of blocks is born.

Sable already detects this and refuses to **walk** it:

```java
if (localBounds.volume() > 500 * 500 * 500) {          // 125,000,000
    Sable.LOGGER.info("Enormous local sub-level collision bounds, quitting.");
    continue;
}
```

That guard is real, and it fired 20 times in this log. It was not enough, for two reasons.

**First, the ceiling is too high.** A thin slab of 57,863,825 blocks passes straight through a
125,000,000-block volume test. A separately reported case of exactly that shape held a real server tick
for 207 seconds.

**Second — and this is the actual defect — the guard only skips Sable's own block walk.** The `continue`
leaves `collisionInfo.motion` equal to the entity's original motion. Sable's `EntityMixin.collideRedirect`
then calls vanilla `Entity.collide()` with exactly that value, vanilla expands the entity box by it into a
swept AABB, and **that** AABB is what Lithium's uncapped sweeper walks.

Sable protected its own loop and handed the same unrepresentable number to code it does not own.

## Why the pose sanity paths did not catch it

Counted from the raw log:

| Sable safety path | Hits |
|---|---|
| NaN pose detection (`Invalid position ... received for sub-level ... from pipeline`) | **0** |
| Extreme-Y sub-level removal (`Sub-level ... has an extreme Y coordinate range, removing`) | **0** |
| Enormous collision bounds abort | 20 |
| Abnormally large AABB abort | 4,291 |

Both pose-validity paths never fired. The pose pair that produced the oversized region was individually
plausible — the defect is the **union of two valid poses**, not one corrupt pose. That is why validating
a single pose or a single AABB at computation time does not close this: nothing is invalid at that point.
The invalid thing only comes into existence one step later, and what escapes is a *motion*, not the AABB.

## Why Lithium is not the defect

Lithium's sweeper has no volume cap and no section-count cap. That is a real robustness gap, and this mod
closes it. But Lithium is behaving as designed: it was handed an AABB that vanilla collision considers
legal, and it walked it. Vanilla's own `getBlockCollisions` would have walked the same region; Lithium
just gets there faster. Removing Lithium does not fix this, and the fix cannot live there.

## Reproduction and verification

See the repository README's **Verification** section for the A/B control, the binding self-check and the
exact numbers. In short:

- A throwaway probe mod (no fix in it) hands the real Lithium sweeper the real swept AABB from the
  incident. With the guard absent the sweep never returns; with the guard present it is refused in ~11 ms.
- The legitimate-sweep control returns immediately in **both** runs, so the guard is not over-refusing.
- A binding self-check force-loads each target and proves all four handlers attached, and that every
  decision predicate accepts legitimate input and rejects the incident's shapes.

## Related upstream issues

- ryanhcode/sable#1228 — server crashed on 1.21.1 NeoForge, `Enormous local sub-level collision bounds`
- ryanhcode/sable#1447 — Async + Sable collision, same log line from worker threads
- ryanhcode/sable#857, #338, #1098 — sub-level bounds/chunk-loading stability, related family
- AxalotLDev/Async#205 — watchdog hang with the stack passing through `SubLevelEntityCollision.collide`

## What the upstream fix should be

1. A pose pair that far apart should not produce a union used as a sweep region at all. The union should
   be bounded by the sub-level's real block extents, or the sweep should be split rather than unioned.
2. **A declined pass must not return the motion that justified declining it.** When the enormous-bounds
   guard fires, `collisionInfo.motion` should be clamped or zeroed before it is handed to vanilla
   collision. This is the single highest-value change and it is a few lines.

This mod is a containment layer that can ship before either lands.
