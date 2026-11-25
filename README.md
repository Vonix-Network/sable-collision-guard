# Sable Sub-Level Collision Bounds Guard

Standalone, server-safe NeoForge 1.21.1 companion for the Otherworld freeze in
**Raven's High Fantasy 0.3.1**.

- Mod id: `sableboundsguard`
- Artifact: `sableboundsguard-1.21.1-1.0.0.jar`
  (`3fb2e4e9e6d72cf4ffe0689306240b923bc1f2b8376697c9b1b3a4f27b8bbb2d`)
  Reproducible: `preserveFileTimestamps = false` and `reproducibleFileOrder = true`, so a rebuild from
  committed source yields the same bytes. Verified by two consecutive `clean build` runs.
- Host mods (optional dependencies, never bundled): Sable `2.0.6`
  (`9c199157843bf953a47dff625fc5bb41c01a90538d35ad18e6996bbcb8a617c2`) and its nested companion
  `1.6.0` (`873633e35046e3761b277ff8a1ecad0d55d9a3014fa81a0b084c9aecba1f3bed`), Lithium `0.15.4`
  (`488f33216030c1cb0baa33e59de3d657d08d5795a6c2080c8c8bbbc133fc8757`)

## The freeze

A single item entity, one server tick, never completes. Eleven ModernFix watchdog dumps, identical
stack every time, 40,001 ms at the first and 413,414 ms at the eleventh:

```
ServerLevel.tick
  ItemEntity.tick
    Entity.move
      Shapes.collide
        ChunkAwareBlockCollisionSweeper.computeNext / nextSection
          Level.getChunk
```

The cause is not a slow scan that got slower. It is a number.

Sable builds a sub-level's collision region by inverse-transforming the entity's swept box into the
sub-level's local frame **twice** — once at the sub-level's previous pose, once at its current pose —
and taking the union. Those two transforms are the same entity in the same tick seen from two frames.
When the frames are far apart the union spans the gap between them, and its size has nothing to do with
the size of the ship or the entity. Measured bounds in the incident log reach
`x = 2.877e7`, `y = -1.967e6`, `z = -2.55e7` blocks.

Sable already refuses to **walk** such a region. It compares the union's volume against 125,000,000 and
logs:

```
Enormous local sub-level collision bounds, quitting.
```

That line appears twenty times in the log, ten of them on the server thread. It was not enough, for two
reasons:

1. **The ceiling is too high.** A thin slab of 57,863,825 blocks passes straight through it. That case
   held a real server tick for 207 seconds.
2. **More importantly, that guard only skips Sable's own block walk.** The `continue` leaves
   `collisionInfo.motion` equal to the entity's original motion. Sable's `EntityMixin` then calls
   vanilla `Entity.collide()` with exactly that value, vanilla expands the entity box by it, and
   Lithium's `ChunkAwareBlockCollisionSweeper` — which has no cap of any kind — walks every chunk section
   in the result. Sable protected its loop and handed the same unrepresentable number to code it does not
   own.

That seam is the defect. Not the size of the region, and not Lithium.

## What this mod does

Three guards, on the seam:

| Guard | Point | What it does |
|---|---|---|
| Narrow the pose union | `BoundingBox3d.expandTo` inside `SubLevelEntityCollision.collide` | When the union of the two poses is implausibly large, keep the current-pose half. Collision keeps working where the sub-level actually is; only the sweep back in time is dropped. |
| Contain the returned motion | `CollisionInfo.motion` at return | A pass that declines the work also declines to publish the number that justified it. This is the guard that was missing upstream. |
| Cap the sweep | `BlockPos.betweenClosed` in the same method | Safety net. If an oversized region still arrives, walk nothing rather than walk it. |
| Refuse an oversized sweep | Lithium `ChunkAwareBlockCollisionSweeper.nextSection` | Second safety net, underneath, at the exact loop that froze. Gated on Lithium being present. |

### What it does not do

- It does not delete, teleport, move, or repair anything. No sub-level pose, bounds, blocks or NBT is
  edited; the world is never written; no entity is removed.
- It does not touch Lithium's jar, Sable's jar, or any other mod's file.
- It does not disable sub-level collision. A guard that fires costs **one pass** of collision for one
  entity, not a player's progress.
- It does not raise or lower Sable's own 125,000,000 ceiling, and does not depend on it.

### Cost of a guard firing

Narrowing gives up tunnelling protection across a pose gap that the sub-level could not legitimately
have produced. The caps give up block collision for the affected entity for that single pass. Both are
strictly better than a tick that never ends.

## Verification

Built against the exact host jars by hash (`verifyHostPins`), with a compile-time failure if any pin
does not match.

