package com.jcl.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据集（DSN）节点信息
 * 作为 vis-network 图中的 DSN 节点使用。
 */
public class DsnInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 节点唯一 ID */
    private String id;

    /** 数据集物理名（DSN） */
    private String name;

    /** 逻辑名（首次引用该 DSN 的 DD 名） */
    private String logicalName;

    /** 数据集类型：NORMAL / REFERENCE / TEMPORARY / SYSOUT */
    private String type;

    /** DCB 参数串 */
    private String dcb;

    /** LRECL */
    private String lrecl;

    /** RECFM */
    private String recfm;

    /** BLKSIZE */
    private String blksize;

    /** 写入该 DSN 的 STEP 序号列表 */
    private List<Integer> writerSteps = new ArrayList<>();

    /** 读取该 DSN 的 STEP 序号列表 */
    private List<Integer> readerSteps = new ArrayList<>();

    public DsnInfo() {
    }

    public DsnInfo(String id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
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

    public String getLogicalName() {
        return logicalName;
    }

    public void setLogicalName(String logicalName) {
        this.logicalName = logicalName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDcb() {
        return dcb;
    }

    public void setDcb(String dcb) {
        this.dcb = dcb;
    }

    public String getLrecl() {
        return lrecl;
    }

    public void setLrecl(String lrecl) {
        this.lrecl = lrecl;
    }

    public String getRecfm() {
        return recfm;
    }

    public void setRecfm(String recfm) {
        this.recfm = recfm;
    }

    public String getBlksize() {
        return blksize;
    }

    public void setBlksize(String blksize) {
        this.blksize = blksize;
    }

    public List<Integer> getWriterSteps() {
        return writerSteps;
    }

    public void setWriterSteps(List<Integer> writerSteps) {
        this.writerSteps = writerSteps;
    }

    public List<Integer> getReaderSteps() {
        return readerSteps;
    }

    public void setReaderSteps(List<Integer> readerSteps) {
        this.readerSteps = readerSteps;
    }
}
