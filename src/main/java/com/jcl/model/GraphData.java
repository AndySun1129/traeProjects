package com.jcl.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 图数据统一返回结构
 * 前端直接基于此结构渲染节点与连线。
 */
public class GraphData implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JOB 信息 */
    private JobInfo jobInfo;

    /** STEP 列表 */
    private List<StepInfo> stepList = new ArrayList<>();

    /** DSN 列表 */
    private List<DsnInfo> dsnList = new ArrayList<>();

    /** 连线列表 */
    private List<GraphEdge> edges = new ArrayList<>();

    public JobInfo getJobInfo() {
        return jobInfo;
    }

    public void setJobInfo(JobInfo jobInfo) {
        this.jobInfo = jobInfo;
    }

    public List<StepInfo> getStepList() {
        return stepList;
    }

    public void setStepList(List<StepInfo> stepList) {
        this.stepList = stepList;
    }

    public List<DsnInfo> getDsnList() {
        return dsnList;
    }

    public void setDsnList(List<DsnInfo> dsnList) {
        this.dsnList = dsnList;
    }

    public List<GraphEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<GraphEdge> edges) {
        this.edges = edges;
    }
}
