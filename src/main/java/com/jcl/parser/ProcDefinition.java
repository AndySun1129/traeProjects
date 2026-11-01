package com.jcl.parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内嵌 PROC 定义。
 */
public class ProcDefinition {

    private final String name;
    private final Map<String, String> defaults;
    private final List<LogicalStatement> body;
    private final int line;

    public ProcDefinition(String name, Map<String, String> defaults,
                          List<LogicalStatement> body, int line) {
        this.name = name;
        this.defaults = defaults;
        this.body = body;
        this.line = line;
    }

    public String getName() {
        return name;
    }

    public String nameKey() {
        return name.toUpperCase(Locale.ROOT);
    }

    public Map<String, String> getDefaults() {
        return defaults;
    }

    public List<LogicalStatement> getBody() {
        return body;
    }

    public int getLine() {
        return line;
    }
}
