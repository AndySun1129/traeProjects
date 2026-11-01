package com.jcl.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果统一响应结构。
 * <p>
 * 支持一次输入多个 JOB：{@link #jobs} 中每个元素是一张独立的图。
 * 可恢复的问题放入 {@link #warnings}，致命问题放入 {@link #errors}。
 */
public class ParseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否解析成功 */
    private boolean success;

    /** 提示消息 */
    private String message;

    /** 每个 JOB 一张图 */
    private List<GraphData> jobs = new ArrayList<>();

    /** 致命错误 */
    private List<ParseError> errors = new ArrayList<>();

    /** 可恢复的警告 */
    private List<ParseError> warnings = new ArrayList<>();

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<GraphData> getJobs() {
        return jobs;
    }

    public void setJobs(List<GraphData> jobs) {
        this.jobs = jobs;
    }

    public List<ParseError> getErrors() {
        return errors;
    }

    public void setErrors(List<ParseError> errors) {
        this.errors = errors;
    }

    public List<ParseError> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<ParseError> warnings) {
        this.warnings = warnings;
    }
}
