# Random Chaos (Fabric 1.21.11)

A survival chaos mod. Effects activate one after another every five minutes while players are online.

## Features
1. Random block drops
2. Random mob drops
3. Random mob sizes (0.35x to 3x)
4. Random mob spawns near random players
5. Random tick speed (0 to 50)
6. Random speed/jump/slow-falling/slowness, weather, and time

## Commands
- `/chaos status` — current stage
- `/chaos pause` — pause
- `/chaos resume` — continue
- `/chaos next` — unlock next stage instantly

## Build with GitHub Actions
1. Create an empty public or private repository on GitHub.
2. Upload all files from this ZIP (including `.github/workflows/build.yml`).
3. Open **Actions** > **Build Fabric Mod** > **Run workflow**.
4. Download the `RandomChaos-Fabric-1.21.11` artifact, unzip it, and use the non-sources `.jar` in `.minecraft/mods`.
5. Install Fabric Loader + the matching Fabric API for Minecraft 1.21.11.

## Important
- This is an initial source project and has **not been compiled or tested** in this environment.
- On server restart, stages start from zero. Random tick speed may retain its previous value in a saved world: set `/gamerule randomTickSpeed 3` to restore it.
- Drops and mob scale modifications can alter survival gameplay. Back up your world.
- All mechanics are server-side; Fabric Loader/API and the mod are required on the server (or integrated singleplayer server).
