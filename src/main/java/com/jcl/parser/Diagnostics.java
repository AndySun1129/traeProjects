package com.jcl.parser;

import com.jcl.model.ParseError;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次解析过程的诊断收集器。
 * <p>
 * 错误（ERROR）表示语句无法继续解析；警告（WARNING）表示仍可生成图、但结果需要人工确认。
 */
public class Diagnostics {

    private final List<ParseError> errors = new ArrayList<>();
    private final List<ParseError> warnings = new ArrayList<>();

    public void error(int line, String message, String suggestion) {
        errors.add(new ParseError(line, message, suggestion));
    }

    public void warning(int line, String message, String suggestion) {
        warnings.add(new ParseError(line, message, suggestion));
    }

    public List<ParseError> getErrors() {
        return errors;
    }

    public List<ParseError> getWarnings() {
        return warnings;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
