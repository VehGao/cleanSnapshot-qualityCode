package io.github.veh.maven.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查 MyBatis XML 文件格式的工具类
 * 验证 XML 标签是否正确闭合、语法是否符合规范
 */
public class CheckXmlFormatUtil {

    /**
     * 检查 XML 文件的基本格式
     *
     * @param content  XML 文件内容
     * @param fileName 文件名
     * @param getLog   Maven 日志对象
     * @return 错误信息列表
     */
    protected static List<String> checkXmlFormat(String content, String fileName, org.apache.maven.plugin.logging.Log getLog) {
        List<String> errors = new ArrayList<>();

        // 1. 检查 XML 声明
        checkXmlDeclaration(content, fileName, errors);

        // 2. 检查标签嵌套是否正确（包含闭合检查）
        checkTagNesting(content, fileName, errors);

        // 3. 检查属性值是否缺少引号
        checkAttributeQuotes(content, fileName, errors);

        // 输出错误信息
        if (!errors.isEmpty()) {
            for (String error : errors) {
                getLog.warn(error);
            }
        }

        return errors;
    }

    /**
     * 检查 XML 声明是否存在
     */
    private static void checkXmlDeclaration(String content, String fileName, List<String> errors) {
        String trimmedContent = content.trim();
        // 检测并处理 BOM (Byte Order Mark) 字符
        if (trimmedContent.startsWith("\uFEFF")) {
            errors.add(fileName + "(第1行) - 检测到 UTF-8-BOM 编码，建议将文件改为 UTF-8 (无BOM)，以避免潜在的解析问题");
            trimmedContent = trimmedContent.substring(1).trim();
        }
        if (!trimmedContent.startsWith("<?xml")) {
            errors.add(fileName + "(第1行) - 缺少 XML 声明，建议添加: <?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        }
    }

    /**
     * 检查标签嵌套是否正确
     */
    private static void checkTagNesting(String content, String fileName, List<String> errors) {
        // 预处理：将跨行标签合并为单行，便于解析
        String processedContent = preprocessMultiLineTags(content);
        String[] lines = processedContent.split("\n");

        Stack<TagInfo> tagStack = new Stack<>();
        boolean inComment = false;
        boolean inCData = false;
        boolean mapperTagFound = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;

            // 处理跨行的注释和 CDATA 状态
            if (inComment) {
                if (line.contains("-->")) {
                    inComment = false;
                    // 移除注释部分，继续处理剩余内容
                    int commentEnd = line.indexOf("-->") + 3;
                    if (commentEnd < line.length()) {
                        line = line.substring(commentEnd);
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            if (inCData) {
                if (line.contains("]]>")) {
                    inCData = false;
                    // 移除 CDATA 部分，继续处理剩余内容
                    int cdataEnd = line.indexOf("]]>") + 3;
                    if (cdataEnd < line.length()) {
                        line = line.substring(cdataEnd);
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            // 检查当前行是否包含注释开始
            if (line.contains("<!--")) {
                int commentStart = line.indexOf("<!--");
                int commentEnd = line.indexOf("-->", commentStart);
                if (commentEnd == -1) {
                    // 注释跨行
                    inComment = true;
                    // 只处理注释前的部分
                    if (commentStart > 0) {
                        line = line.substring(0, commentStart);
                    } else {
                        continue;
                    }
                } else {
                    // 同一行内注释，移除注释部分
                    String beforeComment = line.substring(0, commentStart);
                    String afterComment = line.substring(commentEnd + 3);
                    line = beforeComment + afterComment;
                }
            }

            // 检查当前行是否包含 CDATA 开始
            if (line.contains("<![CDATA[")) {
                int cdataStart = line.indexOf("<![CDATA[");
                int cdataEnd = line.indexOf("]]>", cdataStart);
                if (cdataEnd == -1) {
                    // CDATA 跨行
                    inCData = true;
                    // 只处理 CDATA 前的部分
                    if (cdataStart > 0) {
                        line = line.substring(0, cdataStart);
                    } else {
                        continue;
                    }
                } else {
                    // 同一行内 CDATA，移除 CDATA 部分
                    String beforeCdata = line.substring(0, cdataStart);
                    String afterCdata = line.substring(cdataEnd + 3);
                    line = beforeCdata + afterCdata;
                }
            }

            // 如果处理后行为空，跳过
            if (line.trim().isEmpty()) {
                continue;
            }

            // 检查是否在这一行遇到了 mapper 开始标签
            if (!mapperTagFound && line.trim().startsWith("<mapper") && !line.contains("</mapper>")) {
                mapperTagFound = true;
                continue; // 跳过 mapper 标签本身
            }

            // 检查是否在这一行遇到了 mapper 结束标签
            if (mapperTagFound && line.trim().equals("</mapper>")) {
                mapperTagFound = false;
                continue; // 跳过 mapper 结束标签
            }

            // 如果还没遇到 mapper 标签或已经遇到 mapper 结束标签，跳过其他标签
            if (!mapperTagFound) {
                continue;
            }

            // 逐字符解析标签，避免正则表达式的贪婪匹配问题
            parseLineForTags(line, lineNumber, fileName, tagStack, errors);
        }

        // 报告未闭合的标签
        while (!tagStack.isEmpty()) {
            TagInfo unclosedTag = tagStack.pop();
            errors.add(fileName + "#" + unclosedTag.tagName + "(第" + unclosedTag.lineNumber + "行) - 标签未闭合，缺少对应的结束标签 </" + unclosedTag.tagName + ">");
        }
    }

    /**
     * 预处理：将跨行标签合并为单行
     * 例如：<association attr1="value1"
     *                   attr2="value2">content</association>
     * 合并为：<association attr1="value1" attr2="value2">content</association>
     */
    private static String preprocessMultiLineTags(String content) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");

        boolean inTag = false;
        boolean inComment = false;
        boolean inCData = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 处理注释状态
            if (inComment) {
                result.append("\n").append(line);
                if (line.contains("-->")) {
                    inComment = false;
                }
                continue;
            }
            if (trimmedLine.contains("<!--") && !trimmedLine.contains("-->")) {
                inComment = true;
                result.append("\n").append(line);
                continue;
            }

            // 处理 CDATA 状态
            if (inCData) {
                result.append("\n").append(line);
                if (line.contains("]]>")) {
                    inCData = false;
                }
                continue;
            }
            if (trimmedLine.contains("<![CDATA[") && !trimmedLine.contains("]]>")) {
                inCData = true;
                result.append("\n").append(line);
                continue;
            }

            // 检查是否在标签内部
            if (inTag) {
                // 如果这一行包含 '>'，说明标签结束
                if (trimmedLine.contains(">")) {
                    result.append(" ").append(trimmedLine);
                    inTag = false;
                } else {
                    // 否则继续追加到当前行
                    result.append(" ").append(trimmedLine);
                }
            } else {
                // 检查是否开始了一个新标签（但未在同一行结束）
                if (trimmedLine.startsWith("<") && !trimmedLine.startsWith("</")
                        && !trimmedLine.contains(">") && !trimmedLine.startsWith("<!--")
                        && !trimmedLine.startsWith("<!")) {
                    inTag = true;
                    result.append("\n").append(trimmedLine);
                } else {
                    result.append("\n").append(line);
                }
            }
        }

        return result.toString();
    }



    /**
     * 逐行解析标签
     */
    private static void parseLineForTags(String line, int lineNumber, String fileName,
                                         Stack<TagInfo> tagStack, List<String> errors) {
        int pos = 0;
        int length = line.length();

        while (pos < length) {
            // 查找下一个 '<'
            int ltPos = line.indexOf('<', pos);
            if (ltPos == -1) {
                break;
            }

            // 查找对应的 '>'
            int gtPos = line.indexOf('>', ltPos);
            if (gtPos == -1) {
                break;
            }

            // 提取标签内容
            String tagContent = line.substring(ltPos, gtPos + 1);

            // 跳过注释和特殊标签
            if (tagContent.startsWith("<!--") || tagContent.startsWith("<!")) {
                pos = gtPos + 1;
                continue;
            }

            // 判断标签类型
            boolean isEndTag = tagContent.startsWith("</");
            boolean isSelfClosing = tagContent.trim().endsWith("/>");

            // 提取标签名
            String tagName = extractTagName(tagContent);
            if (tagName == null || tagName.isEmpty()) {
                pos = gtPos + 1;
                continue;
            }

            if (isEndTag) {
                // 结束标签
                if (tagStack.isEmpty()) {
                    // 栈为空时遇到结束标签，跳过不报错（可能是误匹配）
                    pos = gtPos + 1;
                    continue;
                } else {
                    TagInfo lastTag = tagStack.peek();
                    if (!lastTag.tagName.equals(tagName)) {
                        errors.add(fileName + "#" + tagName + "(第" + lineNumber + "行) - 标签嵌套错误，期望关闭 </" +
                                lastTag.tagName + "> (开始于第" + lastTag.lineNumber + "行)，但找到 </" + tagName + ">");
                    } else {
                        tagStack.pop();
                    }
                }
            } else if (!isSelfClosing) {
                // 开始标签（非自闭合），压入栈
                tagStack.push(new TagInfo(tagName, lineNumber));
            }
            // 自闭合标签不需要处理

            pos = gtPos + 1;
        }
    }

    /**
     * 从标签内容中提取标签名
     */
    private static String extractTagName(String tagContent) {
        if (tagContent.startsWith("</")) {
            // 结束标签: </tagName>
            int end = tagContent.indexOf('>');
            if (end == -1) return null;
            String name = tagContent.substring(2, end).trim();
            // 确保返回的标签名不包含 '>' 或其他特殊字符
            return name.replaceAll("[>\\s]", "").trim();
        } else if (tagContent.startsWith("<")) {
            // 开始标签: <tagName ...> 或 <tagName .../>
            String content = tagContent.substring(1);
            // 去掉末尾的 '>' 或 '/>'
            if (content.endsWith("/>")) {
                content = content.substring(0, content.length() - 2);
            } else if (content.endsWith(">")) {
                content = content.substring(0, content.length() - 1);
            }
            // 提取标签名（第一个空格、制表符或换行符前的部分）
            int spacePos = -1;
            for (int j = 0; j < content.length(); j++) {
                char c = content.charAt(j);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    spacePos = j;
                    break;
                }
            }
            if (spacePos == -1) {
                return content.trim();
            } else {
                return content.substring(0, spacePos).trim();
            }
        }
        return null;
    }

    /**
     * 标签信息内部类
     */
    private static class TagInfo {
        String tagName;
        int lineNumber;

        TagInfo(String tagName, int lineNumber) {
            this.tagName = tagName;
            this.lineNumber = lineNumber;
        }
    }

    /**
     * 检查属性值是否缺少引号
     * 例如：<sql id=dpst_where_if> 应该为 <sql id="dpst_where_if">
     * 只检查 XML 标签的属性，不检查标签内部的 SQL 内容
     */
    private static void checkAttributeQuotes(String content, String fileName, List<String> errors) {
        String[] lines = content.split("\n");
        boolean inComment = false;
        boolean inCData = false;

        // 匹配 XML 开始标签中的属性（在 < 和 > 之间）
        // 先提取标签部分，再检查属性
        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile(
                "<([a-zA-Z][a-zA-Z0-9_-]*)(\\s[^>]*)?>"
        );

        // 匹配属性名=值（值没有引号）的模式
        java.util.regex.Pattern unquotedAttrPattern = java.util.regex.Pattern.compile(
                "\\s([a-zA-Z_][a-zA-Z0-9_-]*)=([^\"'\\s>][^\\s>]*)"
        );

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;

            // 处理 CDATA
            if (inCData) {
                if (line.contains("]]>")) {
                    inCData = false;
                }
                continue;
            }
            if (line.contains("<![CDATA[")) {
                inCData = true;
                continue;
            }

            // 处理注释
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
                    // 同一行内有完整的注释，移除注释后再检查
                    line = line.replaceAll("<!--.*?-->", "");
                }
            }

            // 只在包含标签的行中检查
            if (!line.contains("<")) {
                continue;
            }

            // 提取所有 XML 标签
            java.util.regex.Matcher tagMatcher = tagPattern.matcher(line);
            while (tagMatcher.find()) {
                String fullTag = tagMatcher.group(0);  // 完整的标签，如 <select id="test" resultType="...">
                String tagName = tagMatcher.group(1);   // 标签名，如 select
                String attributes = tagMatcher.group(2); // 属性部分，如 id="test" resultType="..."

                if (attributes == null || attributes.trim().isEmpty()) {
                    continue;
                }

                // 在属性部分检查未加引号的属性
                java.util.regex.Matcher attrMatcher = unquotedAttrPattern.matcher(attributes);
                while (attrMatcher.find()) {
                    String attrName = attrMatcher.group(1);
                    String attrValue = attrMatcher.group(2);

                    // 排除一些特殊情况
                    if (attrValue.contains("=") || attrValue.contains("<") || attrValue.contains(">")
                            || attrValue.contains("#") || attrValue.contains("$")) {
                        continue;
                    }

                    errors.add(fileName + "(第" + lineNumber + "行) -"+ "当前为: " +
                            attrName + "=" + attrValue + "，建议改为: " + attrName + "=\"" + attrValue + "\"");
                }
            }
        }
    }

}
