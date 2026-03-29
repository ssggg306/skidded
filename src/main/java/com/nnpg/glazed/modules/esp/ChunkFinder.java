package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;

public class ChunkFinder extends Module {
    private static final long WORLD_SEED = 6608149111735331168L;
    private static final float RENDER_Y = 63.0f;
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final int ORIGIN_SEARCH_RADIUS = 8;
    private static final int MAX_PATH_LENGTH = 16;
    private static final int MIN_ORIGIN_WEIGHT = 6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> simDistance = sgGeneral.add(new IntSetting.Builder()
            .name("sim-distance").description("Simulation distance in chunks.").defaultValue(16).min(2).sliderMax(16).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
            .name("sensitivity").description("Detection sensitivity.").defaultValue(3).min(1).sliderMax(20).build());
    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
            .name("alpha").description("Overlay alpha.").defaultValue(120).min(1).sliderMax(255).build());
    private final Setting<Double> thickness = sgGeneral.add(new DoubleSetting.Builder()
            .name("thickness").description("Height of the chunk overlay.").defaultValue(0.05).min(0.01).sliderMax(4.0).build());

    private final Setting<Boolean> detectAmethyst = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-amethyst").description("Detect amethyst clusters.").defaultValue(true).build());
    private final Setting<Boolean> detectVines = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-vines").description("Detect vines.").defaultValue(true).build());
    private final Setting<Boolean> detectCaveVines = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-cave-vines").description("Detect cave vines.").defaultValue(true).build());
    private final Setting<Boolean> detectKelp = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-kelp").description("Detect kelp.").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-rotated-deepslate").description("Detect rotated deepslate.").defaultValue(true).build());

    private final Set<ChunkPos> flaggedArea = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Float> scoreCache = new ConcurrentHashMap<>();

    private final ExecutorService engine = Executors.newFixedThreadPool(1);
    private volatile boolean isRunning = false;
    private int tickCounter = 0;

    public SuspiciousChunkFinder() {
        super(YourAddon, "sus-chunk-finder",
                "Finds base locations using plant and cluster growth anomalies.");
    }

    @Override
    public void onActivate() {
        clear();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        flaggedArea.clear();
        scoreCache.clear();
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS || isRunning) return;
        tickCounter = 0;

        isRunning = true;
        engine.submit(() -> {
            try {
                deepCoreScan();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isRunning = false;
            }
        });
    }

    private void deepCoreScan() {
        World world = mc.world;
        if (world == null || mc.player == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int radius = simDistance.get();
        int sens = sensitivity.get();
        int enabledIndicators = getEnabledIndicatorCount();
        boolean requireAmethyst = detectAmethyst.get();
        boolean requireGrowth = detectVines.get() || detectKelp.get() || detectCaveVines.get();
        boolean useRotated = detectRotatedDeepslate.get();

        Map<ChunkPos, Float> frameScores = new HashMap<>();
        Map<ChunkPos, Integer> amethystHitsMap = new HashMap<>();
        Map<ChunkPos, Integer> growthHitsMap = new HashMap<>();
        Map<ChunkPos, Integer> rotatedHitsMap = new HashMap<>();
        Map<ChunkPos, Integer> sourceHits = new HashMap<>();

        float longTimeThreshold = getLongTimeThreshold(sens, enabledIndicators);
        int minAmethystHits = getRequiredAmethystHits(sens);
        int minGrowthHits = getRequiredGrowthHits(sens);
        int minRotatedHits = getRequiredRotatedHits(sens);
        int minTypes = getRequiredTypes(enabledIndicators);
        int minIndicators = getRequiredIndicatorCount(enabledIndicators);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                if (!world.isChunkLoaded(cp.x, cp.z)) continue;

                ChunkScanResult result = runChunkSimulation(cp, world, sens);
                if (result.score <= 0.0f) continue;
                if (result.indicatorCount < minIndicators) continue;
                if (Integer.bitCount(result.typeMask) < minTypes) continue;

                boolean rotatedOk = useRotated && result.rotatedHits >= minRotatedHits;
                if (!rotatedOk) {
                    if (requireAmethyst && result.amethystHits < minAmethystHits) continue;
                    if (requireGrowth && result.growthHits < minGrowthHits) continue;
                }

                frameScores.put(cp, result.score);
                amethystHitsMap.put(cp, result.amethystHits);
                growthHitsMap.put(cp, result.growthHits);
                rotatedHitsMap.put(cp, result.rotatedHits);
                if (result.score >= longTimeThreshold) {
                    mergeSourceHits(sourceHits, result.sourceHitsLocal);
                }
                if (rotatedOk) {
                    addSourceHit(sourceHits, cp, result.rotatedHits);
                }
            }
        }

        if (sourceHits.isEmpty()) {
            mc.execute(() -> { flaggedArea.clear(); scoreCache.clear(); });
            return;
        }

        List<ChunkPos> sourceList = new ArrayList<>(sourceHits.keySet());
        sourceList.sort(Comparator.comparingInt(pos -> sourceHits.getOrDefault(pos, 0)).reversed());
        if (sourceList.size() > 2) sourceList = sourceList.subList(0, 2);

        if (sourceList.isEmpty() || sourceHits.getOrDefault(sourceList.get(0), 0) < MIN_ORIGIN_WEIGHT) {
            mc.execute(() -> { flaggedArea.clear(); scoreCache.clear(); });
            return;
        }

        Set<ChunkPos> newArea = new HashSet<>();
        if (sourceList.size() >= 2) {
            ChunkPos c1 = sourceList.get(0);
            ChunkPos c2 = sourceList.get(1);
            int ddx = c2.x - c1.x;
            int ddz = c2.z - c1.z;
            int dist = Math.max(Math.abs(ddx), Math.abs(ddz));
            if (dist > MAX_PATH_LENGTH) dist = MAX_PATH_LENGTH;
            for (int k = 0; k <= dist; k++) {
                int px = c1.x + ddx * k / Math.max(1, dist);
                int pz = c1.z + ddz * k / Math.max(1, dist);
                addPathCell(newArea, px, pz, 1);
            }
        } else {
            ChunkPos c1 = sourceList.get(0);
            addPathCell(newArea, c1.x, c1.z, 1);
        }

        mc.execute(() -> {
            flaggedArea.clear();
            flaggedArea.addAll(newArea);
            scoreCache.clear();
            scoreCache.putAll(frameScores);
        });
    }

    private ChunkScanResult runChunkSimulation(ChunkPos cp, World world, int sens) {
        WorldChunk chunk = world.getChunk(cp.x, cp.z);
        if (chunk == null || chunk.isEmpty()) return ChunkScanResult.empty();

        float score = 0.0f;
        int typeMask = 0;
        int indicatorCount = 0;
        int amethystCount = 0;
        int growthCount = 0;
        int rotatedCount = 0;
        Map<ChunkPos, Integer> sourceHitsLocal = new HashMap<>();
        Map<ChunkPos, Long> popSeedCache = new HashMap<>();
        ChunkRandom random = new ChunkRandom(Random.create());
        long popSeed = random.setPopulationSeed(WORLD_SEED, cp.getStartX(), cp.getStartZ());

        int startX = cp.getStartX();
        int startZ = cp.getStartZ();
        int bottomY = world.getBottomY();
        ChunkSection[] sections = chunk.getSectionArray();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int sectionY = bottomY + i * 16;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) continue;
                        pos.set(startX + lx, sectionY + ly, startZ + lz);
                        BlockAnalysisResult analysis = analyzeBlock(pos, state, world, random, popSeed);
                        if (analysis.points > 0.0f) score += analysis.points;
                        if (analysis.typeMask != 0) {
                            typeMask |= analysis.typeMask;
                            indicatorCount += analysis.indicatorHits;
                            amethystCount += analysis.amethystHits;
                            growthCount += analysis.growthHits;
                            rotatedCount += analysis.rotatedHits;
                            if (analysis.sourceWeight > 0 && analysis.originType != null) {
                                ChunkPos source = findBestOriginChunk(pos, analysis.originType, analysis.originActual, popSeedCache);
                                if (source != null) addSourceHit(sourceHitsLocal, source, analysis.sourceWeight);
                            }
                        }
                    }
                }
            }
        }

        return new ChunkScanResult(score * (sens / 3.0f), typeMask, indicatorCount, amethystCount, growthCount, rotatedCount, sourceHitsLocal);
    }

    private BlockAnalysisResult analyzeBlock(BlockPos pos, BlockState state, World world, ChunkRandom random, long popSeed) {
        Block block = state.getBlock();
        float points = 0.0f;
        int typeMask = 0;
        int indicatorHits = 0, amethystHits = 0, growthHits = 0, rotatedHits = 0;
        int sourceWeight = 0;
        OriginType originType = null;
        int originActual = 0;

        if (detectAmethyst.get() && (block instanceof AmethystClusterBlock || block == Blocks.AMETHYST_CLUSTER)) {
            int expected = expectedStage(random, popSeed, pos);
            int actual = getAmethystStage(state);
            if (actual > expected + 1) {
                points += (actual - expected) * 12.0f;
                sourceWeight += 2;
                originType = OriginType.AMETHYST;
                originActual = actual;
                typeMask |= IndicatorType.AMETHYST.mask;
                indicatorHits++; amethystHits++;
            }
        }

        if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
            float p = growthScore(state, random, popSeed, pos, 1.2f, 28);
            points += p;
            typeMask |= IndicatorType.KELP.mask;
            indicatorHits++; growthHits++;
            if (p > 0) {
                sourceWeight += 1;
                if (originType == null) { originType = OriginType.KELP; originActual = state.get(Properties.AGE_25); }
            }
        }

        if (detectCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
            float p = growthScore(state, random, popSeed, pos, 1.4f, 4);
            points += p;
            typeMask |= IndicatorType.CAVE_VINES.mask;
            indicatorHits++; growthHits++;
            if (p > 0) {
                sourceWeight += 1;
                if (originType == null) { originType = OriginType.CAVE_VINES; originActual = state.get(Properties.AGE_25); }
            }
        }

        if (detectVines.get() && block instanceof VineBlock) {
            int len = getVineLength(world, pos, block, 24);
            int expected = expectedLength(random, popSeed, pos, 3, 8);
            if (len > expected + 20) {
                points += (len - expected) * 0.7f;
                sourceWeight += 1;
                if (originType == null) { originType = OriginType.VINES; originActual = len; }
            }
            typeMask |= IndicatorType.VINES.mask;
            indicatorHits++; growthHits++;
        }

        if (detectRotatedDeepslate.get() && block == Blocks.DEEPSLATE) {
            if (state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) {
                points += 40.0f;
                sourceWeight += 2;
                if (originType == null) { originType = OriginType.ROTATED; originActual = 1; }
            }
            typeMask |= IndicatorType.DEEPSLATE.mask;
            indicatorHits++; rotatedHits++;
        }

        return new BlockAnalysisResult(points, typeMask, indicatorHits, amethystHits, growthHits, rotatedHits, sourceWeight, originType, originActual);
    }

    private float growthScore(BlockState state, ChunkRandom random, long popSeed, BlockPos pos, float weight, int minDelta) {
        if (!state.contains(Properties.AGE_25)) return 0.0f;
        int actual = state.get(Properties.AGE_25);
        int expected = expectedAge(random, popSeed, pos, 8);
        if (actual <= expected + minDelta) return 0.0f;
        return (actual - expected) * weight;
    }

    private int getAmethystStage(BlockState state) {
        if (state.isOf(Blocks.SMALL_AMETHYST_BUD)) return 1;
        if (state.isOf(Blocks.MEDIUM_AMETHYST_BUD)) return 2;
        if (state.isOf(Blocks.LARGE_AMETHYST_BUD)) return 3;
        if (state.isOf(Blocks.AMETHYST_CLUSTER)) return 4;
        return 0;
    }

    private int getVineLength(World world, BlockPos start, Block block, int maxLen) {
        int length = 1;
        BlockPos.Mutable pos = new BlockPos.Mutable(start.getX(), start.getY(), start.getZ());
        while (length < maxLen) {
            pos.set(pos.getX(), pos.getY() - 1, pos.getZ());
            if (pos.getY() < world.getBottomY()) break;
            if (!world.getBlockState(pos).isOf(block)) break;
            length++;
        }
        return length;
    }

    private int expectedStage(ChunkRandom random, long popSeed, BlockPos pos) {
        random.setSeed(mixSeed(popSeed, pos));
        return random.nextInt(3);
    }

    private int expectedAge(ChunkRandom random, long popSeed, BlockPos pos, int max) {
        random.setSeed(mixSeed(popSeed, pos));
        return random.nextInt(Math.max(1, max));
    }

    private int expectedLength(ChunkRandom random, long popSeed, BlockPos pos, int min, int max) {
        random.setSeed(mixSeed(popSeed, pos));
        int span = Math.max(1, max - min + 1);
        return min + random.nextInt(span);
    }

    private long mixSeed(long popSeed, BlockPos pos) {
        long s = popSeed;
        s ^= (long) pos.getX() * 341873128712L;
        s ^= (long) pos.getZ() * 132897987541L;
        s ^= (long) pos.getY() * 42317861L;
        s ^= s << 13; s ^= s >> 7; s ^= s << 17;
        return s;
    }

    private float getLongTimeThreshold(int sens, int enabledIndicators) {
        float base = 18.0f;
        float adjust = (sens - 3) * 1.1f;
        float indicatorBoost = Math.max(0, 4 - enabledIndicators) * 1.6f;
        return Math.max(8.0f, base - adjust + indicatorBoost);
    }

    private int getEnabledIndicatorCount() {
        int count = 0;
        if (detectAmethyst.get()) count++;
        if (detectVines.get()) count++;
        if (detectCaveVines.get()) count++;
        if (detectKelp.get()) count++;
        if (detectRotatedDeepslate.get()) count++;
        return count;
    }

    private int getRequiredTypes(int enabledIndicators) {
        return enabledIndicators >= 3 ? 2 : 1;
    }

    private int getRequiredIndicatorCount(int enabledIndicators) {
        if (enabledIndicators >= 4) return 4;
        if (enabledIndicators >= 2) return 3;
        return 1;
    }

    private int getRequiredAmethystHits(int sens) { return 10; }

    private int getRequiredGrowthHits(int sens) {
        return 4 + Math.max(0, (sens - 3) / 2);
    }

    private int getRequiredRotatedHits(int sens) {
        return 6 + Math.max(0, (sens - 3) / 2);
    }

    private void addPathCell(Set<ChunkPos> out, int cx, int cz, int width) {
        out.add(new ChunkPos(cx, cz));
        if (width <= 0) return;
        for (int dx = -width; dx <= width; dx++)
            for (int dz = -width; dz <= width; dz++)
                out.add(new ChunkPos(cx + dx, cz + dz));
    }

    private void mergeSourceHits(Map<ChunkPos, Integer> target, Map<ChunkPos, Integer> source) {
        for (Map.Entry<ChunkPos, Integer> entry : source.entrySet())
            addSourceHit(target, entry.getKey(), entry.getValue());
    }

    private void addSourceHit(Map<ChunkPos, Integer> sourceHits, ChunkPos pos, int weight) {
        sourceHits.put(pos, sourceHits.getOrDefault(pos, 0) + weight);
    }

    private ChunkPos findBestOriginChunk(BlockPos pos, OriginType type, int actual, Map<ChunkPos, Long> popSeedCache) {
        if (type == OriginType.ROTATED) return new ChunkPos(pos);
        int baseX = pos.getX() >> 4;
        int baseZ = pos.getZ() >> 4;
        int bestScore = Integer.MIN_VALUE;
        ChunkPos best = null;
        for (int dx = -ORIGIN_SEARCH_RADIUS; dx <= ORIGIN_SEARCH_RADIUS; dx++) {
            for (int dz = -ORIGIN_SEARCH_RADIUS; dz <= ORIGIN_SEARCH_RADIUS; dz++) {
                ChunkPos candidate = new ChunkPos(baseX + dx, baseZ + dz);
                long popSeed = popSeedCache.computeIfAbsent(candidate,
                        c -> new ChunkRandom(Random.create()).setPopulationSeed(WORLD_SEED, c.getStartX(), c.getStartZ()));
                int expected = expectedForType(type, popSeed, pos);
                int delta = actual - expected;
                if (delta > bestScore) { bestScore = delta; best = candidate; }
            }
        }
        return bestScore > 0 ? best : null;
    }

    private int expectedForType(OriginType type, long popSeed, BlockPos pos) {
        switch (type) {
            case AMETHYST: return expectedStage(new ChunkRandom(Random.create()), popSeed, pos);
            case KELP: case CAVE_VINES: return expectedAge(new ChunkRandom(Random.create()), popSeed, pos, 8);
            case VINES: return expectedLength(new ChunkRandom(Random.create()), popSeed, pos, 3, 8);
            default: return 0;
        }
    }

    @EventHandler
    public void onRender3D(Render3DEvent event) {
        if (flaggedArea.isEmpty() || mc.player == null) return;

        MatrixStack matrices = event.matrices;
        float alphaValue = alpha.get() / 255f;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getPos();

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0f));
        matrices.translate(-cam.x, -cam.y, -cam.z);

        for (ChunkPos cp : flaggedArea) {
            float x1 = (float) cp.getStartX();
            float z1 = (float) cp.getStartZ();
            float x2 = x1 + 16.0f;
            float z2 = z1 + 16.0f;
            float y1 = RENDER_Y - thickness.get().floatValue();
            float y2 = RENDER_Y;

            int a = (int)(alphaValue * 255);
            event.renderer.box(x1, y1, z1, x2, y2, z2,
                    new Color(255, 0, 0, a),
                    new Color(255, 0, 0, 255),
                    ShapeMode.Sides, 0);
        }
        matrices.pop();
    }

    private enum IndicatorType {
        AMETHYST(1), VINES(1 << 1), CAVE_VINES(1 << 2), KELP(1 << 3), DEEPSLATE(1 << 4);
        private final int mask;
        IndicatorType(int mask) { this.mask = mask; }
    }

    private static final class ChunkScanResult {
        private final float score;
        private final int typeMask, indicatorCount, amethystHits, growthHits, rotatedHits;
        private final Map<ChunkPos, Integer> sourceHitsLocal;

        private ChunkScanResult(float score, int typeMask, int indicatorCount, int amethystHits, int growthHits, int rotatedHits, Map<ChunkPos, Integer> sourceHitsLocal) {
            this.score = score; this.typeMask = typeMask; this.indicatorCount = indicatorCount;
            this.amethystHits = amethystHits; this.growthHits = growthHits; this.rotatedHits = rotatedHits;
            this.sourceHitsLocal = sourceHitsLocal;
        }

        private static ChunkScanResult empty() {
            return new ChunkScanResult(0.0f, 0, 0, 0, 0, 0, new HashMap<>());
        }
    }

    private static final class BlockAnalysisResult {
        private final float points;
        private final int typeMask, indicatorHits, amethystHits, growthHits, rotatedHits, sourceWeight, originActual;
        private final OriginType originType;

        private BlockAnalysisResult(float points, int typeMask, int indicatorHits, int amethystHits, int growthHits, int rotatedHits, int sourceWeight, OriginType originType, int originActual) {
            this.points = points; this.typeMask = typeMask; this.indicatorHits = indicatorHits;
            this.amethystHits = amethystHits; this.growthHits = growthHits; this.rotatedHits = rotatedHits;
            this.sourceWeight = sourceWeight; this.originType = originType; this.originActual = originActual;
        }
    }

    private enum OriginType { AMETHYST, KELP, CAVE_VINES, VINES, ROTATED }
}
