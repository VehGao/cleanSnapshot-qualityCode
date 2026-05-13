package io.github.veh.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckTableFieldInDtoVoUtil {
    /**
     * 从指定行附近提取字段名
     */
    protected static String extractFieldName(List<String> lines, int annotationLine) {
        for (int i = annotationLine + 1; i < Math.min(lines.size(), annotationLine + 5); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                continue;
            }

            Pattern pattern = Pattern.compile("(?:private|public|protected)?\\s*(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*[;=]");
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }

            if (line.contains(";") || line.contains("=")) {
                break;
            }
        }
        return null;
    }

    protected static void checkPoJavaTimeFields(String className, List<String> lines, File javaFile, org.apache.maven.plugin.logging.Log getLog) {
        List<String> timeTypeFields = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }

            if (line.contains("LocalDate") || line.contains("LocalTime") || line.contains("LocalDateTime")) {
                Pattern pattern = Pattern.compile("(?:private|public|protected)?\\s*(?:static\\s+)?(?:final\\s+)?(?:java\\.time\\.)?(LocalDate|LocalTime|LocalDateTime)[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*[;=]");
                Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {
                    String fieldType = matcher.group(1);
                    String fieldName = matcher.group(2);
                    timeTypeFields.add(fieldName + "(" + fieldType + ", 第" + (i + 1) + "行)");
                }
            }
        }

        if (!timeTypeFields.isEmpty()) {
            String debugMsg = (className != null ? className : javaFile.getName()) +
                    " - PO类包含 Local时间类型字段: " + String.join(", ", timeTypeFields);
            getLog.warn(debugMsg);
        }
    }
}
