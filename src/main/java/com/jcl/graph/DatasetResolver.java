package com.jcl.graph;

import com.jcl.model.DdKind;
import com.jcl.model.DdStatement;
import com.jcl.model.DsnInfo;
import com.jcl.model.StepInfo;
import com.jcl.parser.Diagnostics;
import com.jcl.parser.JobDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 DD 语句解析为 DSN 节点，并处理：
 * <ul>
 *   <li>普通/临时/GDG 数据集的去重与读写方向；</li>
 *   <li>{@code DSN=*.STEP.DD} 引用解析；</li>
 *   <li>非数据流 DD（SYSOUT/DUMMY/INSTREAM/LIBRARY）过滤。</li>
 * </ul>
 */
@Component
public class DatasetResolver {

    public Map<String, DsnInfo> resolve(JobDraft job, Diagnostics diagnostics) {
        Map<String, DsnInfo> dsnMap = new LinkedHashMap<>();
        Map<String, StepInfo> stepByName = new LinkedHashMap<>();
        Map<String, Map<String, DdStatement>> ddByStep = new LinkedHashMap<>();

        for (StepInfo step : job.getSteps()) {
            String stepKey = key(step.getName());
            if (!stepKey.isEmpty()) {
                if (stepByName.containsKey(stepKey)) {
                    diagnostics.warning(step.getLine(), "STEP 名称重复：" + step.getName(),
                            "引用解析会使用后出现的 STEP。");
                }
                stepByName.put(stepKey, step);
            }
            Map<String, DdStatement> dds = new LinkedHashMap<>();
            for (DdStatement dd : step.getDdStatements()) {
                String ddKey = key(dd.getDdName());
                if (!ddKey.isEmpty()) {
                    dds.put(ddKey, dd);
                }
            }
            ddByStep.put(stepKey, dds);
        }

        Set<String> tempSeen = new HashSet<>();

        // 第一遍：非引用类数据集
        for (StepInfo step : job.getSteps()) {
            for (DdStatement dd : step.getDdStatements()) {
                if (!isNodeKind(dd.getKind()) || dd.getKind() == DdKind.REFERENCE) {
                    continue;
                }
                String datasetKey = datasetKey(dd);
                if (datasetKey == null) {
                    continue;
                }
                DsnInfo info = dsnMap.get(datasetKey);
                if (info == null) {
                    info = createDsn(datasetKey, dd, dsnMap.size() + 1);
                    dsnMap.put(datasetKey, info);
                }
                merge(info, dd);

                String io = resolveIo(dd, tempSeen);
                dd.setIoType(io);
                dd.setDsnId(info.getId());
                if ("O".equals(io)) {
                    info.getWriterSteps().add(step.getOrder());
                } else {
                    info.getReaderSteps().add(step.getOrder());
                }
            }
        }

        Map<String, DsnInfo> byId = new HashMap<>();
        for (DsnInfo info : dsnMap.values()) {
            byId.put(info.getId(), info);
        }

        // 第二遍：DSN=*.STEP.DD 引用
        for (StepInfo step : job.getSteps()) {
            for (DdStatement dd : step.getDdStatements()) {
                if (dd.getKind() != DdKind.REFERENCE) {
                    continue;
                }
                DsnInfo target = findReference(dd, step, stepByName, ddByStep, byId, new HashSet<>());
                if (target == null) {
                    diagnostics.warning(dd.getLine(), "无法解析数据集引用：" + dd.getDsn(),
                            "请确认被引用的 STEP/DD 存在，且目标 DD 是普通/临时/GDG 数据集。");
                    continue;
                }
                dd.setIoType("I");
                dd.setDsnId(target.getId());
                target.getReaderSteps().add(step.getOrder());
            }
        }

        for (DsnInfo info : dsnMap.values()) {
            info.setWriterSteps(dedupe(info.getWriterSteps()));
            info.setReaderSteps(dedupe(info.getReaderSteps()));
        }
        return dsnMap;
    }

