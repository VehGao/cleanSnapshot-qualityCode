package io.github.veh.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CleanOldVersionUtil {

    protected static final String[] metadataExtensions = {".sha1", ".lastUpdated", ".properties", ".repositories"};

    // 静态常量作为默认模式
    protected static final Pattern DEFAULT_TIMESTAMP_PATTERN = Pattern.compile(".*-\\d{8}\\.\\d{6}-\\d+\\.(jar|pom|sources\\.jar)$");


    /**
     * 从文件名提取 artifact 标识（不包含时间戳部分）
     * 例如：aggcare-invoke-ipcare-1.0-20260401.044145-2674.jar -> aggcare-invoke-ipcare-1.0
     */
    protected static String extractArtifactKey(String fileName, Pattern timestampPattern) {
        Matcher matcher = timestampPattern.matcher(fileName);
        if (matcher.matches()) {
            int lastDash = fileName.lastIndexOf('-');
            if (lastDash > 0) {
                String beforeBuildNum = fileName.substring(0, lastDash);
                int secondLastDash = beforeBuildNum.lastIndexOf('-');
                if (secondLastDash > 0) {
                    String timestamp = beforeBuildNum.substring(secondLastDash + 1);
                    if (timestamp.matches("\\d{8}\\.\\d{6}")) {
                        return beforeBuildNum.substring(0, secondLastDash);
                    }
                }
            }
        }
        return null;
    }


    /**
     * 从文件名提取时间戳
     * 例如：aggcare-invoke-ipcare-1.0-20260401.044145-2674.jar -> 20260401.044145
     */
    protected static String extractTimestamp(String fileName, Pattern timestampPattern) {
        Matcher matcher = timestampPattern.matcher(fileName);
        if (matcher.matches()) {
            int lastDash = fileName.lastIndexOf('-');
            if (lastDash > 0) {
                String beforeLastDash = fileName.substring(0, lastDash);
                int secondLastDash = beforeLastDash.lastIndexOf('-');
                if (secondLastDash > 0) {
                    return beforeLastDash.substring(secondLastDash + 1);
                }
            }
        }
        return "";
    }

    /**
     * 提取文件类型（jar, pom, sources.jar）
     */
    protected static String extractFileType(String fileName) {
        if (fileName.endsWith("-sources.jar")) {
            return "sources.jar";
        } else if (fileName.endsWith(".jar")) {
            return "jar";
        } else if (fileName.endsWith(".pom")) {
            return "pom";
        }
        return "other";
    }

    /**
     * 收集孤立的元数据文件
     */
    protected static void collectOrphanMetadata(File localRepo, Map<String, Map<String, List<File>>> artifactGroups,
                                             List<File> filesToDelete) throws IOException {
        final List<File> allSnapshotFiles = new ArrayList<>();
        // 收集所有保留的 SNAPSHOT 文件
        for (Map<String, List<File>> typeMap : artifactGroups.values()) {
            for (List<File> files : typeMap.values()) {
                allSnapshotFiles.addAll(files);
            }
        }
        // 遍历查找孤立的元数据文件
        Files.walkFileTree(localRepo.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = fileNamePath.toString();

                for (String ext : metadataExtensions) {
                    if (fileName.endsWith(ext)) {
                        String baseName = fileName.substring(0, fileName.length() - ext.length());
                        boolean isOrphan = true;

                        // 检查是否有对应的 jar/pom 文件存在
                        for (File snapshotFile : allSnapshotFiles) {
                            String snapshotName = snapshotFile.getName();
                            String snapshotBase = snapshotName.endsWith(".sources.jar") ?
                                    snapshotName.substring(0, snapshotName.length() - 12) :
                                    snapshotName.substring(0, snapshotName.lastIndexOf('.'));

                            if (baseName.equals(snapshotBase)) {
                                isOrphan = false;
                                break;
                            }
                        }
                        if (isOrphan) {
                            filesToDelete.add(file.toFile());
                        }
                        break;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
