package project;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;
import nl.kallestruik.noisesampler.minecraft.Dimension;
import nl.kallestruik.noisesampler.minecraft.GenerationShapeConfig;
import nl.kallestruik.noisesampler.minecraft.NoiseColumnSampler;
import nl.kallestruik.noisesampler.minecraft.NoiseParameterKey;
import nl.kallestruik.noisesampler.minecraft.VanillaTerrainParameters;
import nl.kallestruik.noisesampler.minecraft.Xoroshiro128PlusPlusRandom;
import nl.kallestruik.noisesampler.minecraft.noise.LazyDoublePerlinNoiseSampler;
import nl.kallestruik.noisesampler.minecraft.util.MathHelper;
import nl.kallestruik.noisesampler.minecraft.util.NoiseSamplingConfig;
import nl.kallestruik.noisesampler.minecraft.util.SlideConfig;
import nl.kallestruik.noisesampler.minecraft.util.TerrainNoisePoint;
import nl.kallestruik.noisesampler.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SearchCoords {

    // ========== 预筛 / 高度扫描可调常量 ==========
    /** 多点梯子估高均值超过 maxHeight 此裕量才拒绝进入阶段2（越大越宽松、越不易漏检） */
    private static final int DENSITY_PREFILTER_HEIGHT_MARGIN = 8;
    /**
     * 相对小屋局部坐标系的 5 探针（未旋转 7×9）：中心 + 十字。
     * 运用前会按 carver RNG 朝向映射到世界坐标。
     */
    private static final int[][] DENSITY_PREFILTER_LOCAL_PROBES = {
            {3, 4}, // 中心 (HUT_LOCAL_WIDTH-1)/2, (HUT_LOCAL_DEPTH-1)/2
            {3, 1},
            {3, 7},
            {1, 4},
            {5, 4}
    };
    /** cheese 梯子向下探测的 Y 档位 */
    private static final int[] DENSITY_PREFILTER_LADDER_DOWN = {40, 30, 20, 10, 0, -10, -20, -30, -40, -50};
    /**
     * 阶段1粗筛 / 密度预筛的高度下限：选 -50 或 -54 时按 -50 处理，-54 不额外降低。
     * 阶段2仍使用用户选定的真实 maxHeight。
     */
    private static final int PHASE1_MIN_CHECK_HEIGHT = -50;
    /** SeedChecker 列扫描起始 Y（必须足够高，否则会把高地表误判成低洞穴顶） */
    private static final int COLUMN_SCAN_START_Y = 200;
    /** SeedChecker 列扫描最低 Y */
    private static final int COLUMN_SCAN_MIN_Y = -55;
    /** 小屋 footprint 列数（7×9） */
    private static final int HUT_FOOTPRINT_COLUMNS = 63;

    /*
     * SeedChecker 生成 chunk 时会启动额外的 CompletableFuture 工作。
     * 默认并行度与 CPU 核数对齐；仍可用系统属性覆盖。
     * 注意：切勿在 getBlock 热路径里频繁反射清缓存，那会比限制线程更伤性能。
     */
    private static final int MAX_SEARCH_THREADS = positiveIntProperty(
            "lowyswamphut.maxSearchThreads",
            Math.max(1, Runtime.getRuntime().availableProcessors()));
    private static final int MAX_CONCURRENT_REAL_GENERATIONS = positiveIntProperty(
            "lowyswamphut.maxConcurrentRealGenerations",
            MAX_SEARCH_THREADS);
    private static final int MAX_CHUNK_CACHE_SIZE = boundedChunkCacheSize(
            "lowyswamphut.maxChunkCacheSize", 1_024);
    private static final int MAX_SEARCH_AXIS_SPAN = positiveIntProperty(
            "lowyswamphut.maxSearchAxisSpan", 250_000);
    private static final long MAX_SEARCH_ITERATIONS = positiveLongProperty(
            "lowyswamphut.maxSearchIterations", 20_000_000_000L);
    private static final Semaphore REAL_GENERATION_PERMITS =
            new Semaphore(MAX_CONCURRENT_REAL_GENERATIONS, false);

    private final SwampHut swampHut;
    private final GameVersion gameVersion;
    private final MCVersion mcVersion;
    private final WorldPresetMode worldPresetMode;
    private final SearchMetricsHook metricsHook;
    private ExecutorService executor;
    private Thread progressThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile boolean coordinatorFinished = false;
    private volatile CountDownLatch searchDone = new CountDownLatch(0);
    private final List<String> results = new ArrayList<>();

    // 保存当前搜索状态，用于动态调整线程数
    private long currentSeed;
    private int currentMinX, currentMaxX, currentMinZ, currentMaxZ;
    private double currentMaxHeight;
    private AtomicLong currentProcessedCount;
    private volatile long currentTotalTasks;
    private volatile int currentStage = 1;
    /** 当前阶段开始时刻（用于本阶段剩余时间：stageElapsed * remaining / processed） */
    private volatile long stageStartTimeMs;
    /** 当前阶段累计暂停时长 */
    private final AtomicLong stagePausedTimeMs = new AtomicLong(0);
    private final AtomicReference<Long> stagePauseStartMs = new AtomicReference<>(0L);
    private Consumer<String> currentResultCallback;
    private int currentThreadCount;
    private boolean currentCheckGeneration;
    private Set<Long> phase1Candidates;
    private List<CPos> phase2Candidates;
    private AtomicInteger phase2Cursor;
    private volatile boolean phase1AdjustPending = false;
    private volatile boolean phase2AdjustPending = false;

    // ================= 每线程每种子缓存（噪声采样器 + SeedChecker） =================
    private static final ThreadLocal<ThreadSeedResources> THREAD_RESOURCES = new ThreadLocal<>();

    public record ProgressInfo(long processed, long total, double percentage, long elapsedMs, long remainingMs, int stage) {
    }

    public SearchCoords(GameVersion gameVersion, WorldPresetMode worldPresetMode) {
        this(gameVersion, worldPresetMode, SearchMetricsHook.NO_OP);
    }

    public SearchCoords(GameVersion gameVersion, WorldPresetMode worldPresetMode,
                        SearchMetricsHook metricsHook) {
        this.gameVersion = gameVersion;
        this.mcVersion = gameVersion.getMcVersion();
        this.worldPresetMode = worldPresetMode;
        this.swampHut = new SwampHut(mcVersion);
        this.metricsHook = metricsHook == null ? SearchMetricsHook.NO_OP : metricsHook;
    }

    public void startSearch(long seed, int threadCount, int minX, int maxX, int minZ, int maxZ, double maxHeight,
                            Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback, boolean checkGeneration) {
        validateSearchBounds(minX, maxX, minZ, maxZ);
        final int searchThreadCount = boundedThreadCount(threadCount);
        // 如果正在运行且处于暂停状态，且线程数变化，则调整线程数
        if (isRunning && isPaused && searchThreadCount != currentThreadCount) {
            adjustThreadCount(searchThreadCount, resultCallback, checkGeneration);
            return;
        }

        if (isRunning) {
            return;
        }
        // worker 提交后会立即调用 shutdown()，因此不能只依赖 isRunning
        // 判断前一个线程池是否已经彻底退出。
        if (executor != null && !executor.isTerminated()) {
            return;
        }
        isRunning = true;
        coordinatorFinished = false;
        searchDone = new CountDownLatch(1);
        results.clear();

        long stage1Total = (long) (maxX - minX) * (maxZ - minZ);

        // 保存当前搜索状态
        currentSeed = seed;
        currentMinX = minX;
        currentMaxX = maxX;
        currentMinZ = minZ;
        currentMaxZ = maxZ;
        currentMaxHeight = maxHeight;
        currentThreadCount = searchThreadCount;
        currentResultCallback = resultCallback;
        currentCheckGeneration = checkGeneration;
        currentStage = 1;
        currentTotalTasks = stage1Total;
        stageStartTimeMs = System.currentTimeMillis();
        stagePausedTimeMs.set(0);
        stagePauseStartMs.set(0L);
        phase1Candidates = ConcurrentHashMap.newKeySet();
        phase2Candidates = null;
        phase2Cursor = null;

        AtomicLong processedCount = new AtomicLong(0);
        currentProcessedCount = processedCount;

        final CountDownLatch doneLatch = searchDone;

        // 进度监控线程：仅在有回调时启动（批量种子并行可传 null 以省开销）
        long startTime = System.currentTimeMillis();
        AtomicLong pausedTime = new AtomicLong(0);
        AtomicReference<Long> pauseStartTime = new AtomicReference<>(0L);
        if (progressCallback != null) {
            progressThread = new Thread(() -> {
                while (isRunning && !coordinatorFinished) {
                    try {
                        Thread.sleep(100);
                        reportProgress(progressCallback, startTime, pausedTime, pauseStartTime, false);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                reportProgress(progressCallback, startTime, pausedTime, pauseStartTime, true);
            });
            progressThread.setDaemon(true);
            progressThread.start();
        } else {
            progressThread = null;
        }

        // 批量「1 线程/种子」：在调用线程同步跑完，避免每种子再开协调线程
        if (progressCallback == null && searchThreadCount <= 1) {
            runCoordinator(seed, searchThreadCount, minX, maxX, minZ, maxZ, maxHeight,
                    processedCount, resultCallback, checkGeneration, doneLatch);
            return;
        }

        new Thread(() -> runCoordinator(seed, searchThreadCount, minX, maxX, minZ, maxZ, maxHeight,
                processedCount, resultCallback, checkGeneration, doneLatch),
                "SearchCoords-Coordinator").start();
    }

    private void runCoordinator(long seed, int searchThreadCount, int minX, int maxX, int minZ, int maxZ,
                                double maxHeight, AtomicLong processedCount, Consumer<String> resultCallback,
                               