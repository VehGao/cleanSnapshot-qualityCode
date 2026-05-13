package io.github.veh.maven.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckDatabaseCompatibilityInXmlUtil {
    /**
     * 查找 order by 在文件中的实际行号
     */
    protected static int findOrderByLineNumber(List<String> lines, int startLine, String lateralContent) {
        // 从 LATERAL 子句开始的位置向后查找 order by
        Pattern orderByPattern = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);

        for (int i = startLine - 1; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = orderByPattern.matcher(line);
            if (matcher.find()) {
                return i + 1; // 行号从1开始
            }

            // 如果已经超出了 LATERAL 子句的范围，停止查找
            if (i > startLine && line.trim().endsWith(")") && !line.contains("(")) {
                break;
            }
        }

        return startLine; // 如果没找到，返回 LATERAL 子句的起始行
    }

    /**
     * 从 XML 内容中提取所有 LATERAL 子句
     */
    protected static List<AutoCleanMojoLateralClause> extractLateralClauses(String content) {
        List<AutoCleanMojoLateralClause> clauses = new ArrayList<>();
        String lowerContent = content.toLowerCase();
        int searchStart = 0;

        while (true) {
            int lateralIndex = lowerContent.indexOf("lateral", searchStart);
            if (lateralIndex == -1) {
                break;
            }

            // 找到 LATERAL 关键字后，提取其后的子查询内容
            int subqueryStart = findSubqueryStart(content, lateralIndex);
            if (subqueryStart == -1) {
                searchStart = lateralIndex + 7;
                continue;
            }

            // 找到对应的结束位置（匹配的括号或语句结束）
            int subqueryEnd = findSubqueryEnd(content, subqueryStart);
            if (subqueryEnd == -1) {
                searchStart = lateralIndex + 7;
                continue;
            }

            String subqueryContent = content.substring(subqueryStart, subqueryEnd);
            int startLine = countLines(content, 0, lateralIndex);

            AutoCleanMojoLateralClause clause = new AutoCleanMojoLateralClause();
            clause.content = subqueryContent;
            clause.startLine = startLine;
            clauses.add(clause);

            searchStart = subqueryEnd;
        }

        return clauses;
    }

    /**
     * 查找子查询开始位置（跳过 LATERAL 关键字和可能的空格、左括号）
     */
    protected static int findSubqueryStart(String content, int lateralIndex) {
        int pos = lateralIndex + 7; // 跳过 "lateral"
        while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
            pos++;
        }
        if (pos < content.length() && content.charAt(pos) == '(') {
            pos++;
        }
        return pos < content.length() ? pos : -1;
    }

    /**
     * 查找子查询结束位置（匹配的右括号）
     */
    protected static int findSubqueryEnd(String content, int startPos) {
        int depth = 1;
        int pos = startPos;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        while (pos < content.length() && depth > 0) {
            char c = content.charAt(pos);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return pos;
                    }
                }
            }
            pos++;
        }

        return -1;
    }

    /**
     * 检查子查询内容是否包含 order by
     */
    protected static boolean containsOrderBy(String content) {
        String lowerContent = content.toLowerCase();
        // 使用正则表达式匹配 order by，确保是完整的单词
        Pattern orderByPattern = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);
        return orderByPattern.matcher(lowerContent).find();
    }

    /**
     * 从指定行附近提取 statement 名称
     */
    protected static String extractStatementName(List<String> lines, int targetLine) {
        // 向上查找最近的 select/sql 标签的 id 属性
        for (int i = targetLine - 1; i >= Math.max(0, targetLine - 100); i--) {
            String line = lines.get(i).trim();
            Pattern pattern = Pattern.compile("(?:<select|<sql)[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }

            // 如果遇到了另一个 statement 标签的开始，停止搜索
            if (line.matches("(?i).*<(?:select|insert|update|delete|sql)[\\s>].*")) {
                break;
            }
        }

        // 如果向上没找到，尝试向下查找（LATERAL 可能在 statement 定义之前）
        for (int i = targetLine; i < Math.min(lines.size(), targetLine + 50); i++) {
            String line = lines.get(i).trim();
            Pattern pattern = Pattern.compile("(?:<select|<sql)[^>]*\\sid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * 计算从 start 到 end 之间的行数
     */
    protected static int countLines(String content, int start, int end) {
        int count = 1;
        for (int i = start; i < end && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
