package com.nnpg.glazed;

import com.nnpg.glazed.modules.esp.*;
import com.nnpg.glazed.modules.main.*;
import com.nnpg.glazed.modules.pvp.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;

public class GlazedAddon extends MeteorAddon {

    public static final Category CATEGORY = new Category("Glazed", new ItemStack(Items.CAKE));
    public static final Category esp = new Category("Glazed ESP ", new ItemStack(Items.VINE));
    public static final Category pvp = new Category("Glazed PVP", new ItemStack(Items.DIAMOND_SWORD));

    public static int MyScreenVERSION = 16;

    @Override
    public void onInitialize() {
        Modules.get().add(new PlayerDetection());
        Modules.get().add(new AutoSell());
        Modules.get().add(new HideScoreboard());
        Modules.get().add(new CrystalMacro());
        Modules.get().add(new AnchorMacro());
        Modules.get().add(new OneByOneHoles());
        Modules.get().add(new KelpESP());
        Modules.get().add(new DripstoneESP());
        Modules.get().add(new RotatedDeepslateESP());
        Modules.get().add(new CrateBuyer());
        Modules.get().add(new WanderingESP());
        Modules.get().add(new VillagerESP());
        Modules.get().add(new AdvancedStashFinder());
        Modules.get().add(new LamaESP());
        Modules.get().add(new PillagerESP());
        Modules.get().add(new HoleTunnelStairsESP());
        Modules.get().add(new CoveredHole());
        Modules.get().add(new ClusterFinder());
        Modules.get().add(new OrderDropper());
        Modules.get().add(new CollectibleESP());
        Modules.get().add(new ChunkFinder());
        Modules.get().add(new RegionMap());
        Modules.get().add(new NoBlockInteract());
        Modules.get().add(new BeehiveESP());
        Modules.get().add(new DoubleAnchorMacro());
        Modules.get().add(new AutoDoubleHand());
        Modules.get().add(new SweetBerryESP());
        Modules.get().add(new PistonESP());
        Modules.get().add(new HoverTotem());
        Modules.get().add(new SkeletonESP());
        Modules.get().add(new RainNoti());
        Modules.get().add(new BreachSwap());
        Modules.get().add(new FakeScoreboard());
        Modules.get().add(new AutoInvTotem());
        Modules.get().add(new FreecamMining());
        Modules.get().add(new BedrockVoidESP());
        Modules.get().add(new UIHelper());
        Modules.get().add(new InvisESP());
        Modules.get().add(new LightESP());
        Modules.get().add(new PremiumTunnelBaseFinder());
        Modules.get().add(new AdminList());
        Modules.get().add(new AutoTreeFarmer());
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        MyScreen.checkVersionOnServerJoin();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        MyScreen.resetSessionCheck();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(esp);
        Modules.registerCategory(pvp);
    }

    @Override
    public String getPackage() {
        return "com.nnpg.glazed";
    }
}
