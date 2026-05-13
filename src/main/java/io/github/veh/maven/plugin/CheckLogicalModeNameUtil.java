package io.github.veh.maven.plugin;

import java.io.File;

public class CheckLogicalModeNameUtil {
    /**
     * 递归检查目录是否包含 mapper 子目录
     *
     * @param directory 要检查的目录
     * @return 如果包含 mapper 目录返回 true，否则返回 false
     */
    protected static boolean containsMapperOrModelDirectory(File directory) {
        return containsMapperOrModelDirectory(directory, 0);
    }


    /**
     * 递归检查目录是否包含指定名称的子目录(支持多个候选名称)
     *
     * @param directory      要检查的目录
     * @param targetDirNames 目标目录名称列表
     * @return 如果包含任一目标目录返回 true，否则返回 false
     */
    protected static boolean containsDirectory(File directory, String... targetDirNames) {
        if (targetDirNames == null || targetDirNames.length == 0) {
            return false;
        }
        return containsDirectory(directory, targetDirNames, 0);
    }

    /**
     * 递归检查目录是否包含指定名称的子目录(支持多个候选名称，带深度限制)
     *
     * @param directory      要检查的目录
     * @param targetDirNames 目标目录名称列表
     * @param depth          当前递归深度
     * @return 如果包含任一目标目录返回 true，否则返回 false
     */
    private static boolean containsDirectory(File directory, String[] targetDirNames, int depth) {
        if (!directory.exists() || !directory.isDirectory()) {
            return false;
        }
        if (depth >= 10) {
            return false;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                String fileName = file.getName();
                for (String targetName : targetDirNames) {
                    if (targetName.equalsIgnoreCase(fileName)) {
                        return true;
                    }
                }
                // 递归检查子目录，深度加1
                if (containsDirectory(file, targetDirNames, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * 递归检查目录是否包含 mapper 子目录(带深度限制)
     *
     * @param directory 要检查的目录
     * @param depth     当前递归深度
     * @return 如果包含 mapper 目录返回 true，否则返回 false
     */
    private static boolean containsMapperOrModelDirectory(File directory, int depth) {
        if (!directory.exists() || !directory.isDirectory()) {
            return false;
        }
        if (depth >= 10) {
            return false;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if ("mapper".equalsIgnoreCase(file.getName())) {
                    return true;
                }
                // 递归检查子目录，深度加1
                if (containsMapperOrModelDirectory(file, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
