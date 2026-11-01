package com.jcl.model;

import java.io.Serializable;

/**
 * 图的连线（边）模型
 * 表示 STEP 与 DSN 之间的读写关系，或 STEP 之间的主流程关系。
 */
public class GraphEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 连线唯一 ID */
    private String id;

    /** 起始节点 ID */
    private String from;

    /** 目标节点 ID */
    private String to;

    /** 连线类型：
     *  - FLOW：数据流（STEP→DSN 写 或 DSN→STEP 读）
     *  - STEP：STEP 主执行流程（STEP→STEP）
     */
    private String type;

    /** 连线标签（如 DD 名） */
    private String label;

    /** 读写方向语义：I / O */
    private String ioType;

    public GraphEdge() {
    }

    public GraphEdge(String id, String from, String to, String type) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getIoType() {
        return ioType;
    }

    public void setIoType(String ioType) {
        this.ioType = ioType;
    }
}
