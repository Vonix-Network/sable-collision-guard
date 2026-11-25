# Otherworld (Raven's High Fantasy) freeze — diagnosis

Source log: `~/workspace/user/files/ravencrash_0_614o.log` (17 MB, 136,846 lines)
Client singleplayer session, player lokpocka, Nov 16, 2026. Pack: Raven's High Fantasy 0.3.1 (the pack behind the Otherworld server), MC 1.21.1, NeoForge 21.1.265, Java 21.0.7, Windows 11, RX 6600.

## Verdict: permanent single-tick freeze (not a crash)

A Sable sub-level's collision bounds blow up to millions of blocks. Sable guards its
entity-query path, but an ItemEntity move then runs its collision sweep through
Lithium's uncapped ChunkAwareBlockCollisionSweeper, which iterates every chunk
section intersecting the swept AABB — effectively forever. The server thread never
finishes the tick. On a dedicated server this presents as a full hang (spark
"Timed out waiting for world statistics", no crash report).

## Evidence timeline (Nov 16, 2026, log-local time)

- 23:11:34 — first Sable ERROR (Render thread): "Aborting entity get for abnormally
  large AABB: AABB[1815.55, 67.0, -1.620864198436978E7] -> [2.1967152361004844E7,
  9698766.05, -6001.80]". Repeats constantly; values keep growing (y later
  -1,288,140 / -1,967,470; x up to 2.877E7).
- 23:11:39 — Sable INFO, both threads, repeated: "Enormous local sub-level
  collision bounds, quitting."
- 23:12:05 — spark WARN: "Timed out waiting for world statistics".
- 23:12:19 — ModernFix integrated watchdog: single server tick 40,001 ms.
  Grows every ~41 s: 81,316 / 123,043 / 164,990 / 206,513 / 247,919 / 289,293 /
  330,899 / 372,053 / 413,414 ms. Tick never completes; log ends in thread dumps.

## Server thread stack (identical in every watchdog dump)

```
ServerLevel.tick
  Neruina TickHandler.safelyTickEntities (wraps the tick; cannot interrupt one move)
  ItemEntity.tick (ItemEntity.java:163)
  Entity.move
    vanillabackport wrapOperation (captureMovementAndVelocity)
    Sable redirect (collideRedirect)
    Lithium Entity.lithium$CollideMovement
  Shapes.collide
    Lithium LazyList
    ChunkAwareBlockCollisionSweeper.computeNext / nextSection
      Level.getChunk -> C2ME-instrumented ServerChunkCache.getChunk
```

## Versions in play

- Lithium 0.15.4+mc1.21.1
- Sable (dev.ryanhcode.sable) + sablescalablelux-mod; Aeronautics addons present
  (aero_additions, aeroclaims, aeroworks)
- C2ME, ModernFix 5.27.27, Neruina 3.3.5

## Fix options (for discussion with Oro)

- A) Mixin on Lithium ChunkAwareBlockCollisionSweeper: cap sections/AABB size per
  sweep; beyond the cap, end the sweep (no further collision) + rate-limited log.
  Smallest blast radius; sits exactly where it loops. (Venix lean)
- B) Mixin on Entity.collide/move: drop candidate AABBs spanning more than X
  blocks on an axis before colliding (mirrors Sable's own abort philosophy).
- C) Entity position/velocity sanity guard (discard non-finite/absurd entities).
  Probably insufficient alone — the item's own position looks normal; the
  sub-level bounds are the enormous part.
- D) A + C combined in one small guard mod, nsredwoodfix-style.

## Status

- Nov 24, 2026 ~18:51 EST: brief posted to Oro in #oro-agent (msg
  1574935573204836383, with mention). Hermes had not opened the session thread
  yet as of ~18:55 EST (his sessions have been quiet since ~16:02 EST).
- Nov 24, 2026 ~19:06 EST: Pargon confirmed Murphy was offline and ordered a
  re-ping. Re-ping sent as a new top-level #oro-agent message
  (1574939400964542469) that supersedes the first brief and includes the
  updated scope from Pargon: the PROPER fix (Sable-side bounds validation at
  the computation point) as the target, Lithium sweeper cap as safety net
  only. If a thread ever opens on the first brief, it is to be ignored.
- Deployment of any built fix to the Otherworld server needs Pargon's say-so.
