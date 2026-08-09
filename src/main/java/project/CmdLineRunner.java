package project;

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
        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // 创建搜索器实例
            SearchCoords searcher = new SearchCoords();
            
            // 设置回调：每找到一个结果就添加到列表
            searcher.startSearch(
                seed,
                minX, maxX, minZ, maxZ,
                maxY,
                1,                    // 单线程 (命令行模式)
                false,                // 不检查精确生成 (加快速度)
                (String coord) -> {   // 坐标格式: "/tp x y z"
                    if (coord != null && !coord.isEmpty()) {
                        results.add(coord);
                    }
                }
            );
            
            // 等待搜索完成 (最多等待 5 分钟)
            // 注意: SearchCoords 可能没有 awaitCompletion，我们假设有
            // 如果没有，可以用轮询或修改 SearchCoords 添加标志
            // 这里我们简单等待 30 秒 (实际搜索可能更快)
            Thread.sleep(30000);
            
        } catch (Exception e) {
            System.err.println("搜索出错: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // 按 Y 坐标排序 (从低到高)
        results.sort((a, b) -> {
            int y1 = extractY(a);
            int y2 = extractY(b);
            return Integer.compare(y1, y2);
        });

        // 输出结果到文件
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("找到 " + results.size() + " 个女巫小屋：");
            for (String line : results) {
                writer.println(line);
            }
            if (!results.isEmpty()) {
                writer.println("\n最低 Y 坐标的小屋: " + results.get(0));
            }
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

    private static int extractY(String coord) {
        // 从 "/tp x y z" 中提取 y
        String[] parts = coord.split(" ");
        if (parts.length >= 3) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        return Integer.MAX_VALUE;
    }
}