package com.song.castle_in_the_sky.events;

import com.google.common.collect.HashMultimap;
import com.song.castle_in_the_sky.CastleInTheSky;
import com.song.castle_in_the_sky.blocks.block_entities.LaputaCoreBE;
import com.song.castle_in_the_sky.config.ConfigCommon;
import com.song.castle_in_the_sky.effects.EffectRegister;
import com.song.castle_in_the_sky.features.CastleStructure;
import com.song.castle_in_the_sky.items.ItemsRegister;
import com.song.castle_in_the_sky.items.LevitationStone;
import com.song.castle_in_the_sky.network.Channel;
import com.song.castle_in_the_sky.network.ClientHandlerClass;
import com.song.castle_in_the_sky.network.ServerToClientInfoPacket;
import com.song.castle_in_the_sky.utils.CapabilityCastle;
import com.song.castle_in_the_sky.utils.MyTradingRecipe;
import com.song.castle_in_the_sky.utils.RandomTradeBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.EntityDamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.StructureSpawnListGatherEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerEvents {

    /**
     * Helper method that handles setting up the map to multimap relationship to help prevent issues.
     */
    private static void associateBiomeToConfiguredStructure(Map<StructureFeature<?>, HashMultimap<ConfiguredStructureFeature<?, ?>, ResourceKey<Biome>>> STStructureToMultiMap, ConfiguredStructureFeature<?, ?> configuredStructureFeature, ResourceKey<Biome> biomeRegistryKey) {
        STStructureToMultiMap.putIfAbsent(configuredStructureFeature.feature, HashMultimap.create());
        HashMultimap<ConfiguredStructureFeature<?, ?>, ResourceKey<Biome>> configuredStructureToBiomeMultiMap = STStructureToMultiMap.get(configuredStructureFeature.feature);
        if(configuredStructureToBiomeMultiMap.containsValue(biomeRegistryKey)) {
            CastleInTheSky.LOGGER.error("""
                    Detected 2 ConfiguredStructureFeatures that share the same base StructureFeature trying to be added to same biome. One will be prevented from spawning.
                    This issue happens with vanilla too and is why a Snowy Village and Plains Village cannot spawn in the same biome because they both use the Village base structure.
                    The two conflicting ConfiguredStructures are: {}, {}
                    The biome that is attempting to be shared: {}
                """,
                    BuiltinRegistries.CONFIGURED_STRUCTURE_FEATURE.getId(configuredStructureFeature),
                    BuiltinRegistries.CONFIGURED_STRUCTURE_FEATURE.getId(configuredStructureToBiomeMultiMap.entries().stream().filter(e -> e.getValue() == biomeRegistryKey).findFirst().get().getKey()),
                    biomeRegistryKey
            );
        }
        else{
            configuredStructureToBiomeMultiMap.put(configuredStructureFeature, biomeRegistryKey);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onGatherStructureSpawn(StructureSpawnListGatherEvent event){
        if (event.getStructure() instanceof CastleStructure){
            event.addEntitySpawn(MobCategory.MONSTER, new MobSpawnSettings.SpawnerData(EntityType.ZOMBIE, 3, 2, 5));
            event.addEntitySpawn(MobCategory.MONSTER, new MobSpawnSettings.SpawnerData(EntityType.SKELETON, 3, 2, 5));
            event.addEntitySpawn(MobCategory.MONSTER, new MobSpawnSettings.SpawnerData(EntityType.SPIDER, 1, 2, 3));
        }

    }

    // I have removed the Japanese and Chinese incantation because my stupid Windows PC cannot understand it
    // Coding in Windows sucks
    private static final Set<String> DESTRUCTION_INCANTATIONS = new HashSet<>(Arrays.asList("BARUSU", "BALSE", "BALUS", "バルス", "巴鲁斯"));
    private static final int SEARCH_RADIUS = 5;
    private static final int SEARCH_RADIUS2 = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final int SEARCH_HEIGHT=3;

    @SubscribeEvent
    public void onPlayerChat(final ServerChatEvent event){
        if (DESTRUCTION_INCANTATIONS.contains(event.getMessage())){
            if(ConfigCommon.DISABLE_INCANTATION.get()){
                event.getPlayer().sendMessage(new TranslatableComponent("info."+CastleInTheSky.MOD_ID+".destruction_disabled").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), event.getPlayer().getUUID());
                return;
            }

            ServerPlayer player = event.getPlayer();
            AtomicBoolean warned = new AtomicBoolean(false);
            player.getCapability(CapabilityCastle.CASTLE_CAPS).ifPresent(
                    (data -> warned.set(data.isIncantationWarned()))
            );
            boolean found = false;
            if (player.getMainHandItem().getItem() instanceof LevitationStone){
                for (int dy=-SEARCH_HEIGHT; !found && dy<=SEARCH_HEIGHT; dy++){
                    for (int dx=-SEARCH_RADIUS; !found && dx<+SEARCH_RADIUS; dx++){
                        for (int dz=-SEARCH_RADIUS; !found && dz<+SEARCH_RADIUS; dz++){
                            if (dx*dx+dy*dy < SEARCH_RADIUS2){
                                BlockEntity blockEntity = player.level.getBlockEntity(player.blockPosition().offset(dx, dy, dz));
                                if (blockEntity instanceof LaputaCoreBE && !((LaputaCoreBE) blockEntity).isActive()){
                                    if (! warned.get()){
                                        player.sendMessage(new TranslatableComponent("info."+CastleInTheSky.MOD_ID+".destruction_warning").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), player.getUUID());
                                        player.getCapability(CapabilityCastle.CASTLE_CAPS).ifPresent((data -> {
                                            data.setIncantationWarned(true);
                                            data.setWarningCD();
                                        }));
                                        if (ConfigCommon.SILENT_INCANTATION.get()){
                                            event.setCanceled(true);
                                        }
                                        return;
                                    }
                                    else {
                                        player.getInventory().removeItem(player.getMainHandItem());
                                        ((LaputaCoreBE) blockEntity).setDestroying(true);
                                        ((LaputaCoreBE) blockEntity).setActivatedInitPos(player.getEyePosition());
                                        player.getCapability(CapabilityCastle.CASTLE_CAPS).ifPresent((data -> data.setIncantationWarned(false)));
                                        found = true;
                                        for (ServerPlayer playerOther: ((ServerLevel)player.level).players()){
                                            playerOther.sendMessage(new TranslatableComponent("info."+CastleInTheSky.MOD_ID+".incantation_casted", player.getName()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), playerOther.getUUID());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(! found){
                    event.getPlayer().sendMessage(new TranslatableComponent("info."+CastleInTheSky.MOD_ID+".crystal_not_found").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), event.getPlayer().getUUID());
                }
            }
            else {
                event.getPlayer().sendMessage(new TranslatableComponent("info."+CastleInTheSky.MOD_ID+".item_not_hold").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), event.getPlayer().getUUID());
            }

            if (ConfigCommon.SILENT_INCANTATION.get()){
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void tickCap(final TickEvent.PlayerTickEvent event){
        if (! event.player.level.isClientSide()){
            event.player.getCapability(CapabilityCastle.CASTLE_CAPS).ifPresent((CapabilityCastle.Data::tick));
        }
    }

    @SubscribeEvent
    public void onAttachCapEntity(final AttachCapabilitiesEvent<Entity> event){
        if (event.getObject() instanceof Player){
            event.addCapability(new ResourceLocation(CastleInTheSky.MOD_ID, "castle_caps"), new CapabilityCastle());
        }
    }

    @SubscribeEvent
    public void registerCaps(RegisterCapabilitiesEvent event) {
        event.register(CapabilityCastle.class);
    }

    @SubscribeEvent
    public void onVillageTradeRegister(VillagerTradesEvent event){
        for (MyTradingRecipe recipe: ConfigCommon.MY_TRADING_RECIPES){
            if((recipe.getItem1()!=null || recipe.getItem2() != null) && Objects.requireNonNull(event.getType().getRegistryName()).toString().equals(recipe.getStringProfession())){
                int level = recipe.getLevel();
                List<VillagerTrades.ItemListing> tmp = event.getTrades().get(level);
                ArrayList<VillagerTrades.ItemListing> mutableTrades = new ArrayList<>(tmp);
                mutableTrades.add(
                        new RandomTradeBuilder(64, 25, 0.05f)
                                .setPrice(recipe.getItem1(), recipe.price1Min().get(), recipe.price1Max().get())
                                .setPrice2(recipe.getItem2(), recipe.price2Min().get(), recipe.price2Max().get())
                                .setForSale(recipe.getOutput(), recipe.outputMin().get(), recipe.outputMax().get())
                                .build());
                event.getTrades().put(level, mutableTrades);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void modifyStructureSpawnList(StructureSpawnListGatherEvent event){
//        if(event.getStructure() == StructureRegister.CASTLE_IN_THE_SKY.get()){
//            // No mob should spawn here
//            // TODO: add misc spawn like fish, iron golems, etc..
//            List<MobSpawnInfo.Spawners> spawners = event.getEntitySpawns(EntityClassification.MONSTER);
//            for(MobSpawnInfo.Spawners spawner: spawners){
//                event.removeEntitySpawn(EntityClassification.MONSTER, spawner);
//            }
//        }
    }

    @SubscribeEvent
    public void onBlockBreak(PlayerEvent.BreakSpeed event){
        Player playerEntity = event.getPlayer();
        if(playerEntity.hasEffect(EffectRegister.SACRED_CASTLE_EFFECT.get()) && !playerEntity.isCreative()){
            event.setCanceled(true);
            if(playerEntity.level.isClientSide()){
                ClientHandlerClass.showSacredCastleInfoBreak();
            }
        }
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event){
        Entity entity = event.getEntity();
        if(entity instanceof Player && ((Player)entity).hasEffect(EffectRegister.SACRED_CASTLE_EFFECT.get()) && !((Player) entity).isCreative()){
            event.setCanceled(true);
            if(entity instanceof ServerPlayer){
                Channel.INSTANCE.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) entity),
                        new ServerToClientInfoPacket(new TranslatableComponent(String.format("info.%s.sacred_castle_effect.place", CastleInTheSky.MOD_ID)).withStyle(ChatFormatting.RED).withStyle(ChatFormatting.BOLD)));
            }
        }
    }

    @SubscribeEvent
    public void onMobDrop(LivingDropsEvent event){
        DamageSource damageSource = event.getSource();
        if(damageSource instanceof EntityDamageSource){
            Entity killer = damageSource.getEntity();
            if(killer instanceof LivingEntity && ((LivingEntity) killer).hasEffect(EffectRegister.SACRED_CASTLE_EFFECT.get())){
                LivingEntity dropper = event.getEntityLiving();
                if(dropper.getRandom().nextDouble()< ConfigCommon.YELLOW_KEY_DROP_RATE.get()){
                    event.getDrops().add(new ItemEntity(dropper.level, dropper.position().x, dropper.position().y, dropper.position().z, new ItemStack(ItemsRegister.YELLOW_KEY.get())));
                }
                if(dropper.getRandom().nextDouble()<ConfigCommon.BLUE_KEY_DROP_RATE.get()){
                    event.getDrops().add(new ItemEntity(dropper.level, dropper.position().x, dropper.position().y, dropper.position().z, new ItemStack(ItemsRegister.BLUE_KEY.get())));
                }
                if(dropper.getRandom().nextDouble()<ConfigCommon.RED_KEY_DROP_RATE.get()){
                    event.getDrops().add(new ItemEntity(dropper.level, dropper.position().x, dropper.position().y, dropper.position().z, new ItemStack(ItemsRegister.RED_KEY.get())));
                }
            }
        }
    }
}
