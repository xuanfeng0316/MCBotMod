# MCBotMod

Through a Fabric mod, Minecraft client's native methods are exposed as a TCP + JSON interface. Any program capable of TCP communication can call this mod's interface to operate a real, complete game client.

**Not packet simulation, not a headless process — it calls the game's native methods through the interface and runs on a real client.**

## Core Features

**Real Client**
Graphics, rendering, and UI are fully preserved; program operations are visible in sync with the screen. Other Fabric mods work as usual, and both premium and offline login follow the vanilla process. Any flow that requires a graphical interface is unrestricted.

**Native Methods**
Exposes the game's own methods; the project's ceiling is the game's ceiling. TCP + JSON — any language supporting TCP communication can connect. The mod supports concurrent operations, allowing multiple actions to run simultaneously.

## Human–Program Coexistence

Player input and program output are additive, not mutually exclusive. When the program stops sending commands, the player takes over immediately; in emergencies, the player can intervene directly via keyboard. Switching between fully automatic, semi-automatic, and fully manual modes is seamless.

## Startup

 No additional programs need to be deployed. All you need is a Minecraft Java Edition Fabric client, this mod, and two dependencies (Fabric API, Fabric Language Kotlin) — and it's ready to run.
