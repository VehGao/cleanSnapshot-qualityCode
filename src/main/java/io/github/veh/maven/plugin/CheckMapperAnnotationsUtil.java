package io.github.veh.maven.plugin;

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckMapperAnnotationsUtil {
    /**
     * 判断是否为多模块项目
     */
    protected static boolean isMultiModuleProject(MavenProject project) {
        return project.getModules() != null && !project.getModules().isEmpty();
    }

    /**
     * 清理所有子模块的 ${project.build.directory} 目录
     */
    protected static void cleanChildModulesTargetDirectories(MavenProject project) {
        File parentDir = project.getFile().getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            return;
        }

        List<String> modules = project.getModules();
        if (modules == null || modules.isEmpty()) {
            return;
        }

        for (String moduleName : modules) {
            File moduleDir = new File(parentDir, moduleName);
            if (moduleDir.exists() && moduleDir.isDirectory()) {
                File buildDir = new File(moduleDir, "${project.build.directory}");
                if (buildDir.exists() && buildDir.isDirectory()) {
                    try {
                        deleteDirectory(buildDir);
                    } catch (IOException e) {
                    }
                }
            }
        }
    }


    /**
     * 清理当前模块自己的 ${project.build.directory} 目录
     */
    protected static void cleanOwnTargetDirectory() {
        File buildDir = new File("${project.build.directory}");
        if (buildDir.exists() && buildDir.isDirectory()) {
            try {
                deleteDirectory(buildDir);
            } catch (IOException e) {
            }
        }
    }


    protected static String extractClassName(File javaFile, File baseDir, org.apache.maven.plugin.logging.Log getLog) {
        try {
            String absolutePath = javaFile.getAbsolutePath();
            String basePath = baseDir.getAbsolutePath();
            if (!absolutePath.startsWith(basePath)) {
                return null;
            }
            String relativePath = absolutePath.substring(basePath.length());
            if (relativePath.startsWith(File.separator)) {
                relativePath = relativePath.substring(1);
            }
            String className = relativePath.replace(File.separator, ".");
            if (className.endsWith(".java")) {
                className = className.substring(0, className.length() - 5);
            }
            return className;
        } catch (Exception e) {
            getLog.debug("clean-check-plugin 提取类名失败: " + javaFile.getAbsolutePath(), e);
            return null;
        }
    }

    protected static List<MapperMethod> parseMapperMethods(File javaFile, org.apache.maven.plugin.logging.Log getLog) throws IOException {
        List<MapperMethod> methods = new ArrayList<>();
        List<String> lines = Files.readAllLines(javaFile.toPath());

        // 先移除所有注释内容，避免误报
        List<String> codeLinesWithoutComments = removeComments(lines);

        StringBuilder currentMethod = new StringBuilder();
        boolean inMethod = false;
        int braceCount = 0;
        int lineNumber = 0;
        int methodStartLine = 0;
        for (String line : codeLinesWithoutComments) {
            lineNumber++;
            String trimmedLine = line.trim();

            if (!inMethod && isMethodDeclaration(trimmedLine)) {
                inMethod = true;
                methodStartLine = lineNumber;
                currentMethod.setLength(0);
                currentMethod.append(line).append("\n");
                braceCount += countChar(line, '{') - countChar(line, '}');
                if (line.contains(";")) {
                    String methodContent = currentMethod.toString();
                    MapperMethod method = parseSingleMethod(methodContent, getLog);
                    if (method != null) {
                        methods.add(method);
                    } else {
                        getLog.debug("clean-check-plugin 解析方法失败: " + javaFile.getName() + " 第" + methodStartLine + "行");
                    }
                    inMethod = false;
                    currentMethod.setLength(0);
                    braceCount = 0;
                }
            } else if (inMethod) {
                currentMethod.append(line).append("\n");
                braceCount += countChar(line, '{') - countChar(line, '}');

                if (line.trim().endsWith(";") || (braceCount <= 0 && !line.trim().isEmpty())) {
                    String methodContent = currentMethod.toString();
                    MapperMethod method = parseSingleMethod(methodContent, getLog);
                    if (method != null) {
                        methods.add(method);
                    } else {
                        getLog.debug("clean-check-plugin 解析方法失败: " + javaFile.getName() + " 第" + methodStartLine + "-" + lineNumber + "行");
                    }
                    inMethod = false;
                    currentMethod.setLength(0);
                    braceCount = 0;
                }
            }
        }

        return methods;
    }

    /**
     * 移除Java代码中的所有注释（单行注释、多行注释、文档注释）
     */
    protected static List<String> removeComments(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean inBlockComment = false;

        for (String line : lines) {
            StringBuilder cleanedLine = new StringBuilder();
            int i = 0;
            String trimmedLine = line.trim();

            // 如果当前行完全是空行，保留空行以保持行号一致
            if (trimmedLine.isEmpty()) {
                result.add("");
                continue;
            }

            while (i < line.length()) {
                if (inBlockComment) {
                    // 在多行注释中，查找结束标记
                    if (i < line.length() - 1 && line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                } else {
                    // 检查是否进入多行注释
                    if (i < line.length() - 1 && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                        inBlockComment = true;
                        i += 2;
                    }
                    // 检查是否是单行注释
                    else if (i < line.length() - 1 && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                        // 单行注释的剩余部分全部跳过
                        break;
                    }
                    // 检查是否是字符串字面量（避免误判字符串中的注释符号）
                    else if (line.charAt(i) == '"') {
                        cleanedLine.append(line.charAt(i));
                        i++;
                        // 跳过字符串内容
                        while (i < line.length() && line.charAt(i) != '"') {
                            if (line.charAt(i) == '\\') {
                                cleanedLine.append(line.charAt(i));
                                i++;
                                if (i < line.length()) {
                                    cleanedLine.append(line.charAt(i));
                                    i++;
                                }
                            } else {
                                cleanedLine.append(line.charAt(i));
                                i++;
                            }
                        }
                        if (i < line.length()) {
                            cleanedLine.append(line.charAt(i));
                            i++;
                        }
                    }
                    // 检查是否是字符字面量
                    else if (line.charAt(i) == '\'') {
                        cleanedLine.append(line.charAt(i));
                        i++;
                        while (i < line.length() && line.charAt(i) != '\'') {
                            if (line.charAt(i) == '\\') {
                                cleanedLine.append(line.charAt(i));
                                i++;
                                if (i < line.length()) {
                                    cleanedLine.append(line.charAt(i));
                                    i++;
                                }
                            } else {
                                cleanedLine.append(line.charAt(i));
                                i++;
                            }
                        }
                        if (i < line.length()) {
                            cleanedLine.append(line.charAt(i));
                            i++;
                        }
                    }
                    // 普通代码
                    else {
                        cleanedLine.append(line.charAt(i));
                        i++;
                    }
                }
            }

            // 如果清理后的行不为空，添加到结果中；否则添加空行保持行号
            result.add(cleanedLine.toString());
        }

        return result;
    }


    protected static boolean isMethodDeclaration(String trimmedLine) {
        if (!trimmedLine.contains("(")) {
            return false;
        }
        boolean hasAccessModifier = trimmedLine.contains("public ") ||
                trimmedLine.contains("protected static ") ||
                trimmedLine.contains("protected ");
        boolean hasStaticOrDefault = trimmedLine.startsWith("static ") ||
                trimmedLine.startsWith("default ");
        boolean hasAnnotation = trimmedLine.startsWith("@");
        if (hasAnnotation) {
            return false;
        }
        boolean hasReturnType = trimmedLine.matches("^(?:(?:public|private|protected|static|default)\\s+)*(?:<[^>]+>\\s+)?\\S+\\s+\\w+\\s*\\(.*");
        boolean isNotClassOrInterface = !trimmedLine.startsWith("class ") &&
                !trimmedLine.startsWith("interface ") &&
                !trimmedLine.startsWith("enum ") &&
                !trimmedLine.startsWith("new ") &&
                !trimmedLine.startsWith("if ") &&
                !trimmedLine.startsWith("for ") &&
                !trimmedLine.startsWith("while ") &&
                !trimmedLine.startsWith("switch ") &&
                !trimmedLine.startsWith("return ") &&
                !trimmedLine.startsWith("throw ");
        return (hasAccessModifier || hasStaticOrDefault || hasReturnType) && isNotClassOrInterface;
    }


    protected static MapperMethod parseSingleMethod(String methodContent, org.apache.maven.plugin.logging.Log getLog) {
        try {
            String methodName = extractMethodName(methodContent);
            if (methodName == null) {
                return null;
            }
            List<ParameterInfo> parameters = extractParametersWithAnnotations(methodContent, getLog);
            boolean allHaveAnnotations = true;
            for (ParameterInfo param : parameters) {
                if (!param.hasAnnotation && !isIgnoredType(param.name, param.type)) {
                    allHaveAnnotations = false;
                    break;
                }
            }
            List<String> paramNames = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            for (ParameterInfo param : parameters) {
                paramNames.add(param.name);
                paramTypes.add(param.type);
            }
            MapperMethod method = new MapperMethod();
            method.name = methodName;
            method.hasParamAnnotation = allHaveAnnotations && !parameters.isEmpty();
            method.parameters = paramNames;
            method.parameterTypes = paramTypes;
            method.paramAnnotations = new ArrayList<>();
            for (ParameterInfo param : parameters) {
                method.paramAnnotations.add(param.hasAnnotation || isIgnoredType(param.name, param.type));
            }
            return method;
        } catch (Exception e) {
            return null;
        }
    }


    protected static List<ParameterInfo> extractParametersWithAnnotations(String methodContent, org.apache.maven.plugin.logging.Log getLog) {
        List<ParameterInfo> params = new ArrayList<>();
        int parenStart = methodContent.indexOf('(');
        int parenEnd = methodContent.lastIndexOf(')');
        if (parenStart == -1 || parenEnd == -1 || parenStart >= parenEnd) {
            getLog.debug("clean-check-plugin 无法找到参数部分: " + methodContent.replaceAll("\\s+", " ").substring(0, Math.min(80, methodContent.length())));
            return params;
        }
        String paramSection = methodContent.substring(parenStart + 1, parenEnd).trim();
        if (paramSection.isEmpty()) {
            return params;
        }
        List<String> paramList = splitParameters(paramSection);
        for (String param : paramList) {
            String trimmedParam = param.trim();
            if (!trimmedParam.isEmpty()) {
                ParameterInfo paramInfo = extractParamInfo(trimmedParam, getLog);
                if (paramInfo != null) {
                    params.add(paramInfo);
                } else {
                    getLog.debug("clean-check-plugin 无法解析参数: " + trimmedParam);
                }
            }
        }
        return params;
    }


    protected static List<String> splitParameters(String paramSection) {
        List<String> params = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : paramSection.toCharArray()) {
            if (c == '<') {
                depth++;
                current.append(c);
            } else if (c == '>') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                params.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            params.add(current.toString());
        }
        return params;
    }

    protected static ParameterInfo extractParamInfo(String paramDeclaration, org.apache.maven.plugin.logging.Log getLog) {
        boolean hasAnnotation = paramDeclaration.contains("@Param");
        String cleanParam = paramDeclaration.replaceAll("@\\w+\\s*\\([^)]*\\)", "").trim();
        int lastSpaceIndex = cleanParam.lastIndexOf(' ');
        if (lastSpaceIndex > 0) {
            String paramType = cleanParam.substring(0, lastSpaceIndex).trim();
            String paramName = cleanParam.substring(lastSpaceIndex + 1).trim();
            paramName = paramName.replaceAll("[\\[\\]]", "").trim();
            if (paramName.isEmpty() || paramName.contains("<") || paramName.contains(">")) {
                getLog.debug("clean-check-plugin 参数名可能解析错误: '" + paramName + "' 来自: " + paramDeclaration);
                return null;
            }
            ParameterInfo info = new ParameterInfo();
            info.name = paramName;
            info.type = paramType;
            info.hasAnnotation = hasAnnotation;
            return info;
        }
        getLog.debug("clean-check-plugin 无法提取参数信息: " + paramDeclaration);
        return null;
    }


    protected static boolean isIgnoredType(String paramName, String paramType) {
        if (paramType == null || paramType.isEmpty()) {
            return false;
        }
        String lowerType = paramType.toLowerCase();
        return lowerType.contains("page<") ||
                lowerType.equals("page") ||
                lowerType.endsWith(".page") ||
                lowerType.contains("ipage<") ||
                lowerType.equals("ipage") ||
                lowerType.endsWith(".ipage") ||
                lowerType.contains("pagedatavo<") ||
                lowerType.equals("pagedatavo") ||
                lowerType.endsWith(".pagedatavo");
    }

    protected static boolean isMapType(String paramType) {
        if (paramType == null || paramType.isEmpty()) {
            return false;
        }
        String lowerType = paramType.toLowerCase();
        return lowerType.equals("map") ||
                lowerType.equals("java.util.map") ||
                lowerType.startsWith("map<") ||
                lowerType.startsWith("java.util.map<") ||
                lowerType.endsWith(".map") ||
                lowerType.endsWith(".map<");
    }


    protected static class ParameterInfo {
        String name;
        String type;
        boolean hasAnnotation;
    }

    protected static String extractMethodName(String methodContent) {
        Pattern pattern = Pattern.compile("(?:public|private|protected|static|default)?\\s*(?:<[^>]+>\\s+)?(\\S+)\\s+(\\w+)\\s*\\(");
        Matcher matcher = pattern.matcher(methodContent);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }


    protected static int countChar(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    protected static class MapperMethod {
        String name;
        boolean hasParamAnnotation;
        List<String> parameters = new ArrayList<>();
        List<String> parameterTypes = new ArrayList<>();
        List<Boolean> paramAnnotations = new ArrayList<>();
    }

    /**
     * 递归删除目录及其所有内容
     */
    protected static void deleteDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        Files.delete(directory.toPath());
    }

}
