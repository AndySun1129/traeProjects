package com.jcl.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * JCL STEP（作业步）信息。
 * <p>
 * 读写关系不再在 STEP 上重复维护，统一由 {@link GraphEdge} 表达（单一数据源）。
 */
public class StepInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 节点唯一 ID，如 step_1 */
    private String id;

    /** STEP 名称（JCL 中可能为空，例如无名 EXEC） */
    private String name;

    /** 执行序号（从 1 开始） */
    private int order;

    /** 执行类型：PGM / PROC */
    private String execType;

    /** 执行的程序名（PGM=xxx） */
    private String pgm;

    /** 调用的过程名（PROC=xxx） */
    private String proc;

    /** 通过 PROC 展开产生的 STEP，记录来源过程名 */
    private String procName;

    /** EXEC 语句原始参数串 */
    private String execParameters;

    /** PARM= 参数值 */
    private String parm;

    /** EXEC 语句所在行号 */
    private int line;

    /** 该 STEP 下所有 DD 语句 */
    private List<DdStatement> ddStatements = new ArrayList<>();

    public StepInfo() {
    }

    public StepInfo(String id, String name, int order) {
        this.id = id;
        this.name = name;
        this.order = order;
    }

    /**
     * 图中展示用名称：STEP 名为空时依次回退到 PGM、PROC，最后用序号。
     */
    public String getDisplayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (pgm != null && !pgm.isBlank()) {
            return pgm;
        }
        if (proc != null && !proc.isBlank()) {
            return proc;
        }
        return "STEP " + order;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getExecType() {
        return execType;
    }

    public void setExecType(String execType) {
        this.execType = execType;
    }

    public String getPgm() {
        return pgm;
    }

    public void setPgm(String pgm) {
        this.pgm = pgm;
    }

    public String getProc() {
        return proc;
    }

    public void setProc(String proc) {
        this.proc = proc;
    }

    public String getProcName() {
        return procName;
    }

    public void setProcName(String procName) {
        this.procName = procName;
    }

    public String getExecParameters() {
        return execParameters;
    }

    public void setExecParameters(String execParameters) {
        this.execParameters = execParameters;
    }

    public String getParm() {
        return parm;
    }

    public void setParm(String parm) {
        this.parm = parm;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public List<DdStatement> getDdStatements() {
        return ddStatements;
    }

    public void setDdStatements(List<DdStatement> ddStatements) {
        this.ddStatements = ddStatements;
    }
}
