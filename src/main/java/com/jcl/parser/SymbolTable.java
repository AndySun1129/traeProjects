package com.jcl.parser;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JCL 符号替换（&NAME）。
 * <p>
 * 注意：{@code &&TEMP} 是临时数据集前缀，不参与符号替换，因此使用负向后顾 {@code (?<!&)}。
 */
final class SymbolTable {

    private static final Pattern SYMBOL =
            Pattern.compile("(?<!&)&([A-Za-z@#][A-Za-z0-9@#]*)\\.?");

    private SymbolTable() {
    }

    static String substitute(String text, Map<String, String> vars) {
        if (text == null || text.indexOf('&') < 0 || vars == null || vars.isEmpty()) {
            return text;
        }
        Matcher matcher = SYMBOL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase(Locale.ROOT);
            String value = vars.get(key);
            if (value == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