    private DsnInfo createDsn(String datasetKey, DdStatement dd, int index) {
        String name;
        String type;
        if (dd.getKind() == DdKind.GDG) {
            name = dd.getBaseDsn();
            type = "GDG";
        } else if (dd.getKind() == DdKind.TEMPORARY) {
            name = dd.getDsn();
            type = "TEMPORARY";
        } else if (dd.getKind() == DdKind.NULLFILE) {
            name = "NULLFILE";
            type = "NULLFILE";
        } else {
            name = dd.getDsn();
            type = "NORMAL";
        }
        DsnInfo info = new DsnInfo("dsn_" + index, name, type);
        info.setLogicalName(dd.getDdName());
        applyDcb(info, dd);
        return info;
    }

    private void merge(DsnInfo info, DdStatement dd) {
        if (info.getLogicalName() == null && dd.getDdName() != null && !dd.getDdName().isBlank()) {
            info.setLogicalName(dd.getDdName());
        }
        applyDcb(info, dd);
    }

    private void applyDcb(DsnInfo info, DdStatement dd) {
        if (info.getDcb() == null && dd.getDcb() != null) {
            info.setDcb(dd.getDcb());
        }
        if (info.getLrecl() == null && dd.getLrecl() != null) {
            info.setLrecl(dd.getLrecl());
        }
        if (info.getRecfm() == null && dd.getRecfm() != null) {
            info.setRecfm(dd.getRecfm());
        }
        if (info.getBlksize() == null && dd.getBlksize() != null) {
            info.setBlksize(dd.getBlksize());
        }
    }

    private String resolveIo(DdStatement dd, Set<String> tempSeen) {
        if (dd.getKind() == DdKind.TEMPORARY) {
            String datasetKey = datasetKey(dd);
            boolean first = tempSeen.add(datasetKey);
            String status = dd.getDispStatus();
            if (status != null) {
                if ("SHR".equals(status) || "OLD".equals(status)) {
                    return "I";
                }
                if ("NEW".equals(status) || "MOD".equals(status)) {
                    return "O";
                }
            }
            return first ? "O" : "I";
        }
        return dd.getIoType() == null ? "O" : dd.getIoType();
    }

    private DsnInfo findReference(DdStatement reference, StepInfo currentStep,
                                  Map<String, StepInfo> stepByName,
                                  Map<String, Map<String, DdStatement>> ddByStep,
                                  Map<String, DsnInfo> byId,
                                  Set<String> visited) {
        String targetStepName = reference.getReferenceStep();
        String targetDdName = reference.getReferenceDd();
        if (targetDdName == null || targetDdName.isBlank()) {
            return null;
        }

        StepInfo targetStep = (targetStepName == null || targetStepName.isBlank())
                ? currentStep
                : stepByName.get(key(targetStepName));
        if (targetStep == null) {
            return null;
        }

        String visitKey = key(targetStep.getName()) + "." + key(targetDdName);
        if (!visited.add(visitKey)) {
            return null;
        }

        Map<String, DdStatement> dds = ddByStep.get(key(targetStep.getName()));
        if (dds == null) {
            return null;
        }
        DdStatement target = dds.get(key(targetDdName));
        if (target == null) {
            return null;
        }

        if (target.getKind() == DdKind.REFERENCE) {
            return findReference(target, targetStep, stepByName, ddByStep, byId, visited);
        }
        if (target.getDsnId() == null) {
            return null;
        }
        return byId.get(target.getDsnId());
    }

    private boolean isNodeKind(DdKind kind) {
        return kind == DdKind.DATA || kind == DdKind.TEMPORARY
                || kind == DdKind.GDG || kind == DdKind.NULLFILE;
    }

    private String datasetKey(DdStatement dd) {
        if (dd.getKind() == DdKind.GDG && dd.getBaseDsn() != null) {
            return "GDG:" + key(dd.getBaseDsn());
        }
        if (dd.getDsn() == null || dd.getDsn().isBlank()) {
            return null;
        }
        return "DSN:" + key(dd.getDsn());
    }

    private static List<Integer> dedupe(List<Integer> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static String key(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
