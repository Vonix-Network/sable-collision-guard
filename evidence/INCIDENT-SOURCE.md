# Incident source material

## Raw log

| | |
|---|---|
| File | `evidence/incident-raw.log` (gitignored, on disk only — 17 MB) |
| SHA-256 | `5b448470d55cd767f394fdef218d8f490fe8b97e7ac3e7bc7a4cd32e1cecc8c6` |
| Bytes | 17,914,177 |
| Lines | 136,846 |
| Environment | Client singleplayer, MC 1.21.1, NeoForge 21.1.265, Java 21.0.7 (Microsoft), Windows 11 |
| Pack | Raven's High Fantasy 0.3.1 |
| Session date | 2026-11-16, log-local time |

Original supplier URL (expires 2026-11-27):

```
https://muse.ai/files/1369039299625831/1465523403316297/1nmzxftvce6pgnapd49cokbb/ravencrash_0_614o.log
```

The 17 MB raw log is **not committed** — it is gitignored by design so the repository stays small. It is
present on disk at the path above. If it is ever lost, the SHA-256 and the supplier URL are the recovery
identity: accept a re-download only if size and hash match, or if the supplier URL is dead, treat the raw
log as unrecoverable and rely on the excerpts below plus `incident-diagnosis.md`.

## Excerpts and diagnosis

| File | Contents |
|---|---|
| `incident-diagnosis.md` | The written diagnosis supplied with the report |
| `diagnosis-paste.txt` | Original diagnosis paste (`https://paste.rs/PiaPk`) |
| `excerpts-paste.txt` | Key log excerpts — first AABB aborts with exact bounds, the `Enormous local sub-level collision bounds` lines, the spark timeout, two full ModernFix watchdog stack dumps |

## Timeline anchors (from the raw log)

| Time | Event |
|---|---|
| 23:11:34.047 | `New unregistered ship detected: id=559b8029-c14b-4527-b8ba-843a0125eed4 name=ship` (aeroclaims) |
| 23:11:34.297 | `Received a sub-level movement packet for a non-existent sub-level` |
| 23:11:34.560 | First `Aborting entity get for abnormally large AABB` — `AABB[1815.55, 67.0, -1.620864198436978E7] -> [2.1967152361004844E7, 9698766.05, -6001.80]` |
| 23:11:39.810 | `Enormous local sub-level collision bounds, quitting.` — 10 times on Render thread, 10 on Server thread |
| 23:12:05 | spark: `Timed out waiting for world statistics` |
| 23:12:19.784 | ModernFix integrated watchdog: single tick 40,001 ms |
| 23:18:33.197 | Same tick now 413,414 ms, eleventh dump, identical stack |

## Counts taken from the raw log

- `abnormally large AABB` errors: **4,291**, all on the Render thread
- `Enormous local sub-level collision bounds, quitting.`: **20** (10 Render, 10 Server)
- `extreme Y coordinate range` sub-level removals: **0**
- `Invalid position ... received for sub-level ... from pipeline` (NaN pose recovery): **0**

The absence of the last two matters: Sable's own pose-sanity and out-of-range removal paths never fired, so
the pose pair producing the oversized region was individually plausible. The defect is the *union* of two
valid poses, not one invalid pose.