**Packaged-artifact gates** (`./gradlew build`): host pins, mixin package ownership on the compiled
bytes, mixin config present in the jar, `MixinConfigs` in the manifest, every class listed in the config
present in the jar.

**Binding gate.** A mixin that fails to apply looks exactly like a mixin that works: the jar builds, the
server boots, `Done` appears, and nothing says otherwise. An opt-in self-check proves each handler bound
*and* decides correctly:

```
java -Dsableboundsguard.selfcheck=true ... --nogui
```

```
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND narrowPoseUnion   via method redirect$zog000$sableboundsguard$narrowPoseUnion
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND capRegion         via method redirect$zog000$sableboundsguard$capRegion
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND containMotion     via method modifyReturnValue$zog000$sableboundsguard$containMotion
SABLEBOUNDSGUARD-SELFCHECK lithium-sweeper    BOUND refuseOversizedSweep via method handler$zof000$sableboundsguard$refuseOversizedSweep
SABLEBOUNDSGUARD-SELFCHECK motion clamp: 28971398 -> 63.99999999999999 blocks/tick
SABLEBOUNDSGUARD-SELFCHECK narrowing: union of 213016968056096 blocks narrowed to the current pose
SABLEBOUNDSGUARD-SELFCHECK RESULT=PASS
```

Decision cases covered: the incident motion, a modest diagonal, a fast-airship step, the clamp ceiling,
one step past it, NaN and infinity; the measured 155x985x379 region, millions-per-axis, a typical entity
step, a large hull sweep, one axis past the plot size, and a zero extent; a far-apart union that must be
narrowed and a nearby union that must not be.

**A/B control.** A separate jar (`sableprobe`, built from a source set that cannot reference this mod,
ships no fix) hands the real Lithium sweeper the real swept AABB from the incident
(`x=21965337 y=9698699 z=16202640`, volume `3.452e+21`). Same server, same jars, only the guard changes:

```
A  guard absent   incident VERDICT=UNBOUNDED shapes=0 elapsedMs=11999 (the sweep never returned)
                  control  VERDICT=IMMEDIATE shapes=0 elapsedMs=0
B  guard present  [SableBoundsGuard] refused a collision region of 21965338x254x16202641 blocks at lithium chunk sweep
                  incident VERDICT=IMMEDIATE shapes=0 elapsedMs=11
                  control  VERDICT=IMMEDIATE shapes=0 elapsedMs=0
```

The control returning `IMMEDIATE` in both runs is the regression half: the guard does not refuse
legitimate sweeps.

**Which guard is proven by which run.** The A/B above drives the **Lithium** sweeper directly, and the
probe's box never touches Sable, so run B proves the Lithium safety net only. Its log shows
`SubLevelEntityCollisionMixin ENABLED` but no `applied` line for it — in a boot that short the Sable
collision class is never loaded, so the mixin is armed but unapplied. That is expected, and it is the
reason a boot alone is not evidence.

The Sable-side guards are proven by run C, which force-loads both targets:

```
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND narrowPoseUnion  via redirect$zog000$sableboundsguard$narrowPoseUnion
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND capRegion        via redirect$zog000$sableboundsguard$capRegion
SABLEBOUNDSGUARD-SELFCHECK sublevel-collision BOUND containMotion    via modifyReturnValue$zog000$sableboundsguard$containMotion
SABLEBOUNDSGUARD-SELFCHECK lithium-sweeper    BOUND refuseOversizedSweep via handler$zof000$sableboundsguard$refuseOversizedSweep
SABLEBOUNDSGUARD-SELFCHECK RESULT=PASS
```

together with a decision test that narrows a real `213,016,968,056,096`-block pose union to the current
pose, logged by the production code path rather than by a fixture.

All three runs used the same guard logic on a freshly installed NeoForge 21.1.265 dedicated server with
real Sable 2.0.6 and Lithium 0.15.4. They were captured at artifact
`5c762e18657c13d87fc9608a1ee4892c3b80e52c8063ea29ef57994cc41492c8`; the only later change is inside
`SelfCheck` (the opt-in diagnostic), and the three guard classes — `SubLevelEntityCollisionMixin`,
`LithiumSweeperMixin`, `BoundsGuard` — are byte-identical in the current artifact, verified with `cmp`.

**Exit-status gate.** The self-check halves are only useful if they can fail loudly, so the verdict is
carried in the process exit status as well as the log. `MinecraftServer.halt(boolean)` is not an exit-code
parameter — its argument is `waitForServer`, and passing `true` joins the server thread from itself, which
deadlocks. `System.exit` was also swallowed by NeoForge's shutdown path. `Runtime.halt(1)` sets the status
deterministically. Both branches verified:

```
A  all guards bound      SELFCHECK RESULT=PASS halting server exitCode=0    PASS_EXIT=0
B  injected failure      SELFCHECK RESULT=FAIL failures=1 exitCode=1        FAIL_EXIT=1
```

