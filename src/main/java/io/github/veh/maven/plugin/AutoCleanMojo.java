package io.github.veh.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.veh.maven.plugin.CheckDatabaseCompatibilityInXmlUtil.*;
import static io.github.veh.maven.plugin.CheckLogicalModeNameUtil.containsDirectory;
import static io.github.veh.maven.plugin.CheckMapperAnnotationsUtil.*;
import static io.github.veh.maven.plugin.CheckTableFieldInDtoVoUtil.checkPoJavaTimeFields;
import static io.github.veh.maven.plugin.CheckXmlDuplicateIdsUtil.checkDuplicateIds;
import static io.github.veh.maven.plugin.CheckXmlFieldQuotesUtil.findQuotedFieldsInSql;
import static io.github.veh.maven.plugin.CheckXmlFormatUtil.checkXmlFormat;
import static io.github.veh.maven.plugin.CleanOldVersionUtil.*;

/**
 * Maven插件
 * 1，自动清理Maven本地仓库中过期的SNAPSHOT版本JAR文件，保留最新SNAPSHOT版本；
 * 2，检查 Mapper.xml : LATERAL 子句是否包含 order by
 * 3，检查 Mapper.xml: 国产数据库不兼容函数和语法： UNNEST函数，ISNULL函数，IFNULL函数，NVL2函数，IF函数，DATE_FORMAT函数，TOP语法，GROUP_CONCAT函数，date函数
 * 4，检查 Mapper.xml: 检查 statement 重复ID，XML标签未闭合
 * 5，检查 Mapper.xml: 检查 SQL 字段名是否包含双引号
 * 6，检查 DTO.java，VO.java 中的  @TableField 注解
 * 7，检查 Mapper.java 方法 是否缺少 @Param注解
 */
@Mojo(name = "clean-snapshots", defaultPhase = LifecyclePhase.CLEAN)//插件内部的具体目标(Goal)名称：clean-snapshots，默认目标执行顺序为：CLEAN
public class AutoCleanMojo extends AbstractMojo {


    @Parameter(property = "mapper.xmlPath", defaultValue = "${project.basedir}/src/main/resources/mapper/")
    private String mapperXmlPath;//Mapper.xml 文件所在路径 默认为 src/main/resources/mapper/

    @Parameter(property = "repoPath", defaultValue = "${settings.localRepository}/")
    private String repoPath;//本地maven仓库路径，比如想 递归清理 仓库 下的 SNAPSHOT 文件

    @Parameter(property = "mapper.javaPath", defaultValue = "${project.basedir}/src/main/java/")
    private String mapperJavaPath;//Mapper.java 文件所在包路径 前两级即可，会自动递归寻找

    @Parameter(property = "dto.voPath", defaultValue = "${project.basedir}/src/main/java/")
    private String dtoVoPath; //DTO，PO，VO 文件所在 包路径 前两级即可，会自动递归寻找

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;//Maven项目对象
    @Parameter(property = "clean.pattern")
    private String pattern;//匹配需要清理的文件名模式，默认匹配形如 example-20231207.123456-1.jar 的 SNAPSHOT 文件
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private String projectBuildDirectory;
    private String logicalModeName = "";//逻辑模块名

