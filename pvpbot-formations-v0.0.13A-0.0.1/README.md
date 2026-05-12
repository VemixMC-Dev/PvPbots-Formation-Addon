# PVP Bot Formation Addon

Adds **grid formation** commands to the PVP Bot mod (v0.0.13A).  
Optionally integrates with **pvpbot-groups** if it is also installed.

---

## Requirements

| Mod | Version |
|-----|---------|
| pvp-bot-fabric | 0.0.13A |
| Fabric Loader | ≥ 0.18.4 |
| Fabric API | 0.141.3+1.21.11 |
| Minecraft | 1.21.11 |
| pvpbot-groups *(optional)* | v0.0.13A |

---

## Commands

### Mass spawn with instant formation
```
/pvpbot massspawn <count> grid
```
Spawns `<count>` bots **and** automatically arranges them in a grid once they've all finished loading in.  
Stand where you want to be the general — bots will form up in front of you, facing the same direction you're facing.  
*(If you omit `grid` the normal base-mod massspawn runs with no formation.)*

### Faction formation
```
/pvpbot faction <faction> formation grid
```
Arranges **all active bots** in the given faction into a centred rectangular grid.  
The calling player acts as the anchor — bots form up **in front of them**.

### Group formation *(requires pvpbot-groups)*
```
/pvpbot faction group <group> formation grid
```
Arranges the bots in a specific **group** (from pvpbot-groups) into a grid.

---

## Grid Formation Rules

| Setting | Value |
|---------|-------|
| Row width | **5** bots (4 when N ≤ 4) |
| Column spacing | 2 blocks |
| Row spacing | 2 blocks |
| Fill order | Left → right, then next row |
| Last row | Centred horizontally |
| Anchor | Caller's position & yaw |
| Formation direction | In **front** of caller |

### Examples

**N = 6** (row width 5)
```
Row 1:  B B B B B      ← 5 bots
Row 2:      B          ← 1 bot, centred
```

**N = 20** (row width 5)
```
Row 1:  B B B B B
Row 2:  B B B B B
Row 3:  B B B B B
Row 4:  B B B B B
```

**N = 21** (row width 5)
```
Row 1:  B B B B B
Row 2:  B B B B B
Row 3:  B B B B B
Row 4:  B B B B B
Row 5:      B          ← centred
```

---

## Building

You need **JDK 21** and an internet connection (Gradle downloads dependencies automatically).

```bash
# On Linux/macOS
chmod +x gradlew
./gradlew build

# On Windows
gradlew.bat build
```

The compiled `.jar` will be in `build/libs/`.  
Drop it into your Minecraft server's `mods/` folder alongside `pvp-bot-fabric-0.0.13A.jar`.

---

## How it works

The addon uses **reflection** to access pvpbot's internal classes (`BotFaction`, `BotManager`) at runtime — no compile-time dependency on pvpbot is required.  
The same approach is used for pvpbot-groups (`GroupManager`) so the addon works whether or not groups is installed.
