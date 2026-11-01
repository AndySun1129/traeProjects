package com.jcl.model;

import java.io.Serializable;

/**
 * JCL DD 语句模型。
 * <p>
 * 只描述“语句本身”的信息；解析后的 DSN 节点 ID 由 DatasetResolver 回填到 {@link #dsnId}。
 * 非数据流 DD（SYSOUT / DUMMY / INSTREAM / LIBRARY）通过 {@link #kind} 区分，不生成图节点。
 */
public class DdStatement implements Serializable {

    private static final long serialVersionUID = 1L;

    /** DD 名（如 SYSUT1、SYSPRINT） */
    private String ddName;

    /** 数据集物理名（DSN= 后的值） */
    private String dsn;

    /** GDG 的 base 名（去掉 (+1)/(0) 之后） */
    private String baseDsn;

    /** GDG 的代际，如 +1、0 */
    private String generation;

    /** DD 语句原始文本 */
    private String rawText;

    /** DD 参数串（不含 DD 名） */
    private String parameters;

    /** 语句类型 */
    private DdKind kind;

    /** 解析后对应的 DSN 节点 ID */
    private String dsnId;

    /** DISP 状态：NEW / OLD / SHR / MOD / CATLG / UNCATLG / DELETE 等 */
    private String dispStatus;

    /** 正常结束处置 */
    private String dispNormal;

    /** 异常结束处置 */
    private String dispAbend;

    /** 读写方向：I=读，O=写 */
    private String ioType;

    /** DCB 参数串，如 (LRECL=80,RECFM=FB,BLKSIZE=32760) */
    private String dcb;

    /** 顶层或 DCB 内的 LRECL */
    private String lrecl;

    /** 顶层或 DCB 内的 RECFM */
    private String recfm;

    /** 顶层或 DCB 内的 BLKSIZE */
    private String blksize;

    /** 是否为临时数据集（无 DSN，使用 && 前缀） */
    private boolean temporary;

    /** 是否为 SYSOUT 输出 */
    private boolean sysout;

    /** 是否为流内数据（DD * 或 DD DATA） */
    private boolean instream;

    /** 流内数据内容（DD * / DD DATA 后面的数据行） */
    private String instreamData;

    /** 引用目标 STEP 名（DSN=*.STEP.DD） */
    private String referenceStep;

    /** 引用目标 DD 名（DSN=*.STEP.DD） */
    private String referenceDd;

    /** 语句所在行号 */
    private int line;

    public String getDdName() {
        return ddName;
    }

    public void setDdName(String ddName) {
        this.ddName = ddName;
    }

    public String getDsn() {
        return dsn;
    }

    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    public String getBaseDsn() {
        return baseDsn;
    }

    public void setBaseDsn(String baseDsn) {
        this.baseDsn = baseDsn;
    }

    public String getGeneration() {
        return generation;
    }

    public void setGeneration(String generation) {
        this.generation = generation;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public DdKind getKind() {
        return kind;
    }

    public void setKind(DdKind kind) {
        this.kind = kind;
    }

    public String getDsnId() {
        return dsnId;
    }

    public void setDsnId(String dsnId) {
        this.dsnId = dsnId;
    }

    public String getDispStatus() {
        return dispStatus;
    }

    public void setDispStatus(String dispStatus) {
        this.dispStatus = dispStatus;
    }

    public String getDispNormal() {
        return dispNormal;
    }

    public void setDispNormal(String dispNormal) {
        this.dispNormal = dispNormal;
    }

    public String getDispAbend() {
        return dispAbend;
    }

    public void setDispAbend(String dispAbend) {
        this.dispAbend = dispAbend;
    }

    public String getIoType() {
        return ioType;
    }

    public void setIoType(String ioType) {
        this.ioType = ioType;
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

    public boolean isTemporary() {
        return temporary;
    }

    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }

    public boolean isSysout() {
        return sysout;
    }

    public void setSysout(boolean sysout) {
        this.sysout = sysout;
    }

    public boolean isInstream() {
        return instream;
    }

    public void setInstream(boolean instream) {
        this.instream = instream;
    }

    public String getInstreamData() {
        return instreamData;
    }

    public void setInstreamData(String instreamData) {
        this.instreamData = instreamData;
    }

    public String getReferenceStep() {
        return referenceStep;
    }

    public void setReferenceStep(String referenceStep) {
        this.referenceStep = referenceStep;
    }

    public String getReferenceDd() {
        return referenceDd;
    }

    public void setReferenceDd(String referenceDd) {
        this.referenceDd = referenceDd;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }
}
