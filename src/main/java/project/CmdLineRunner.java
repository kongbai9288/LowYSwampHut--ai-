package project;

import seedfinding.SeedChecker;
import seedfinding.SeedCheckerInitializer;
import seedfinding.CoordResult;
import cubiomes.Coord;

import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LowYSwampHut 命令行入口
 * 用法: java -jar LowYSwampHut.jar --seed <种子> [--max-y <数值>] [--output <文件>]
 */
public class CmdLineRunner {
    public static void main(String[] args) {
        // 默认参数
        long seed = 0;
        int maxY = -40;
        int minX = -58594, maxX = 58593;
        int minZ = -58594, maxZ = 58593;
        String outputFile = "result.txt";
        String version = "26.2";

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed":
                    seed = Long.parseLong(args[++i]);
                    break;
                case "--max-y":
                    maxY = Integer.parseInt(args[++i]);
                    break;
                case "--min-x":
                    minX = Integer.parseInt(args[++i]);
                    break;
                case "--max-x":
                    maxX = Integer.parseInt(args[++i]);
                    break;
                case "--min-z":
                    minZ = Integer.parseInt(args[++i]);
                    break;
                case "--max-z":
                    maxZ = Integer.parseInt(args[++i]);
                    break;
                case "--output":
                    outputFile = args[++i];
                    break;
                case "--version":
                    version = args[++i];
                    break;
                case "--help":
                    printHelp();
                    return;
                default:
                    System.err.println("未知参数: " + args[i]);
                    printHelp();
                    System.exit(1);
            }
        }

        if (seed == 0) {
            System.err.println("错误: 必须指定 --seed");
            printHelp();
            System.exit(1);
        }

        // 执行搜索
        System.out.println("开始搜索种子 " + seed + "，最大Y=" + maxY);
        List<CoordResult> results = new ArrayList<>();

        try {
            // 1. 初始化 SeedChecker（与 GUI 相同）
            SeedCheckerInitializer initializer = new SeedCheckerInitializer();
            // 设置版本（可能需要转换为内部版本号）
            int mcVersion = getMCVersion(version);
            initializer.setMCVersion(mcVersion);
            // 设置搜索范围
            initializer.setSearchArea(minX, maxX, minZ, maxZ);
            // 设置最大 Y（注意：原 GUI 中 "maxY" 是高度上限，即只找 Y <= maxY 的小屋）
            initializer.setMaxY(maxY);
            // 设置是否检查精确生成（设为 true 更准确，但慢）
            initializer.setCheckExactGeneration(true);

            // 2. 创建 SeedChecker 实例
            SeedChecker checker = initializer.createSeedChecker();

            // 3. 设置回调收集结果
            // 由于 SeedChecker 没有直接的回调接口，我们使用它的 `addResultListener` 方法（如果存在）
            // 或者我们可以在搜索完成后通过 `getResults()` 获取（具体 API 需查看源码）
            // 根据原仓库代码，SeedChecker 继承自 SwingWorker，有 `get()` 方法等待完成
            // 我们采用后台执行并等待完成的方式
            
            // 启动搜索（假设 SeedChecker 是 SwingWorker，我们使用 execute）
            checker.execute();

            // 等待搜索完成（最多 5 分钟）
            boolean done = checker.get(5, TimeUnit.MINUTES);
            if (!done) {
                System.err.println("搜索超时，可能未完成");
                System.exit(1);
            }

            // 获取结果（假设有 getResults 方法）
            // 实际需要根据 SeedChecker 的具体实现调整
            // 这里使用反射尝试获取私有字段 results（如果存在）
            java.lang.reflect.Field resultsField = null;
            try {
                resultsField = SeedChecker.class.getDeclaredField("results");
                resultsField.setAccessible(true);
                results = (List<CoordResult>) resultsField.get(checker);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // 如果没有 results 字段，尝试其他方式
                System.err.println("无法获取搜索结果，请检查 SeedChecker API");
                e.printStackTrace();
                System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("搜索出错: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // 如果没有结果，输出提示
        if (results == null || results.isEmpty()) {
            System.out.println("未找到任何女巫小屋");
            return;
        }

        // 按 Y 坐标排序（从低到高）
        results.sort(Comparator.comparingInt(r -> r.y));

        // 输出到文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("找到 " + results.size() + " 个女巫小屋：");
            for (CoordResult r : results) {
                writer.println("X=" + r.x + ", Y=" + r.y + ", Z=" + r.z);
            }
            CoordResult lowest = results.get(0);
            writer.println("\n最低 Y 坐标的小屋: X=" + lowest.x + ", Y=" + lowest.y + ", Z=" + lowest.z);
            System.out.println("结果已保存到: " + outputFile);
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printHelp() {
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
        System.out.println("  --help             显示此帮助");
    }

    private static int getMCVersion(String version) {
        // 将版本字符串转换为内部版本号（需根据实际情况映射）
        // 示例：26.2 -> 26
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (Exception e) {
            return 26;
        }
    }
}