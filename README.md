# Daldon's Snowy Roofs — Forge 1.20.1

Experimental source project for a **visual** snow overlay on Macaw's Roofs.

- Requires Macaw's Roofs 2.3.x (`mcwroofs`) and Forge 47.x.
- Shows slanted snow on exposed Macaw's Roofs blocks in cold snowy biomes.
- Snow uses the vanilla snow atlas sprite (compatible with texture packs).
- Does not change roof block states, loot, items, collision, or world saves.
- This is **not yet** true simulated snow accumulation/melting or collision-bearing snow.
- Top / corner / attic models may not align perfectly: overlay slope is an approximation, not derived from the Macaw's Roofs model geometry.
- Client-side visual effect; for multiplayer, players must install it on their client. The mod is safe to keep on both server and clients.

## Build

Use Java 17, Gradle 8.x and ForgeGradle 6, with network access to Forge Maven. From the project folder, run `gradle build`. The built jar will be in `build/libs/`.

**Status:** Source draft; build and in-game visuals have not been tested. Check GitHub Actions for build status.

## Notes

Future full version should integrate directly with Macaw's model geometry for matching corners and ridge pieces, track exposed snow independently of temperature, and handle accumulation/melting with server-authoritative state.
