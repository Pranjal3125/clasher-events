# Clasher Events 0.6.0 — Release Candidate Notes

**Status: Development / release candidate — not production-certified**

## Included in this candidate

- Corrected NethPot phase progression and Wave 2 threshold handling.
- Exactly-two-survivor transition to the Final phase.
- Direct Final start for two-player events.
- Winner and no-contest handling with an exactly-once completion guard.
- UUID-based winner recording and offline-safe reward/stat handling.
- Kit delivery remains in the waiting lobby; arena teleport happens when the match starts.
- Removed obsolete invalid Java source stubs that prevented compilation.
- Java 25 GitHub Actions build currently passes.

## Validation

The latest recorded GitHub Actions run for the main branch completed successfully:
https://github.com/Pranjal3125/clasher-events/actions/runs/44813410441

A successful CI build confirms compilation and artifact packaging only. It does **not** prove live gameplay correctness.

## Required before a stable 0.6.0 release

- Sync and review the remaining pending arena, kit, YAML, and documentation changes.
- Validate all YAML files and arena spawn configuration.
- Test all eight event modes on a disposable Paper server.
- Test NethPot wave transitions, simultaneous eliminations, winner/no-contest handling.
- Test kit timing, inventory restoration, disconnect/rejoin, late join, and server shutdown recovery.
- Test commands, permissions, spectator flow, rewards, and arena reset.
- Attach and verify the final JAR from a clean release build.

Do not install this candidate on the live Clasher Network until the release-blocking tests pass.
