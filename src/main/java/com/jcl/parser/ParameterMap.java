package com.jcl.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JCL 参数串的键值解析器。
 * <p>
 * 按“顶层逗号”切分，忽略括号和引号内的逗号；键统一转大写。
 */
public final class ParameterMap {

    private final LinkedHashMap<String, String> values;

    private ParameterMap(LinkedHashMap<String, String> values) {
        this.values = values;
    }

    public static ParameterMap parse(String params) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (params == null || params.isBlank()) {
            return new ParameterMap(map);
        }
        for (String token : splitTopLevel(params)) {
            String t = token.strip();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0) {
                String key = t.substring(0, eq).strip().toUpperCase(Locale.ROOT);
                String value = unquote(t.substring(eq + 1).strip());
                map.put(key, value);
            } else {
                map.put(t.toUpperCase(Locale.ROOT), t);
            }
        }
        return new ParameterMap(map);
    }

    public String get(String key) {
        return values.get(key.toUpperCase(Locale.ROOT));
    }

    public boolean has(String key) {
        return values.containsKey(key.toUpperCase(Locale.ROOT));
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * 按顶层逗号切分，忽略括号与引号内的逗号。
     */
    public static List<String> splitTopLevel(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int parenDepth = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (inQuote && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    cur.append("''");
                    i++;
                    continue;
                }
                inQuote = !inQuote;
                cur.append(c);
            } else if (inQuote) {
                cur.append(c);
            } else if (c == '(') {
                parenDepth++;
                cur.append(c);
            } else if (c == ')') {
                parenDepth--;
                cur.append(c);
            } else if (c == ',' && parenDepth == 0) {
                result.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            result.add(cur.toString());
        }
        return result;
    }

    /**
     * 去掉一层最外层括号。
     */
    public static String stripParens(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("(") && t.endsWith(")") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * 去掉值外层的一层单/双引号。
     */
    public static String unquote(String v) {
        if (v == null) {
            return "";
        }
        String t = v.strip();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }
}