Case B uses `-Dsableboundsguard.selfcheck.forceFail=true`, a test-only switch, because removing Lithium to
force a real failure aborts server init before the event fires — a verdict that can only be exercised on
its passing branch is not a tested verdict.

Raw logs: `evidence/A-baseline-unbounded.log`, `evidence/B-patched-refused.log`,
`evidence/C-selfcheck-pass.log`.

## Limits of this evidence

- The A/B reproduces the exact defect path on a live dedicated server, but it is a reproduction, not the
  player's world. No capture of the original world exists.
- The evidence is a **client singleplayer** log. Sable and Lithium ship in the server pack, so a dedicated
  server reaches the same code, but a clean dedicated-server capture of the original freeze has not been
  taken.
- `35,502` collision passes with sub-levels at rest were measured never coming within an order of
  magnitude of the volume cap, and in one broken session 120 of 120 passes exceeded it. No sample exists
  of a legitimate pass during **fast rotation**, because the union grows with rotation by design. If
  entities are seen passing through a spinning sub-level, raise the cap:
  `-Dsableboundsguard.maxSweepVolume=1048576`.
- Sable's own pose pipeline, not only the collision code, remains the upstream defect. This mod contains
  the damage; it does not repair why a pose pair can be that far apart.

## Config

Two system properties, both optional, both in blocks:

| Property | Default | Meaning |
|---|---|---|
| `sableboundsguard.maxMotion` | `64` | Largest per-tick entity motion allowed, blocks |
| `sableboundsguard.maxSweepVolume` | `262144` | Largest block region one collision sweep may walk |

## Install

Drop the jar in the pack's `mods/` folder. It belongs on **both** the client and the server.

- **Server** — required. This is where the freeze happens, and where the guards have to run.
- **Client** — optional but recommended for a singleplayer or LAN world, because the same collision code
  runs in an integrated server. `displayTest = "IGNORE_ALL_VERSION"`, so a client without it can still
  join a server that has it, and a client with it is harmless.

No configuration is required. The jar works on a fresh install with Sable and Lithium present.

## Compatibility

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 or newer (declared `[21.1.265,)`) |
| Java | 21 |
| Sable | 2.0 or newer — built and verified against 2.0.6 |
| Lithium | 0.15+ optional — its guard applies only when present |
| Side | Client and server; `IGNORE_ALL_VERSION` |

- **Sable is a required dependency** (declared `required` in `neoforge.mods.toml`). The guards patch Sable's
  collision method directly, so a Sable-free install is a configuration mistake rather than a degraded mode.
  The guard classes are also gated on the target being on the classpath, so a missing Sable produces a clear
  "target absent" line instead of a boot failure.
- **Lithium is optional by design.** The sweeper guard is gated on the target class being on the
  classpath, so a pack without Lithium simply runs the Sable-side guards instead of failing to boot.
- **Sable's own version ceiling is untouched.** This mod adds a *lower* bound; it does not raise, lower or
  depend on Sable's internal 125,000,000-block figure.
- **No conflicts with other Sable mixins are expected.** Every guard targets a method Sable already owns
  and adds a bounded early-out or a value clamp; nothing is `@Overwrite`n, no Minecraft class is patched,
  and no other mod's behaviour is changed.
- **Known interaction, not a conflict:** packs running C2ME will show the freeze stack passing through
  `ServerChunkCache.getChunk` with C2ME instrumentation in the frame. That is C2ME doing its normal job on
  a `getChunk` call that should never have been made in the first place — it is not a C2ME defect.


## Building

```
export JAVA_HOME=<jdk21>
./gradlew build
```

Produces `build/libs/sableboundsguard-1.21.1-1.0.0.jar` and, separately,
`build/libs/sableprobe-0.0.1-NEGATIVE-CONTROL-DO-NOT-SHIP.jar`. The probe must never be deployed.

`libs/*.jar` are gitignored build inputs copied from the live pack, not artifacts to commit.

## Upstream

The proper long-term fix is in Sable: a pose pair that far apart should not produce a union used as a
sweep region at all, and a declined pass should not return the motion that justified declining it. This
mod is a containment layer that can ship before that lands.

Full write-up, evidence and timeline: [`docs/DIAGNOSIS.md`](docs/DIAGNOSIS.md).

Related upstream issues: ryanhcode/sable#1228, #1447, #857, #338, #1098; AxalotLDev/Async#205.

## License

LGPL-3.0-or-later — see [LICENSE](LICENSE).

Sable is by ryanhcode and does all the actual work; this mod is unaffiliated and not endorsed by anyone
involved with it. Lithium is by the CaffeineMC team. Neither is bundled here.

