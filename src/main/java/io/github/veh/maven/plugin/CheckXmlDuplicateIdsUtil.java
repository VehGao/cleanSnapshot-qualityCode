package io.github.veh.maven.plugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckXmlDuplicateIdsUtil {

    /**
     * 检查单个XML文件中是否存在重复的ID定义
     */
    protected static List<String> checkDuplicateIds(String xmlContent, String fileName, org.apache.maven.plugin.logging.Log getLog) {
        List<String> errorStrings = new ArrayList<>();

        Map<String, String> resultMapIds = new HashMap<>();
        Map<String, String> sqlIds = new HashMap<>();
        Map<String, String> selectIds = new HashMap<>();
        Map<String, String> insertIds = new HashMap<>();
        Map<String, String> updateIds = new HashMap<>();
        Map<String, String> deleteIds = new HashMap<>();

        String[] lines = xmlContent.split("\n");

        Pattern resultMapPattern = Pattern.compile("<resultMap[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern sqlPattern = Pattern.compile("<sql[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern selectPattern = Pattern.compile("<select[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern insertPattern = Pattern.compile("<insert[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern updatePattern = Pattern.compile("<update[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Pattern deletePattern = Pattern.compile("<delete[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

        boolean inComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;

            if (inComment) {
                if (line.contains("-->")) {
                    inComment = false;
                }
                continue;
            }

            if (line.contains("<!--")) {
                if (!line.contains("-->")) {
                    inComment = true;
                    continue;
                } else {
                    continue;
                }
            }

            checkAndRecordId(resultMapPattern, line, resultMapIds, lineNumber, "resultMap", fileName, errorStrings);
            checkAndRecordId(sqlPattern, line, sqlIds, lineNumber, "sql", fileName, errorStrings);
            checkAndRecordId(selectPattern, line, selectIds, lineNumber, "select", fileName, errorStrings);
            checkAndRecordId(insertPattern, line, insertIds, lineNumber, "insert", fileName, errorStrings);
            checkAndRecordId(updatePattern, line, updateIds, lineNumber, "update", fileName, errorStrings);
            checkAndRecordId(deletePattern, line, deleteIds, lineNumber, "delete", fileName, errorStrings);
        }
        if (!errorStrings.isEmpty()) {
            for (String errorStr : errorStrings) {
                getLog.error(errorStr);
            }
        }
        return errorStrings;
    }

    private static void checkAndRecordId(Pattern pattern, String line, Map<String, String> idMap,
                                         int lineNumber, String elementType, String fileName, List<String> errorStrings) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (idMap.containsKey(id)) {
                int firstOccurrenceLine = Integer.parseInt(idMap.get(id).split(":")[1]);
                String warningMsg = fileName + "#" + elementType + "[id=" + id + "]" +
                        "(第" + lineNumber + "行) - ID '" + id + "' 重复定义（首次出现在第" + firstOccurrenceLine + "行）";
                errorStrings.add(warningMsg);
            } else {
                idMap.put(id, elementType + ":" + lineNumber);
            }
        }
    }
}

