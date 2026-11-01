package com.jcl.model;

import java.io.Serializable;

/**
 * JCL JOB 卡片信息
 */
public class JobInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JOB 名称 */
    private String name;

    /** JOB 语句原始参数串（如 CLASS=A,MSGCLASS=X,...） */
    private String parameters;

    /** 作业账号信息 */
    private String account;

    /** 程序员信息 */
    private String programmer;

    /** JOB CLASS（CLASS=） */
    private String jobClass;

    /** 消息类（MSGCLASS=） */
    private String msgClass;

    /** JOB 语句所在行号 */
    private int line;

    public JobInfo() {
    }

    public JobInfo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getProgrammer() {
        return programmer;
    }

    public void setProgrammer(String programmer) {
        this.programmer = programmer;
    }

    public String getJobClass() {
        return jobClass;
    }

    public void setJobClass(String jobClass) {
        this.jobClass = jobClass;
    }

    public String getMsgClass() {
        return msgClass;
    }

    public void setMsgClass(String msgClass) {
        this.msgClass = msgClass;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }
}
