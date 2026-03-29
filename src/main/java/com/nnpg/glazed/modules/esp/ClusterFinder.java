package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.class_1109;
import net.minecraft.class_1113;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_1923;
import net.minecraft.class_1935;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_238;
import net.minecraft.class_2561;
import net.minecraft.class_2680;
import net.minecraft.class_2791;
import net.minecraft.class_2818;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_3414;
import net.minecraft.class_3417;
import net.minecraft.class_368;
import net.minecraft.class_374;

public class ClusterFinder
extends Module {
    final private SettingGroup sgGeneral;
    final private SettingGroup sgNotifications;
    final private Setting<SettingColor> chunkColor;
    final private Setting<Boolean> showToast;
    final private Setting<Boolean> playSound;
    static final private double CHUNK_HEIGHT = 64.0;
    static final private double CHUNK_THICKNESS = 0.5;
    static final private int CLUSTER_THRESHOLD = 5;
    static final private int THREAD_POOL_SIZE = 2;
    final private Set<class_1923> flaggedChunks;
    final private Map<class_1923, Long> lastNotificationTimes;
    final private Queue<Long> recentNotifications;
    private ExecutorService threadPool;
    private boolean active;

    public ClusterFinder() {
        super(ZincAddon.esp, "ClusterFinder", "Finds Amethyst Clusters.");
        this.sgGeneral = this.settings.createGroup("General");
        this.sgNotifications = this.settings.createGroup("Notifications");
        this.chunkColor = this.sgGeneral.add((Setting)((ColorSetting.Builder)new ColorSetting.Builder().name("chunk-color")).defaultValue(new SettingColor(180, 50, 230, 60)).build());
        this.showToast = this.sgNotifications.add((Setting)((BoolSetting.Builder)((BoolSetting.Builder)new BoolSetting.Builder().name("show-toast")).defaultValue((Object)true)).build());
        this.playSound = this.sgNotifications.add((Setting)((BoolSetting.Builder)((BoolSetting.Builder)new BoolSetting.Builder().name("play-sound")).defaultValue((Object)true)).build());
        this.flaggedChunks = ConcurrentHashMap.newKeySet();
        this.lastNotificationTimes = new ConcurrentHashMap<class_1923, Long>();
        this.recentNotifications = new ConcurrentLinkedQueue<Long>();
        this.active = false;
    }

    public void onActivate() {
        this.active = true;
        this.threadPool = Executors.newFixedThreadPool(2);
        this.flaggedChunks.clear();
        for (class_2791 chunk : Utils.chunks()) {
            if (!(chunk instanceof class_2818)) continue;
            class_2818 wc = (class_2818)chunk;
            this.threadPool.submit(() -> this.lambda$onActivate$0(wc));
        }
    }

    public void onDeactivate() {
        this.active = false;
        if (this.threadPool != null) {
            this.threadPool.shutdownNow();
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (this.threadPool != null) {
            this.threadPool.submit(() -> this.lambda$onChunkLoad$1(event));
        }
    }

    private void scanChunk(class_2818 chunk) {
        if (!this.active || chunk == null) {
            return;
        }
        class_1923 cpos = chunk.method_12004();
        int count = 0;
        int topY = chunk.method_31607() + chunk.method_31605();
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                for (int y = chunk.method_31607(); y < topY; ++y) {
                    class_2680 state = chunk.method_8320(new class_2338(cpos.method_8326() + x, y, cpos.method_8328() + z));
                    if (!state.method_27852(class_2246.field_27161)) continue;
                    ++count;
                }
            }
        }
        if (count >= 5 && this.flaggedChunks.add(cpos)) {
            int finalCount = count;
            this.mc.execute(() -> this.lambda$scanChunk$2(finalCount));
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color color = new Color((Color)this.chunkColor.get());
        for (class_1923 cp : this.flaggedChunks) {
            class_238 box = new class_238((double)cp.method_8326(), 64.0, (double)cp.method_8328(), (double)(cp.method_8327() + 1), 64.5, (double)(cp.method_8329() + 1));
            event.renderer.box(box, color, color, ShapeMode.Both, 0);
        }
    }

    private void lambda$scanChunk$2(int finalCount) {
        if (((Boolean)this.showToast.get()).booleanValue()) {
            this.mc.method_1566().method_1999((class_368)new ClusterToast(new class_1799((class_1935)class_1802.field_27069), (class_2561)class_2561.method_43470((String)"Cluster Found"), (class_2561)class_2561.method_43470((String)("Count " + finalCount))));
        }
        if (((Boolean)this.playSound.get()).booleanValue()) {
            this.mc.method_1483().method_4873((class_1113)class_1109.method_4757((class_3414)class_3417.field_26980, 1.0f, 1.5f));
        }
    }

    private void lambda$onChunkLoad$1(ChunkDataEvent event) {
        this.scanChunk(event.chunk());
    }

    private void lambda$onActivate$0(class_2818 wc) {
        this.scanChunk(wc);
    }

    private static class ClusterToast
    implements class_368 {
        final private class_1799 icon;
        final private class_2561 title;
        final private class_2561 description;
        private long startTime = -1L;
        private class_368.class_369 visibility = class_368.class_369.field_2210;

        public ClusterToast(class_1799 icon, class_2561 title, class_2561 description) {
            super();
            this.icon = icon;
            this.title = title;
            this.description = description;
        }

        public class_368.class_369 method_61988() {
            return this.visibility;
        }

        public void method_61989(class_374 manager, long currentTime) {
            if (this.startTime == -1L) {
                this.startTime = currentTime;
            }
            if (currentTime - this.startTime >= 5000L) {
                this.visibility = class_368.class_369.field_2209;
            }
        }

        public void method_1986(class_332 context, class_327 textRenderer, long currentTime) {
            context.method_25294(0, 0, 160, 32, -1442840576);
            int purple = -6736897;
            context.method_25294(0, 0, 160, 1, purple);
            context.method_25294(0, 31, 160, 32, purple);
            context.method_25294(0, 1, 1, 31, purple);
            context.method_25294(159, 1, 160, 31, purple);
            if (this.icon != null && !this.icon.method_7960()) {
                context.method_51427(this.icon, 8, 8);
            }
            if (this.title != null) {
                context.method_51439(textRenderer, this.title, 32, 7, 0xFFFF00, false);
            }
            if (this.description != null) {
                context.method_51439(textRenderer, this.description, 32, 18, 0xFFFFFF, false);
            }
        }
    }
}
