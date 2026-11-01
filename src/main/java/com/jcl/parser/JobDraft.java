package com.jcl.parser;

import com.jcl.model.JobInfo;
import com.jcl.model.StepInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 JOB 的解析中间态：JOB 信息 + 已收集的 STEP 列表。
 */
public class JobDraft {

    private final JobInfo jobInfo;
    private final List<StepInfo> steps = new ArrayList<>();
    private StepInfo currentStep;
    private int order;

    public JobDraft(JobInfo jobInfo) {
        this.jobInfo = jobInfo;
    }

    public JobInfo getJobInfo() {
        return jobInfo;
    }

    public List<StepInfo> getSteps() {
        return steps;
    }

    public StepInfo getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(StepInfo currentStep) {
        this.currentStep = currentStep;
    }

    public int nextOrder() {
        return ++order;
    }
}
