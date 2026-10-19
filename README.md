# HollowSkins 1.0.0 — Paper 1.21.11

**Source project** for a Hollow SMP automatic skin restoration + skin selection GUI plugin.

## Build (requires internet, Java 21 and Gradle 8.10+)

From this folder run: `gradle build`

Install `build/libs/HollowSkins-1.0.0.jar` in your server's `plugins/` directory, then restart.

## Commands
- `/hskin` or `/hskins` — open GUI (up to 45 skins per page)
- `/hskin set <MojangUsername>` — use the skin of a premium Minecraft username
- `/hskin reset` — clear the saved selection and try your username's Mojang skin
- `/hskin reload` — reload catalog/config (OP only)

## Important limitations
- Only retrieves skins published for Mojang/Microsoft Minecraft usernames. Cannot extract skins stored only in TLauncher or another cracked launcher.
- Bedrock skins are left to Geyser/Floodgate; do not override them.
- Changing a skin on a connected player may not immediately refresh it for all clients; reconnect if necessary.
- The menu's head previews depend on Minecraft's profile resolution and may show defaults temporarily.
- Remove competing skin plugins first; back up the server.
- The plugin uses Mojang endpoints, so an internet connection is required.
- No binary JAR is included because the Paper API build dependency is unavailable in this environment. **This code has not been compiled or tested against a running Paper server.**

## Build without installing Gradle locally
Upload the source folder contents to a GitHub repository. Open **Actions -> Build HollowSkins -> Run workflow**. After it succeeds, download the `HollowSkins-JAR` artifact and extract the `.jar`. This workflow is provided for convenience but has not been run or validated here.

## Bedrock status
Bedrock players can open the inventory menu through Geyser, but applying Java texture properties to Floodgate Bedrock players is intentionally disabled. True Bedrock skin replacement requires a Geyser-compatible skin implementation and validation; this version does **not** provide that feature.
