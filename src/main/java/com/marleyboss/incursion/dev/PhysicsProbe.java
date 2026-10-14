package com.marleyboss.incursion.dev;

import com.marleyboss.incursion.Incursion;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Development tool, only active when the environment variable INCURSION_PROBE is set (see the "Probe" workflow).
 * Builds water channels high above the world, puts a zombie into each and lets it swim towards the upstream end the
 * way the siege drive does (wanted position + jumping), then logs how far it got. Measures the real game physics
 * instead of guessing them. Everything is logged with the prefix "PROBE".
 */
public final class PhysicsProbe {
    private static final ResourceLocation SWIM_ID = ResourceLocation.fromNamespaceAndPath("incursion", "siege_swimmer");
    private static final int FLOOR = 200;
    private static final int RUN_TICKS = 400;
    private static final double START_X = 6.5;

    private record Lane(String name, int z, double efficiency, boolean stillWater, int depth, boolean nav, boolean ledge) {
        double goalX() {
            return ledge ? -0.5 : 0.5;
        }

        double goalY() {
            return FLOOR + (ledge ? 5.0 : 1.0);
        }
    }

    private static final List<Lane> LANES = new ArrayList<>();
    private static final List<Zombie> MOBS = new ArrayList<>();
    private static int phase = 0;
    private static int phaseStart = 0;

    private PhysicsProbe() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PhysicsProbe::onTick);
    }

    private static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        if (now < 100) {
            return;
        }
        try {
            ServerLevel level = server.overworld();
            if (phase == 0) {
                build(level);
                phase = 1;
                phaseStart = now;
            } else if (phase == 1 && now - phaseStart >= 100) {
                spawn(level);
                phase = 2;
                phaseStart = now;
            } else if (phase == 2) {
                step(level, now - phaseStart);
                if (now - phaseStart >= RUN_TICKS) {
                    Incursion.LOGGER.info("PROBE DONE");
                    phase = 3;
                    server.halt(false);
                }
            }
        } catch (Throwable error) {
            Incursion.LOGGER.error("PROBE FAILED", error);
            phase = 3;
            server.halt(false);
        }
    }

    private static void build(ServerLevel level) {
        level.setDayTime(18000);
        LANES.add(new Lane("flow-deep/move/eff0.6", 0, 0.6, false, 3, false, false));
        LANES.add(new Lane("flow-deep/nav/eff0.6", 4, 0.6, false, 3, true, false));
        LANES.add(new Lane("still-deep/nav/eff0.6", 8, 0.6, true, 3, true, false));
        LANES.add(new Lane("flow-deep/move/ledge/eff0.6", 12, 0.6, false, 3, false, true));
        LANES.add(new Lane("flow-deep/nav/ledge/eff0.6", 16, 0.6, false, 3, true, true));
        LANES.add(new Lane("flow-deep/nav/ledge/eff1.0", 20, 1.0, false, 3, true, true));
        LANES.add(new Lane("still-deep/nav/ledge/eff0.6", 24, 0.6, true, 3, true, true));
        LANES.add(new Lane("still-deep/move/ledge/eff0.6", 28, 0.6, true, 3, false, true));
        int index = LANES.size();
        for (int cz = -1; cz <= (index * 4) / 16 + 1; cz++) {
            for (int cx = -1; cx <= 1; cx++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        for (Lane lane : LANES) {
            int z = lane.z();
            for (int x = -2; x <= 10; x++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = FLOOR; y <= FLOOR + 6; y++) {
                        level.setBlock(new BlockPos(x, y, z + dz), y == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
                for (int x = -1; x <= 9; x++) {
                    level.setBlock(new BlockPos(x, y, z - 1), Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(x, y, z + 1), Blocks.STONE.defaultBlockState(), 2);
                }
                level.setBlock(new BlockPos(-1, y, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(9, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
            if (lane.ledge()) {
                for (int x = -3; x <= -1; x++) {
                    for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
            int sources = lane.stillWater() ? 9 : 1;
            for (int x = 0; x < sources; x++) {
                for (int d = 0; d < lane.depth(); d++) {
                    level.setBlock(new BlockPos(x, FLOOR + 1 + d, z), Blocks.WATER.defaultBlockState(), 3);
                }
            }
        }
        Incursion.LOGGER.info("PROBE built {} lanes", LANES.size());
    }

    private static void spawn(ServerLevel level) {
        for (Lane lane : LANES) {
            Zombie zombie = EntityType.ZOMBIE.create(level);
            if (zombie == null) {
                throw new IllegalStateException("no zombie");
            }
            zombie.moveTo(START_X, FLOOR + 1.0, lane.z() + 0.5, 90.0F, 0.0F);
            zombie.goalSelector.removeAllGoals(goal -> true);
            zombie.targetSelector.removeAllGoals(goal -> true);
            zombie.setPersistenceRequired();
            zombie.setCanBreakDoors(false);
            AttributeInstance instance = zombie.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
            if (lane.efficiency() > 0.0 && instance != null) {
                instance.addPermanentModifier(new AttributeModifier(SWIM_ID, lane.efficiency(), AttributeModifier.Operation.ADD_VALUE));
            }
            level.addFreshEntity(zombie);
            MOBS.add(zombie);
            BlockPos probe = new BlockPos(3, FLOOR + 1, lane.z());
            Vec3 flow = level.getFluidState(probe).getFlow(level, probe);
            Incursion.LOGGER.info("PROBE lane {} flow@x3=({}, {}, {}) fluid={} speedAttr={} waterEff={}", lane.name(),
                    flow.x, flow.y, flow.z, level.getFluidState(probe), zombie.getAttributeValue(Attributes.MOVEMENT_SPEED),
                    zombie.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY));
        }
    }

    private static void step(ServerLevel level, int t) {
        for (int i = 0; i < LANES.size(); i++) {
            Lane lane = LANES.get(i);
            Zombie zombie = MOBS.get(i);
            if (!zombie.isAlive()) {
                continue;
            }
            if (lane.nav()) {
                if (zombie.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation navigation) {
                    navigation.setCanFloat(true);
                }
                if (zombie.getNavigation().isDone() || t % 20 == 0) {
                    zombie.getNavigation().moveTo(lane.goalX(), lane.goalY(), lane.z() + 0.5, 1.0);
                }
            } else {
                zombie.getMoveControl().setWantedPosition(lane.goalX(), lane.goalY(), lane.z() + 0.5, 1.0);
            }
            if (zombie.isInWater() && zombie.getFluidHeight(FluidTags.WATER) > zombie.getFluidJumpThreshold()
                    && zombie.getRandom().nextFloat() < 0.8F) {
                zombie.getJumpControl().jump();
            }
            if (t % 20 == 0) {
                Vec3 motion = zombie.getDeltaMovement();
                Incursion.LOGGER.info("PROBE lane {} t={} x={} y={} vx={} inWater={} ground={} navDone={}", lane.name(), t,
                        round(zombie.getX()), round(zombie.getY()), round(motion.x), zombie.isInWater(), zombie.onGround(),
                        zombie.getNavigation().isDone());
            }
        }
        if (t == RUN_TICKS - 1) {
            for (int i = 0; i < LANES.size(); i++) {
                Lane lane = LANES.get(i);
                Zombie zombie = MOBS.get(i);
                Incursion.LOGGER.info("PROBE RESULT lane {} start x={} end x={} y={} progress={} blocks in {} s", lane.name(), START_X,
                        round(zombie.getX()), round(zombie.getY()), round(START_X - zombie.getX()), RUN_TICKS / 20);
            }
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