    /**
     * 插件执行入口方法，负责启动清理流程
     *
     */
    public void execute() throws MojoExecutionException {
        try {
            // 初始化模式：如果用户配置了自定义 pattern 则使用，否则使用默认模式
            Pattern timestampPattern;
            if (pattern != null && !pattern.trim().isEmpty()) {
                timestampPattern = Pattern.compile(pattern);
            } else {
                timestampPattern = DEFAULT_TIMESTAMP_PATTERN;
            }
            // 获取并记录本地仓库目录
            File localRepo = new File(repoPath);
            // 检查仓库路径是否存在
            if (!localRepo.exists()) {
                return;
            }
            if (!localRepo.isDirectory()) {
                return;
            }
            String modelName = project.getArtifactId();
            // 清理各组中的旧版本文件（.jar 和 pom 和 -sources.jar ）和所有孤立的元数据文件（metadataExtensions）
            String executedFlag = "clean.old.versions.executed";// 使用系统属性确保 cleanOldVersions 只执行一次
            boolean alreadyExecuted = System.getProperty(executedFlag) != null;
            if (!alreadyExecuted) {
                System.setProperty(executedFlag, "true");
                long startCleanRepo = System.currentTimeMillis();
                cleanOldVersions(timestampPattern);
                getLog().info("clean repository old versions SNAPSHOT File：" + (System.currentTimeMillis() - startCleanRepo) + " ms");
            }
            long startCheck = System.currentTimeMillis();
            boolean shouldCheck = false;
            //if (CHECK_ALL_PRODUCT_MODULES) {
            // 方案一：开发规范中，模块名必定有 core 或 model 或 mapper，
            shouldCheck = modelName.contains("core") || modelName.contains("mapper") || modelName.contains("model");
            //}
            //else {
            //    shouldCheck = modelName.contains("core-ar");
            //}
            // 方案二：未按照 开发规范建模块名的部分，通过检查项目目录是否包含 mapper目录 或 model目录 来开启
            if (!shouldCheck) {
                File javaBaseDir = new File(project.getBasedir(), "src/main/java/");
                if (javaBaseDir.exists() && javaBaseDir.isDirectory()) {
                    boolean hasMapper = containsDirectory(javaBaseDir, "mapper", "dao");
                    boolean hasModel = containsDirectory(javaBaseDir, "model", "entity");
                    if (hasMapper || hasModel) {
                        shouldCheck = true;
                        // 确定逻辑模块名
                        if (hasMapper && hasModel) {
                            logicalModeName = "mapper+model";
                        } else if (hasMapper) {
                            logicalModeName = "mapper";
                        } else {
                            logicalModeName = "model";
                        }
                    }
                }
            }
            List<String> allErrors = new ArrayList<>();
            if (shouldCheck) {
                // 统一处理XML文件，只读取一次
                if (modelName.contains("mapper") || (!"".equals(logicalModeName) && logicalModeName.contains("mapper"))) {
                    // 统一处理所有XML文件，一次性读取并进行多项检查：2，3，4，5
                    List<String> xmlErrors = processAllXmlFiles();
                    allErrors.addAll(xmlErrors);
                }
                if (modelName.contains("model") || (!"".equals(logicalModeName) && logicalModeName.contains("model"))) {
                    // 6，检查 DTO，VO 中的  @TableField 注解
                    checkTableFieldInDtoVo();
                }
                if (modelName.contains("mapper") || (!"".equals(logicalModeName) && logicalModeName.contains("mapper"))) {
                    // 7，检查 Mapper.java 方法 是否缺少 @Param注解
                    List<String> mapperErrors = checkMapperAnnotations();
                    allErrors.addAll(mapperErrors);
                }
                // 统一输出所有错误
                if (!allErrors.isEmpty()) {
                    // 严于律己，宽以待人
                    // 如果当前模块是 AR 模块 或 insu模块，则抛出异常，否则只 出书警告日志
                    if (modelName.contains("-ar-mapper") || modelName.startsWith("insu-")) {
                        StringBuilder exceptionMessage = new StringBuilder();
                        for (String error : allErrors) {
                            exceptionMessage.append(error).append("\n");
                        }
                        throw new MojoExecutionException(exceptionMessage.toString());
                    }
                }
            }
            if (modelName.contains("model") || modelName.contains("mapper") || !"".equals(logicalModeName)) {
                getLog().info("checkCode：" + (System.currentTimeMillis() - startCheck) + " ms");
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            getLog().error("clean-check-plugin 执行异常", e);
        }
    }

    /**
     * 1，清理旧版本的 SNAPSHOT 文件和孤立的元数据文件
     *
     * @throws IOException 当文件读取或写入失败时抛出
     */
    private void cleanOldVersions(Pattern timestampPattern) throws IOException {
        File localRepo = new File(repoPath);
        if (!localRepo.exists()) {
            return;
        }
        final Map<String, Map<String, List<File>>> artifactGroups = new HashMap<>();
        // 遍历仓库目录，按 artifact 分组收集文件
        Files.walkFileTree(localRepo.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = fileNamePath.toString();
                // 只处理带时间戳的 SNAPSHOT 文件
                if (timestampPattern.matcher(fileName).matches()) {
                    String artifactKey = extractArtifactKey(fileName, timestampPattern);
                    String fileType = extractFileType(fileName);

                    if (artifactKey != null) {
                        artifactGroups.computeIfAbsent(artifactKey, k -> new HashMap<>())
                                .computeIfAbsent(fileType, k -> new ArrayList<>())
                                .add(file.toFile());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // 收集需要删除的文件
        final List<File> filesToDelete = new ArrayList<>();
        // 对每个 artifact，保留最新版本，删除旧版本
        for (Map.Entry<String, Map<String, List<File>>> artifactEntry : artifactGroups.entrySet()) {
            for (Map.Entry<String, List<File>> typeEntry : artifactEntry.getValue().entrySet()) {
                List<File> versions = typeEntry.getValue();
                if (versions.size() <= 1) {
                    continue; // 只有一个版本，不需要删除
                }
                // 按时间戳排序，最新的在前
                versions.sort((f1, f2) -> {
                    String ts1 = extractTimestamp(f1.getName(), timestampPattern);
                    String ts2 = extractTimestamp(f2.getName(), timestampPattern);
                    return ts2.compareTo(ts1); // 降序排列
                });
                // 保留最新的，其余标记删除
                for (int i = 1; i < versions.size(); i++) {
                    filesToDelete.add(versions.get(i));
                }
            }
        }
        // 收集所有孤立的元数据文件
        collectOrphanMetadata(localRepo, artifactGroups, filesToDelete);
        // 执行删除操作
        for (File file : filesToDelete) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
            }
        }
    }

    /**
     * 统一处理所有XML文件，一次性读取并进行多项检查
     * 2，检查: LATERAL 子句是否包含 order by
     * 3，检查: 数据库不兼容函数 UNNEST函数，ISNULL函数，IFNULL函数，NVL2函数，IF函数，DATE_FORMAT函数，TOP语法，date函数
     * 4，检查: 检查重复ID，XML标签未闭合
     * 5，检查: 检查 SQL 字段名是否包含双引号
     */
    private List<String> processAllXmlFiles() throws Exception {
        List<String> errors = new ArrayList<>();
        String resolvedPath = mapperXmlPath;
        if (resolvedPath.contains("${project.basedir}")) {
            resolvedPath = resolvedPath.replace("${project.basedir}", project.getBasedir().getAbsolutePath());
        }
        File xmlBaseDir = new File(resolvedPath);
        if (!xmlBaseDir.exists() || !xmlBaseDir.isDirectory()) {
            getLog().debug("clean-check-plugin XML目录不存在: " + resolvedPath);
            return errors;
        }
        List<File> mapperXmlFiles = new ArrayList<>();
        Files.walkFileTree(xmlBaseDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".xml")) {
                    mapperXmlFiles.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (File xmlFile : mapperXmlFiles) {
            try {
                List<String> lines = Files.readAllLines(xmlFile.toPath());
                String content = String.join("\n", lines);
                String fileName = xmlFile.getName();
                // 2，检查: LATERAL 子句是否包含 order by
                checkLateralOrderBy(fileName, lines, content);
                // 3，检查: 数据库不兼容函数 UNNEST函数，ISNULL函数，IFNULL函数，NVL2函数，IF函数，DATE_FORMAT函数，TOP语法，，date函数
                checkDatabaseCompatibility(fileName, lines, content);
                // 4，检查: 检查重复ID，XML标签未闭合
                List<String> duplicateErrorStrings = checkDuplicateIds(content, fileName, getLog());
                List<String> formatErrors = checkXmlFormat(content, fileName, getLog());
                // 5，检查: 检查 SQL 字段名是否包含双引号
                List<Integer> quotedLines = findQuotedFieldsInSql(content);
                if (!quotedLines.isEmpty()) {
                    // 将行号排序并去重
                    quotedLines.sort(Integer::compareTo);
                    StringBuilder lineNumbers = new StringBuilder();
                    for (int i = 0; i < quotedLines.size(); i++) {
                        if (i > 0) {
                            lineNumbers.append(", ");
                        }
                        lineNumbers.append("第").append(quotedLines.get(i)).append("行");
                    }
                    getLog().warn(fileName + "(" + lineNumbers.toString() + ") - SQL片段 字段名 包含双引号");
                }
                errors.addAll(formatErrors);
                errors.addAll(duplicateErrorStrings);
            } catch (IOException e) {
                getLog().debug("clean-check-plugin 读取XML文件失败: " + xmlFile.getAbsolutePath(), e);
            }
        }
        return errors;
    }

    /**
     * 2，检查 LATERAL 子句是否包含 order by
     */
    private void checkLateralOrderBy(String fileName, List<String> lines, String content) {
        List<AutoCleanMojoLateralClause> autoCleanMojoLateralClauses = extractLateralClauses(content);

        Map<String, List<Integer>> warningGroups = new HashMap<>();

        for (AutoCleanMojoLateralClause clause : autoCleanMojoLateralClauses) {
            if (containsOrderBy(clause.content)) {
                String statementName = extractStatementName(lines, clause.startLine);
                int orderByLine = findOrderByLineNumber(lines, clause.startLine, clause.content);
                String groupKey = fileName + (statementName != null ? "#" + statementName : "");

                if (!warningGroups.containsKey(groupKey)) {
                    warningGroups.put(groupKey, new ArrayList<>());
                }
                warningGroups.get(groupKey).add(orderByLine);
            }
        }

        for (Map.Entry<String, List<Integer>> entry : warningGroups.entrySet()) {
            String prefix = entry.getKey();
            List<Integer> lineNumbers = entry.getValue();

            if (!lineNumbers.isEmpty()) {
                lineNumbers.sort(Integer::compareTo);
                String linesStr = lineNumbers.stream()
                        .map(line -> "第" + line + "行")
                        .collect(java.util.stream.Collectors.joining(", "));
                String warningMsg = prefix + "(" + linesStr + ") - 海量数据库 LATERAL 子查询内不允许排序";
                getLog().warn(warningMsg);
            }
        }
    }

    /**
     * 3，检查数据库不兼容函数 UNNEST函数，ISNULL函数，IFNULL函数，NVL2函数，IF函数，DATE_FORMAT函数，TOP语法，group_concat，date函数
     */
    private void checkDatabaseCompatibility(String fileName, List<String> lines, String content) {
        Map<String, String> incompatibleItems = new HashMap<>();
        incompatibleItems.put("UNNEST", "Seata官方不支持 UNNEST函数");
        incompatibleItems.put("(?i)\\bISNULL\\s*\\(", "高斯数据库不支持 ISNULL函数，建议使用COALESCE或CASE WHEN");
        incompatibleItems.put("(?i)\\bIFNULL\\s*\\(", "高斯数据库不支持 IFNULL函数，建议使用COALESCE");
        incompatibleItems.put("(?i)\\bNVL2\\s*\\(", "高斯数据库不支持 NVL2函数，建议使用CASE WHEN");
        incompatibleItems.put("(?i)\\bIF\\s*\\(", "高斯数据库不支持 IF函数，建议使用CASE WHEN");
        incompatibleItems.put("(?i)\\bdate_format\\s*\\(", "高斯数据库不支持 DATE_FORMAT函数");
        incompatibleItems.put("(?i)\\bTOP\\s+\\d+", "高斯数据库不支持 TOP语法，建议使用LIMIT");
        incompatibleItems.put("(?i)\\bgroup_concat\\s*\\(", "海量数据库不支持 GROUP_CONCAT函数");
        incompatibleItems.put("(?i)\\bDATE\\s*\\(", "函数date()是mysql语法，在多数据库间兼容性差");
        incompatibleItems.put("(?i)\\bLISTAGG\\s*\\(\\s*DISTINCT", "LISTAGG使用DISTINCT时存在跨数据库兼容性问题：金仓不支持DISTINCT与WITHIN GROUP同时使用，海量不支持单独的DISTINCT写法。建议改用子查询去重后再聚合，或使用数据库特定的替代方案");

        Map<String, Map<String, List<Integer>>> groupedWarnings = new HashMap<>();

        for (Map.Entry<String, String> entry : incompatibleItems.entrySet()) {
            String pattern = entry.getKey();
            String message = entry.getValue();
            Pattern regexPattern = Pattern.compile(pattern);
            Matcher matcher = regexPattern.matcher(content);
            while (matcher.find()) {
                int matchStart = matcher.start();
                int lineNumber = countLines(content, 0, matchStart);
                String statementName = extractStatementName(lines, lineNumber);
                String groupKey = fileName + (statementName != null ? "#" + statementName : "");

                if (!groupedWarnings.containsKey(groupKey)) {
                    groupedWarnings.put(groupKey, new HashMap<>());
                }
                Map<String, List<Integer>> messageToLines = groupedWarnings.get(groupKey);
                if (!messageToLines.containsKey(message)) {
                    messageToLines.put(message, new ArrayList<>());
                }
                messageToLines.get(message).add(lineNumber);
            }
        }

        for (Map.Entry<String, Map<String, List<Integer>>> entry : groupedWarnings.entrySet()) {
            String prefix = entry.getKey();
            Map<String, List<Integer>> messageToLines = entry.getValue();

            for (Map.Entry<String, List<Integer>> msgEntry : messageToLines.entrySet()) {
                String message = msgEntry.getKey();
                List<Integer> lineNumbers = msgEntry.getValue();

                if (!lineNumbers.isEmpty()) {
                    lineNumbers.sort(Integer::compareTo);
                    String linesStr = lineNumbers.stream()
                            .map(line -> "第" + line + "行")
                            .collect(java.util.stream.Collectors.joining(", "));
                    String warningMsg = prefix + " - (" + linesStr + ")-" + message;
                    getLog().warn(warningMsg);
                }
            }
        }
    }

    /**
     * 4，检查 DTO 和 VO 类中的 @TableField 注解
     */
    private void checkTableFieldInDtoVo() throws Exception {
        String modelName = project.getArtifactId();
        boolean shouldCheck = modelName.contains("model") ||
                (!"".equals(logicalModeName) && logicalModeName.contains("model"));
        if (!shouldCheck) {
            return;
        }
        String resolvedPath = dtoVoPath;
        if (resolvedPath.contains("${project.basedir}")) {
            resolvedPath = resolvedPath.replace("${project.basedir}", project.getBasedir().getAbsolutePath());
        }
        File javaBaseDir = new File(resolvedPath);
        if (!javaBaseDir.exists() || !javaBaseDir.isDirectory()) {
            getLog().debug("clean-check-plugin DTO/VO目录不存在: " + resolvedPath);
            return;
        }
        List<File> dtoVoFiles = new ArrayList<>();
        Files.walkFileTree(javaBaseDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = fileNamePath.toString();
                if ((fileName.endsWith("DTO.java") || fileName.endsWith("VO.java") ||
                        fileName.endsWith("Dto.java") || fileName.endsWith("Vo.java") ||
                        fileName.endsWith("PO.java")) &&
                        !fileName.equals("DTO.java") && !fileName.equals("VO.java") && !fileName.equals("PO.java")) {
                    dtoVoFiles.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (File javaFile : dtoVoFiles) {
            try {
                List<String> lines = Files.readAllLines(javaFile.toPath());
                boolean isDtoOrVo = false;
                String fileName = javaFile.getName();
                for (String line : lines) {
                    String trimmedLine = line.trim();
                    if (trimmedLine.contains("class ") &&
                            (trimmedLine.endsWith("DTO") || trimmedLine.endsWith("VO") ||
                                    trimmedLine.endsWith("Dto") || trimmedLine.endsWith("Vo") ||
                                    trimmedLine.endsWith("PO") ||
                                    trimmedLine.matches(".*class\\s+\\w+DTO\\s+.*") ||
                                    trimmedLine.matches(".*class\\s+\\w+VO\\s+.*") ||
                                    trimmedLine.matches(".*class\\s+\\w+Dto\\s+.*") ||
                                    trimmedLine.matches(".*class\\s+\\w+Vo\\s+.*") ||
                                    trimmedLine.matches(".*class\\s+\\w+PO\\s+.*"))) {
                        isDtoOrVo = true;
                        break;
                    }
                }
                if (!isDtoOrVo) {
                    continue;
                }
                String className = extractClassName(javaFile, javaBaseDir, getLog());
                if (!fileName.endsWith("PO.java")) {
                    List<String> fieldWarnings = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains("@TableField") || line.contains("@TableId")) {
                            fieldWarnings.add("第" + (i + 1) + "行");
                        }
                    }
                    if (!fieldWarnings.isEmpty()) {
                        String classPrefix = className != null ? className : javaFile.getName();
                        String warningMsg = classPrefix + " - DTO/VO 类不应使用 @TableField/@TableId 注解，涉及行: "
                                + String.join(", ", fieldWarnings);
                        getLog().warn(warningMsg);
                    }
                }
                if (fileName.endsWith("PO.java") && modelName.contains("-ar-model")) {
                    checkPoJavaTimeFields(className, lines, javaFile, getLog());
                }
            } catch (IOException e) {
                getLog().debug("clean-check-plugin 读取文件失败: " + javaFile.getAbsolutePath(), e);
            }
        }
    }


    /**
     * 7，错误级别： 检查 Mapper.java 方法 是否缺少 @Param注解
     *
     * @throws Exception
     */
    private List<String> checkMapperAnnotations() throws Exception {
        List<String> errors = new ArrayList<>();
        String modelName = project.getArtifactId();
        // 如果是多模块父项目(包含子模块),则遍历所有子模块并清理它们的 target 目录
        if (isMultiModuleProject(project)) {
            cleanChildModulesTargetDirectories(project);
        } else {
            // 单个模块,只清理自己的 target 目录
            cleanOwnTargetDirectory();
        }
        //包含 mapper，才去检查
        boolean shouldCheck = modelName.contains("mapper") ||
                (!"".equals(logicalModeName) && logicalModeName.contains("mapper"));
        if (!shouldCheck) {
            return errors;
        }
        String resolvedPath = mapperJavaPath;
        if (resolvedPath.contains("${project.basedir}")) {
            resolvedPath = resolvedPath.replace("${project.basedir}", project.getBasedir().getAbsolutePath());
        }
        if (resolvedPath.contains("${project.build.directory}")) {
            resolvedPath = resolvedPath.replace("${project.build.directory}", projectBuildDirectory);
        }
        File javaBaseDir = new File(resolvedPath);
        if (!javaBaseDir.exists() || !javaBaseDir.isDirectory()) {
            return errors;
        }
        Map<String, File> mapperJavaFiles = new HashMap<>();
        Files.walkFileTree(javaBaseDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith("Mapper.java")) {
                    String className = extractClassName(file.toFile(), javaBaseDir, getLog());
                    if (className != null) {
                        mapperJavaFiles.put(className, file.toFile());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Map.Entry<String, File> entry : mapperJavaFiles.entrySet()) {
            File javaFile = entry.getValue();
            List<MapperMethod> methods = parseMapperMethods(javaFile, getLog());
            for (MapperMethod method : methods) {
                if (!method.parameters.isEmpty()) {
                    // 严于律己，宽以待人
                    // 如果当前模块是 AR 模块 或 insu模块，则抛出异常，否则只 出书警告日志
                    boolean iscoreArMapper = modelName.contains("-ar-mapper") || modelName.startsWith("insu-");
                    if (iscoreArMapper) {
                        if (method.parameters.size() == 1 && isMapType(method.parameterTypes.get(0))) {
                            continue;
                        }
                    } else {
                        if (method.parameters.size() <= 1) {
                            continue;
                        }
                        if (method.parameters.size() == 1 && isMapType(method.parameterTypes.get(0))) {
                            continue;
                        }
                    }
                    List<String> missingParams = new ArrayList<>();
                    for (int i = 0; i < method.parameters.size(); i++) {
                        if (!method.paramAnnotations.get(i)) {
                            missingParams.add(method.parameters.get(i));
                        }
                    }
                    if (!missingParams.isEmpty()) {
                        String errorMsg = javaFile.getName() + "#" + method.name +
                                " - 方法的参数: " + String.join(", ", missingParams) + " 缺少 @Param 注解";
                        errors.add(errorMsg);
                        // 严于律己，宽以待人
                        // 如果当前模块是 AR 模块 或 insu模块，则抛出异常，否则只 出书警告日志
                        if (modelName.contains("-ar-mapper") || modelName.startsWith("insu-")) {
                            getLog().error(errorMsg);
                        } else {
                            getLog().warn(errorMsg);
                        }
                    }
                }
            }
        }
        return errors;
    }
}
