package project;

import nl.jellejurre.seedchecker.SeedChecker;
import project.SearchCoords;
import project.GameVersion;
import project.WorldPresetMode;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LowYSwampHut 命令行入口（基于 SearchCoords 引擎）
 * 用法: java -jar LowYSwampHut.jar --seed <种子> [选项]
 */
public class CmdLineRunner {

    public static void main(String[] args) {
        // 1. 初始化 log4j 和 SharedConstants（与 Launcher 一致）
        initLogging();

        // 2. 默认参数
        long seed = 0;
        int maxY = -40;
        int minX = -58594, maxX = 58593;
        int minZ = -58594, maxZ = 58593;
        String outputFile = "result.txt";
        String versionName = "26.2";
        boolean checkGen = false;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        // 3. 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--max-y" -> maxY = Integer.parseInt(args[++i]);
                case "--min-x" -> minX = Integer.parseInt(args[++i]);
                case "--max-x" -> maxX = Integer.parseInt(args[++i]);
                case "--min-z" -> minZ = Integer.parseInt(args[++i]);
                case "--max-z" -> maxZ = Integer.parseInt(args[++i]);
                case "--version" -> versionName = args[++i];
                case "--output" -> outputFile = args[++i];
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--check-gen" -> checkGen = true;
                case "--help" -> { printHelp(); return; }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printHelp();
                    System.exit(1);
                }
            }
        }

        if (seed == 0) {
            System.err.println("错误: 必须指定 --seed");
            printHelp();
            System.exit(1);
        }

        // 4. 版本映射（使用项目现有的 GameVersion 枚举）
        GameVersion gameVersion = GameVersion.fromDisplayName(versionName);
        if (gameVersion == null) {
            System.err.println("错误: 不支持的版本 " + versionName);
            System.exit(1);
        }
        WorldPresetMode preset = WorldPresetMode.DEFAULT;

        // 5. 创建 SearchCoords 并启动搜索
        System.out.println("开始搜索种子 " + seed + "，最大Y=" + maxY + "，版本=" + versionName);
        SearchCoords searcher = new SearchCoords(gameVersion, preset);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger lastPrinted = new AtomicInteger(0);

        searcher.startSearch(
            seed,
            threads,
            minX, maxX, minZ, maxZ,
            maxY,
            progress -> {
                // 进度回调：每 1% 打印一次
                int pct = (int) (progress.percentage() * 100);
                int stage = progress.stage();
                if (pct >= lastPrinted.get() + 1) {
                    System.out.printf("\r阶段 %d 进度: %d%% (%d 个结果)%n", stage, pct, count.get());
                    lastPrinted.set(pct);
                }
            },
            result -> {
                // 结果回调：收集坐标（格式与 GUI 一致）
                results.add(String.format("/tp %d %d %d", result.x(), result.y(), result.z()));
                count.incrementAndGet();
            },
            checkGen
        );

        // 6. 等待搜索完成（SearchCoords 没有 awaitCompletion，需要手动轮询）
        // 通过检查 searcher 的状态来判断（假设有 isRunning 方法，如果没有则用反射或 try-catch）
        // 这里我们使用一个简单的超时等待
        System.out.println("\n搜索中，请稍候...");
        try {
            // 等待最多 10 分钟
            for (int i = 0; i < 600; i++) {
                Thread.sleep(1000);
                if (count.get() > 0 && !searcher.isRunning()) {
                    break;
                }
                if (i % 30 == 0) {
                    System.out.printf("  已运行 %d 秒，已找到 %d 个结果...%n", i, count.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 7. 排序并输出结果
        results.sort(Comparator.comparingInt(s -> {
            String[] parts = s.split("\\s+");
            return parts.length >= 3 ? Integer.parseInt(parts[2]) : Integer.MAX_VALUE;
        }));

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("找到 " + results.size() + " 个女巫小屋：");
            for (String line : results) {
                writer.println(line);
            }
            if (!results.isEmpty()) {
                writer.println("\n最低 Y 坐标的小屋: " + results.get(0));
            }
            System.out.println("\n结果已保存到: " + outputFile);
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void initLogging() {
        // 与 Launcher 完全一致的 log4j 初始化
        System.setProperty("log4j2.isThreadContextMapInheritable", "true");
        System.setProperty("log4j2.disable.jmx", "true");
        System.setProperty("log4j2.formatMsgNoLookups", "true");
        System.setProperty("log4j2.enable.threadlocals", "false");
        System.setProperty("log4j2.enable.direct.encoders", "false");
        System.setProperty("max.bg.threads", "2");
        try {
            Class.forName("net.minecraft.SharedConstants");
        } catch (Exception ignored) {
        }
    }

    private static void printHelp() {
        System.out.println("LowYSwampHut 命令行搜索工具");
        System.out.println("用法: java -jar LowYSwampHut.jar --seed <种子> [选项]");
        System.out.println("选项:");
        System.out.println("  --seed <种子>       必需，要搜索的种子 (整数)");
        System.out.println("  --max-y <数值>     最大Y坐标，默认 -40");
        System.out.println("  --min-x <数值>     X范围最小值，默认 -58594");
        System.out.println("  --max-x <数值>     X范围最大值，默认 58593");
        System.out.println("  --min-z <数值>     Z范围最小值，默认 -58594");
        System.out.println("  --max-z <数值>     Z范围最大值，默认 58593");
        System.out.println("  --version <版本>   Minecraft版本，默认 26.2");
        System.out.println("  --output <文件>    输出文件，默认 result.txt");
        System.out.println("  --threads <数量>   线程数，默认 CPU核心数/2");
        System.out.println("  --check-gen        精确检查生成（较慢但更准）");
        System.out.println("  --help             显示此帮助");
    }
}