# Changelog

## 1.0.0 — 2026-11-24

First candidate. Server-safe containment for the Sable sub-level collision freeze in
Raven's High Fantasy 0.3.1 (MC 1.21.1, NeoForge 21.1.265).

### Guards

- **Narrow the pose union** at `BoundingBox3d.expandTo` inside `SubLevelEntityCollision.collide`.
  When the union of the entity box at the sub-level's previous and current poses is implausibly large,
  keep the current-pose half. Collision against where the sub-level actually is keeps working; the sweep
  back to where it used to be is what is given up.
- **Contain the returned motion** (`CollisionInfo.motion` at return). Sable's own 125,000,000-block guard
  skips its block walk but returns the entity's original multi-million-block motion unchanged; that
  motion is then handed to vanilla `Entity.collide()`, expanded into a swept AABB, and walked by
  Lithium's uncapped sweeper. This clamps it before it leaves.
- **Cap the collision region** at `BlockPos.betweenClosed`. Safety net under the two above.
- **Refuse an oversized Lithium sweep** at `ChunkAwareBlockCollisionSweeper.nextSection`. Second safety
  net, at the exact loop that froze. Applies only when Lithium is present.

### Limits

- `sableboundsguard.maxMotion` (default 64 blocks/tick)
- `sableboundsguard.maxSweepVolume` (default 262144 blocks)

### Verification

- Host jars pinned by SHA-256 at build time; build fails on a mismatch.
- Packaged-artifact gates: mixin config present, `MixinConfigs` in manifest, every declared mixin class
  present, mixin package ownership checked on compiled bytes.
- Opt-in binding self-check (`-Dsableboundsguard.selfcheck=true`) proves all four handlers attached and
  that every decision predicate accepts legitimate input and rejects the incident's shapes.
- A/B negative control on a live dedicated server: guard absent → the incident sweep never returns;
  guard present → refused in 11 ms. Legitimate sweep `IMMEDIATE` in both runs.
- Reproducible jar (`preserveFileTimestamps = false`, `reproducibleFileOrder = true`), verified by two
  consecutive `clean build` runs producing identical bytes, so a recorded hash is meaningful evidence.
- Self-check carries its verdict in the process exit status as well as the log, and both branches are
  verified: PASS exits 0, an injected failure exits 1. `Runtime.halt(1)` is used rather than
  `MinecraftServer.halt(boolean)` — whose argument is `waitForServer`, not an exit code, and which
  deadlocks if handed `true` from the server thread — or `System.exit`, which NeoForge's shutdown path
  swallowed.

### Notes

- Contains nothing destructive. No pose, bounds, blocks, NBT or entity is modified; the world is never
  written.
- Deploy the guard jar only. `sableprobe-*-NEGATIVE-CONTROL-DO-NOT-SHIP.jar` is a test fixture.
