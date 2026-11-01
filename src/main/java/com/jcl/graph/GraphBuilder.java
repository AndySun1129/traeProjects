package com.jcl.graph;

import com.jcl.model.DdStatement;
import com.jcl.model.DsnInfo;
import com.jcl.model.GraphData;
import com.jcl.model.GraphEdge;
import com.jcl.model.StepInfo;
import com.jcl.parser.JobDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图数据构建器：后端是 STEP/DSN 边的唯一数据源。
 * <p>
 * 前端直接渲染 {@link GraphData#getEdges()}。
 * {@code STEP} 类型边表示执行顺序，{@code FLOW} 类型边表示数据集读写，label 为 DD 名。
 */
@Component
public class GraphBuilder {

    public GraphData build(JobDraft job, Map<String, DsnInfo> dsnMap) {
        GraphData graph = new GraphData();
        graph.setJobInfo(job.getJobInfo());
        graph.setStepList(new ArrayList<>(job.getSteps()));
        graph.setDsnList(new ArrayList<>(dsnMap.values()));

        List<GraphEdge> edges = new ArrayList<>();
        int edgeIndex = 0;

        List<StepInfo> steps = job.getSteps();
        for (int i = 0; i < steps.size() - 1; i++) {
            StepInfo from = steps.get(i);
            StepInfo to = steps.get(i + 1);
            edges.add(new GraphEdge("e_step_" + (++edgeIndex), from.getId(), to.getId(), "STEP"));
        }

        for (StepInfo step : steps) {
            for (DdStatement dd : step.getDdStatements()) {
                if (dd.getDsnId() == null || dd.getIoType() == null) {
                    continue;
                }
                GraphEdge edge = new GraphEdge();
                edge.setId("e_flow_" + (++edgeIndex));
                edge.setType("FLOW");
                edge.setIoType(dd.getIoType());
                edge.setLabel(dd.getDdName());
                if ("O".equals(dd.getIoType())) {
                    edge.setFrom(step.getId());
                    edge.setTo(dd.getDsnId());
                } else {
                    edge.setFrom(dd.getDsnId());
                    edge.setTo(step.getId());
                }
                edges.add(edge);
            }
        }

        graph.setEdges(edges);
        return graph;
    }
}
