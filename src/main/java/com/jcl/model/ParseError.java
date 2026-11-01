package com.jcl.model;

import java.io.Serializable;

/**
 * 解析诊断信息（错误或警告），定位到具体行号。
 * <p>
 * JSON 字段为 {@code line}，与前端 {@code e.line} 保持一致。
 */
public class ParseError implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 原始行号（从 1 开始） */
    private int line;

    /** 错误消息 */
    private String message;

    /** 修正建议 */
    private String suggestion;

    public ParseError() {
    }

    public ParseError(int line, String message, String suggestion) {
        this.line = line;
        this.message = message;
        this.suggestion = suggestion;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }
}
