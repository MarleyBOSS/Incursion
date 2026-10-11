# Incursion

Build the Incursion Core and level it from 0 to 20. Every level grants stronger benefits: item generation, energy output and beacon-like area buffs. But each upgrade also intensifies a permanent siege: more and stronger mobs will relentlessly try to destroy the Core. How far do you dare to go?

- Minecraft 1.21.1 · NeoForge
- Status: early development (v0.1)

See [DESIGN.md](DESIGN.md) for the full concept and roadmap.

## Download

Every push is built automatically. Open the latest successful run under **Actions → Build** and download the `incursion-jar` artifact. Put the `.jar` into your `mods` folder.

## Current features (v0.1)

- **Incursion Core** block (crafted from crying obsidian, eyes of ender and a diamond block)
- Right-click with a **blaze rod** to activate it (level 1). Only one active core per world.
- Right-click with **diamonds** to upgrade it (cost rises per level, up to level 20).
- Right-click with anything else to see its status.
- Active cores cannot be mined.
- All values are configurable in `serverconfig/incursion-server.toml` or via the Mods menu.
