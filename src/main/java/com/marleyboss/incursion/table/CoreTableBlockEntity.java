package com.marleyboss.incursion.table;

import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.ChatFormatting;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * State of one Core Table: the item in its slot and the running job (enchantment, target level, time left).
 * The item stays in the table while the job runs; the renderer shows it floating above the table. The slot is not
 * exposed to hoppers (this class is no {@link Container}).
 */
public class CoreTableBlockEntity extends BlockEntity {
    private final SimpleContainer container = new SimpleContainer(1);
    private int jobState = CoreTableLogic.JOB_IDLE;
    private String jobKey = "";
    private int jobLevel;
    private int jobTotal;
    private int jobLeft;
    /** Server only: ticks until the next run of the working loop sound. */
    private int loopCooldown;

    public CoreTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORE_TABLE.get(), pos, state);
        container.addListener(this::onContainerChanged);
    }

    public SimpleContainer getContainer() {
        return container;
    }

    public ItemStack getDisplayStack() {
        return container.getItem(0);
    }

    public int getJobState() {
        return jobState;
    }

    public String getJobKey() {
        return jobKey;
    }

    public int getJobLevel() {
        return jobLevel;
    }

    public int getJobTotal() {
        return jobTotal;
    }

    public int getJobLeft() {
        return jobLeft;
    }

    /** 0 to 1: how far the running job is (client: counted down locally between the sync packets). */
    public float getProgress() {
        if (jobState == CoreTableLogic.JOB_DONE) {
            return 1.0F;
        }
        return jobTotal <= 0 ? 0.0F : 1.0F - jobLeft / (float) jobTotal;
    }

    private void onContainerChanged(Container changed) {
        setChanged();
        if (level == null || level.isClientSide) {
            return;
        }
        if (jobState == CoreTableLogic.JOB_DONE && changed.getItem(0).isEmpty()) {
            resetJob();
        }
        syncToClients();
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private void setBlockJob(int job) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(CoreTableBlock.JOB) && state.getValue(CoreTableBlock.JOB) != job) {
            level.setBlock(worldPosition, state.setValue(CoreTableBlock.JOB, job), Block.UPDATE_ALL);
        }
    }

    /** Starts enchanting (server): the item in the slot gets {@code key} at {@code level} when the time is up. */
    public void startJob(String key, int level, int ticks) {
        this.jobState = CoreTableLogic.JOB_WORKING;
        this.jobKey = key;
        this.jobLevel = level;
        this.jobTotal = Math.max(1, ticks);
        this.jobLeft = this.jobTotal;
        setBlockJob(1);
        setChanged();
        syncToClients();
        this.loopCooldown = 0;
        if (this.level != null) {
            this.level.playSound(null, worldPosition, ModSounds.TABLE_START.get(), SoundSource.BLOCKS, 1.2F, 1.0F);
        }
    }

    private void resetJob() {
        jobState = CoreTableLogic.JOB_IDLE;
        jobKey = "";
        jobLevel = 0;
        jobTotal = 0;
        jobLeft = 0;
        setBlockJob(0);
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CoreTableBlockEntity table) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (table.jobState == CoreTableLogic.JOB_IDLE) {
            // A quiet, rare glassy chime so that the table feels alive even without a job.
            if (level.getGameTime() % 400 == Math.floorMod(pos.asLong(), 400L) && level.random.nextInt(3) == 0) {
                level.playSound(null, pos, ModSounds.TABLE_AMBIENT.get(), SoundSource.BLOCKS, 0.3F,
                        0.9F + level.random.nextFloat() * 0.3F);
            }
            return;
        }
        if (table.jobState != CoreTableLogic.JOB_WORKING) {
            return;
        }
        table.jobLeft--;
        float progress = table.getProgress();
        if (--table.loopCooldown <= 0) {
            float pitch = 0.9F + 0.35F * progress;
            level.playSound(null, pos, ModSounds.TABLE_LOOP.get(), SoundSource.BLOCKS, 0.8F, pitch);
            table.loopCooldown = Math.max(20, Math.round(60.0F / pitch) - 2);
        }
        if (level.random.nextInt(40) == 0) {
            level.playSound(null, pos, ModSounds.TABLE_ZAP.get(), SoundSource.BLOCKS, 0.6F,
                    0.8F + level.random.nextFloat() * 0.6F);
        }
        if (table.jobLeft % 100 == 0) {
            table.setChanged();
            table.syncToClients();
        }
        if (table.jobLeft <= 0) {
            table.finishJob(serverLevel);
        }
    }

    /** Client: counts the time down between sync packets and throws the particles (see {@link CoreTableEffects}). */
    public static void clientTick(Level level, BlockPos pos, BlockState state, CoreTableBlockEntity table) {
        if (table.jobState == CoreTableLogic.JOB_WORKING && table.jobLeft > 0) {
            table.jobLeft--;
        }
        CoreTableEffects.spawn(level, pos, state.hasProperty(CoreTableBlock.JOB) ? state.getValue(CoreTableBlock.JOB) : 0,
                table.getProgress(), table.jobLeft);
    }

    private void finishJob(ServerLevel serverLevel) {
        ItemStack stack = container.getItem(0);
        Optional<Holder.Reference<Enchantment>> holder = serverLevel.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolder(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(jobKey)));
        Component name = Component.literal(jobKey);
        if (!stack.isEmpty() && holder.isPresent()) {
            final int newLevel = jobLevel;
            final Holder<Enchantment> enchantment = holder.get();
            EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(enchantment, newLevel));
            name = enchantment.value().description();
        }
        jobState = CoreTableLogic.JOB_DONE;
        jobLeft = 0;
        setBlockJob(2);
        setChanged();
        syncToClients();
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + 1.2;
        double z = worldPosition.getZ() + 0.5;
        serverLevel.playSound(null, worldPosition, ModSounds.TABLE_FINISH.get(), SoundSource.BLOCKS, 1.3F, 1.0F);
        serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 60, 0.4, 0.4, 0.4, 0.35);
        serverLevel.sendParticles(ParticleTypes.END_ROD, x, y, z, 40, 0.3, 0.5, 0.3, 0.08);
        serverLevel.sendParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        for (int i = 0; i < 48; i++) { // a ring of light rushing outwards
            double angle = i * Math.PI * 2.0 / 48.0;
            serverLevel.sendParticles(ParticleTypes.END_ROD, x, worldPosition.getY() + 1.05, z, 0,
                    Math.cos(angle), 0.02, Math.sin(angle), 0.25);
        }
        Component message = Component.translatable("message.incursion.table.finished", name,
                CoreTableLogic.roman(jobLevel)).withStyle(ChatFormatting.LIGHT_PURPLE);
        for (Player player : serverLevel.players()) {
            if (player.distanceToSqr(x, y, z) < 24 * 24) {
                player.displayClientMessage(message, true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Saving and syncing
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Item", container.getItem(0).saveOptional(registries));
        tag.putInt("JobState", jobState);
        tag.putString("JobKey", jobKey);
        tag.putInt("JobLevel", jobLevel);
        tag.putInt("JobTotal", jobTotal);
        tag.putInt("JobLeft", jobLeft);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        container.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("Item")));
        jobState = tag.getInt("JobState");
        jobKey = tag.getString("JobKey");
        jobLevel = tag.getInt("JobLevel");
        jobTotal = tag.getInt("JobTotal");
        jobLeft = tag.getInt("JobLeft");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
