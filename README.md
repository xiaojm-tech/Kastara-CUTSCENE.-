# KASTARA-CUTSCENE

Custom first-join cinematic plugin for KASTARA (Paper).

## Features
- First-join-only cinematic with persistent UUID tracking.
- Smooth camera movement using an invisible marker ArmorStand as a spectator camera rig.
- Configurable camera waypoints, titles, spawn, sound and particle effects.
- `/skipintro` to finish immediately.
- `/kastaracutscene play <player>`, `stop <player>`, and `reload`.

## Build
Requires JDK 21+ and Maven 3.9+.

```bash
mvn clean package
```

Output: `target/KASTARA-CUTSCENE-1.0.0.jar`.

## Install
1. Build the JAR and place it in `plugins/`.
2. Start once to generate `plugins/KASTARA-CUTSCENE/config.yml`.
3. Edit spawn and camera waypoint coordinates for the actual KASTARA spawn.
4. Restart the server.
5. Add the generated TTS file at `resource-pack/assets/kastara/sounds/intro.ogg`.
6. Zip the *contents* of `resource-pack/` so `pack.mcmeta` and `assets/` are at ZIP root.
7. Host the ZIP and configure the server resource-pack URL and SHA-1 in server.properties or your resource-pack delivery plugin.

## Resource pack
The resource pack source is in `src/main/resources/pack/`. Minecraft Java 26.3 uses resource pack format 97.1. The binary voice-over is intentionally not included: generate the Indonesian TTS voice-over and save it as `assets/kastara/sounds/intro.ogg`.

The registered sound event is `kastara:intro`.

## Important first test
The waypoint coordinates in the default config are illustrative placeholders, not the real KASTARA ruins or altar. Configure the route and spawn before allowing players to use the intro. Test camera spectating on the exact Paper 26.3 build and Minecraft client first.
