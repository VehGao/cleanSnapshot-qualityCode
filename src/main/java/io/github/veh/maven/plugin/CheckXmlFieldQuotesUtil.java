package io.github.veh.maven.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 检查 MyBatis XML 文件中 SQL 字段名是否包含双引号的工具类
 */
public class CheckXmlFieldQuotesUtil {

    /**
     * 匹配 SQL 标签（select/insert/update/delete/sql）中的内容
     * 排除 resultType="Map" 的 select 标签
     */
    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "<select(?!.*?resultType=\"Map\").*?>.*?</select>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INSERT_UPDATE_DELETE_PATTERN = Pattern.compile(
            "<(insert|update|delete|sql)>.*?</\\1>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /**
     * 检查 XML 内容中是否在 SQL 字段名中使用了双引号
     *
     * @param content XML 文件内容
     * @return 包含双引号的行号列表，如果为空则表示没有发现
     */
    protected static List<Integer> findQuotedFieldsInSql(String content) {
        List<Integer> quotedLines = new ArrayList<>();

        // 检查所有 SQL 标签
        checkPattern(content, SELECT_PATTERN, quotedLines);
        checkPattern(content, INSERT_UPDATE_DELETE_PATTERN, quotedLines);

        return quotedLines;
    }

    /**
     * 检查指定模式的 SQL 标签中是否包含带双引号的字段名
     */
    private static void checkPattern(String content, Pattern pattern, List<Integer> quotedLines) {
        java.util.regex.Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String tagContent = matcher.group();
            int tagStartPos = matcher.start();

            List<Integer> linesInTag = findQuotedFieldsInTag(tagContent, tagStartPos, content);
            quotedLines.addAll(linesInTag);
        }
    }

    /**
     * 在 SQL 标签内容中查找带双引号的字段名及其行号
     */
    private static List<Integer> findQuotedFieldsInTag(String sqlTagContent, int tagStartPos, String fullContent) {
        List<Integer> quotedLines = new ArrayList<>();
        int pos = 0;
        int length = sqlTagContent.length();

        while (pos < length) {
            // 查找下一个 '<' 字符
            int nextLt = sqlTagContent.indexOf('<', pos);

            if (nextLt == -1) {
                // 没有更多标签，处理剩余文本
                String remainingText = sqlTagContent.substring(pos);
                List<Integer> lines = findQuotedLinesInText(remainingText, tagStartPos + pos, fullContent);
                quotedLines.addAll(lines);
                break;
            }

            // 检查标签前的文本内容
            if (nextLt > pos) {
                String textBeforeTag = sqlTagContent.substring(pos, nextLt);
                List<Integer> lines = findQuotedLinesInText(textBeforeTag, tagStartPos + pos, fullContent);
                quotedLines.addAll(lines);
            }

            // 查找完整的标签结束位置
            int tagEnd = findTagEndProperly(sqlTagContent, nextLt);

            if (tagEnd == -1) {
                // 没有找到标签结束，将 '<' 作为普通文本处理
                pos = nextLt + 1;
            } else {
                // 提取完整标签
                String tag = sqlTagContent.substring(nextLt, tagEnd + 1);

                // 检查是否是自闭合标签
                boolean isSelfClosing = tag.trim().endsWith("/>");

                if (isSelfClosing) {
                    // 自闭合标签直接跳过
                    pos = tagEnd + 1;
                } else {
                    // 非自闭合标签，需要找到对应的结束标签
                    String tagName = extractTagName(tag);

                    if (tagName != null && !isVoidElement(tagName)) {
                        // 查找对应的结束标签
                        String closingTag = "</" + tagName + ">";
                        int closingTagPos = findClosingTag(sqlTagContent, tagEnd + 1, closingTag);

                        if (closingTagPos != -1) {
                            // 找到结束标签，递归处理内部内容
                            String innerContent = sqlTagContent.substring(tagEnd + 1, closingTagPos);

                            // 递归检查内部内容
                            List<Integer> innerLines = findQuotedFieldsInTag(innerContent, tagStartPos + tagEnd + 1, fullContent);
                            quotedLines.addAll(innerLines);

                            pos = closingTagPos + closingTag.length();
                        } else {
                            // 没有找到结束标签，只保留开始标签
                            pos = tagEnd + 1;
                        }
                    } else {
                        // 无法识别的标签或空元素，直接跳过
                        pos = tagEnd + 1;
                    }
                }
            }
        }

        return quotedLines;
    }

    /**
     * 在纯文本中查找包含双引号的行号（排除 SpEL 表达式和 AS 别名）
     */
    /**
     * 在纯文本中查找包含双引号的行号（排除 SpEL 表达式和 AS 别名）
     */
    private static List<Integer> findQuotedLinesInText(String text, int textStartPos, String fullContent) {
        List<Integer> quotedLines = new ArrayList<>();

        // 按行分割处理
        String[] lines = text.split("\n", -1);
        int currentPos = textStartPos;

        for (String line : lines) {
            // 移除 SpEL 表达式
            String cleanedLine = removeSpelExpressions(line);

            // 移除 AS 别名中的双引号
            cleanedLine = removeAsAliasQuotes(cleanedLine);

            // 检查是否还包含带大写字段名的双引号
            if (hasUppercaseQuotedFields(cleanedLine)) {
                int lineNum = countLines(fullContent, 0, currentPos);
                if (!quotedLines.contains(lineNum)) {
                    quotedLines.add(lineNum);
                }
            }

            // 更新位置（+1 是因为 split 去掉了换行符）
            currentPos += line.length() + 1;
        }

        return quotedLines;
    }

    /**
     * 检查文本中是否有包含大写字母的带双引号字段名
     * 只要双引号中包含至少一个大写字母就返回 true
     */
    private static boolean hasUppercaseQuotedFields(String text) {
        java.util.regex.Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text);

        while (matcher.find()) {
            String quotedContent = matcher.group(1);
            // 检查双引号内容中是否包含大写字母
            if (containsUppercase(quotedContent)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查字符串中是否包含大写字母
     */
    private static boolean containsUppercase(String str) {
        for (char c : str.toCharArray()) {
            if (Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算从 startPos 到 targetPos 之间的行数
     */
    private static int countLines(String content, int startPos, int targetPos) {
        int lineCount = 1;
        for (int i = startPos; i < targetPos && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lineCount++;
            }
        }
        return lineCount;
    }

    /**
     * 移除 SpEL 表达式 ${...}
     */
    private static String removeSpelExpressions(String text) {
        return text.replaceAll("\\$\\{[^}]*\\}", "");
    }

    /**
     * 移除 AS 别名中的双引号
     * 匹配模式：as "xxx" 或 AS "xxx"（不区分大小写）
     */
    private static String removeAsAliasQuotes(String line) {
        // 处理有 as 关键字的情况
        String result = line.replaceAll("(?i)\\bas\\s+\"([^\"]*)\"", "as $1");

        // 处理 ) "alias" 的情况（CASE WHEN ... END "alias"）
        result = result.replaceAll("\\)\\s+\"([^\"]*)\"", ") $1");

        // 处理 标识符 "alias" 的情况（column_name "alias"）
        // 但要避免匹配字符串常量，所以要求前面是标识符字符
        result = result.replaceAll("(?<=[a-zA-Z0-9_])\\s+\"([^\"]*)\"", " $1");

        return result;
    }

    /**
     * 正确查找标签结束位置（处理属性值中的 '>'）
     */
    private static int findTagEndProperly(String content, int startPos) {
        boolean inAttributeValue = false;
        char quoteChar = 0;

        for (int i = startPos + 1; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inAttributeValue) {
                // 在属性值内部
                if (c == quoteChar) {
                    // 遇到匹配的引号，退出属性值
                    inAttributeValue = false;
                    quoteChar = 0;
                }
            } else {
                // 不在属性值内部
                if (c == '"' || c == '\'') {
                    // 进入属性值
                    inAttributeValue = true;
                    quoteChar = c;
                } else if (c == '>') {
                    // 找到标签结束
                    return i;
                } else if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '>') {
                    // 自闭合标签 />
                    return i + 1;
                }
            }
        }

        return -1; // 没有找到标签结束
    }

    /**
     * 提取标签名
     */
    private static String extractTagName(String tag) {
        if (tag.startsWith("</")) {
            // 结束标签
            int end = tag.indexOf('>');
            if (end == -1) return null;
            return tag.substring(2, end).trim();
        } else if (tag.startsWith("<")) {
            // 开始标签
            int end = findTagEndProperly(tag, 0);
            if (end == -1) return null;

            String tagContent = tag.substring(1, end);
            // 去掉自闭合符号
            if (tagContent.endsWith("/")) {
                tagContent = tagContent.substring(0, tagContent.length() - 1);
            }

            // 提取标签名（第一个空格前的部分）
            int spacePos = tagContent.indexOf(' ');
            if (spacePos == -1) {
                return tagContent.trim();
            } else {
                return tagContent.substring(0, spacePos).trim();
            }
        }
        return null;
    }

    /**
     * 判断是否是空元素（不需要结束标签）
     */
    private static boolean isVoidElement(String tagName) {
        // MyBatis 中没有真正的空元素，所有标签都需要闭合
        return false;
    }

    /**
     * 查找结束标签的位置
     */
    private static int findClosingTag(String content, int startPos, String closingTag) {
        return content.indexOf(closingTag, startPos);
    }
}
