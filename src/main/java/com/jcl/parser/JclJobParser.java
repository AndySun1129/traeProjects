package com.jcl.parser;

import com.jcl.model.DdStatement;
import com.jcl.model.JobInfo;
import com.jcl.model.StepInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把逻辑语句解析为 JOB / STEP / DD 结构。
 * <p>
 * 采用两遍扫描：
 * <ol>
 *   <li>第一遍收集内嵌 {@code PROC ... PEND} 定义；</li>
 *   <li>第二遍按 JOB 分段解析 EXEC/DD，遇到 {@code EXEC PROC=} 时展开过程体并做符号替换。</li>
 * </ol>
 * 一个输入中可以有多个 JOB，每个 JOB 生成一个独立的 {@link JobDraft}。
 */
@Component
public class JclJobParser {

    private static final int MAX_PROC_DEPTH = 20;

    private final JclStatementReader reader;

    public JclJobParser(JclStatementReader reader) {
        this.reader = reader;
    }

    public List<JobDraft> parse(List<LogicalStatement> statements, Diagnostics diagnostics) {
        ProcTable procs = collectProcedures(statements, diagnostics);
        ParseState state = new ParseState(procs, diagnostics);
        List<JobDraft> jobs = new ArrayList<>();
        JobDraft current = null;

        for (int i = 0; i < statements.size(); i++) {
            if (procs.isDefinitionStart(i)) {
                i = procs.endIndex(i);
                continue;
            }

            LogicalStatement stmt = statements.get(i);
            String op = upper(stmt.getOperation());

            if ("JOB".equals(op)) {
                current = new JobDraft(parseJobCard(stmt));
                jobs.add(current);
                continue;
            }

            if (current == null) {
                if ("SET".equals(op)) {
                    handleSet(stmt, state.globalSymbols, diagnostics);
                    continue;
                }
                if (!("EXEC".equals(op) || "DD".equals(op))) {
                    continue;
                }
                current = new JobDraft(new JobInfo("UNKNOWN_JOB"));
                current.getJobInfo().setLine(stmt.getStartLine());
                diagnostics.warning(stmt.getStartLine(), "缺少 JOB 语句，已按 UNKNOWN_JOB 处理",
                        "请在 JCL 开头添加 //jobname JOB ...");
                jobs.add(current);
            }

            switch (op) {
                case "EXEC" -> handleExec(current, stmt, state,
                        new HashMap<>(state.globalSymbols), new ArrayDeque<>());
                case "DD" -> handleDd(current, stmt, state, state.globalSymbols);
                case "SET" -> handleSet(stmt, state.globalSymbols, diagnostics);
                case "IF" -> diagnostics.warning(stmt.getStartLine(),
                        "IF/THEN/ELSE 条件暂不参与图建模",
                        "条件分支中的 STEP 仍会按顺序展示，请结合源码确认执行路径。");
                case "INCLUDE" -> diagnostics.warning(stmt.getStartLine(),
                        "INCLUDE 暂不支持展开",
                        "请先把被包含的 JCL 合并到输入中。");
                default -> {
                    // OUTPUT / COMMAND / XMIT 等暂不参与数据流
                }
            }
        }

        for (JobDraft job : jobs) {
            if (job.getSteps().isEmpty()) {
                diagnostics.warning(job.getJobInfo().getLine(),
                        "JOB " + job.getJobInfo().getName() + " 没有可解析的 STEP",
                        "请确认 EXEC 语句是否正确。");
            }
        }
        return jobs;
    }

    // ===================== PROC 收集 =====================

    private ProcTable collectProcedures(List<LogicalStatement> statements, Diagnostics diagnostics) {
        ProcTable table = new ProcTable();
        for (int i = 0; i < statements.size(); i++) {
            LogicalStatement stmt = statements.get(i);
            if (!"PROC".equalsIgnoreCase(stmt.getOperation()) || stmt.getName().isBlank()) {
                continue;
            }

            int end = i + 1;
            while (end < statements.size() && !statements.get(end).isPend()) {
                end++;
            }
            if (end >= statements.size()) {
                diagnostics.warning(stmt.getStartLine(), "PROC 定义缺少 PEND",
                        "请以 // PEND 结束过程定义。");
                end = statements.size();
            }

            List<LogicalStatement> body = new ArrayList<>(statements.subList(i + 1, end));
            Map<String, String> defaults = ParameterMap.parse(stmt.getParameters()).asMap();
            table.add(i, end, new ProcDefinition(stmt.getName(), defaults, body, stmt.getStartLine()));
            i = end;
        }
        return table;
    }

    // ===================== EXEC / DD / SET =====================

    private void handleExec(JobDraft job, LogicalStatement stmt, ParseState state,
                            Map<String, String> scope, Deque<String> stack) {
        LogicalStatement resolved = substituteStatement(stmt, scope);
        ParameterMap params = ParameterMap.parse(resolved.getParameters());

        String procName = params.get("PROC");
        if (procName == null) {
            StepInfo step = newStep(job, resolved);
            step.setExecType("PGM");
            step.setPgm(params.get("PGM"));
            step.setParm(params.get("PARM"));
            if (isBlank(step.getPgm())) {
                state.diagnostics.warning(resolved.getStartLine(), "EXEC 未指定 PGM 或 PROC",
                        "请补充 PGM=程序名 或 PROC=过程名。");
            }
            return;
        }

        ProcDefinition definition = state.procs.get(procName);
        if (definition == null) {
            StepInfo step = newStep(job, resolved);
            step.setExecType("PROC");
            step.setProc(procName);
            step.setProcName(procName);
            step.setParm(params.get("PARM"));
            state.diagnostics.warning(resolved.getStartLine(),
                    "未找到 PROC " + procName + " 的定义，无法展开",
                    "请补充该 PROC 的内嵌定义；当前会保留一个占位 STEP。");
            return;
        }

        if (stack.size() >= MAX_PROC_DEPTH || stack.contains(definition.nameKey())) {
            state.diagnostics.error(resolved.getStartLine(),
                    "PROC 递归调用或嵌套过深：" + procName,
                    "请检查 PROC 之间是否存在循环调用。");
            return;
        }

        Map<String, String> vars = new HashMap<>(scope);
        vars.putAll(definition.getDefaults());
        for (String key : params.keys()) {
            if (!"PROC".equals(key)) {
                vars.put(key, params.get(key));
            }
        }

        stack.push(definition.nameKey());
        int before = job.getSteps().size();
        StepInfo saved = job.getCurrentStep();
        job.setCurrentStep(null);
        try {
            for (LogicalStatement bodyStmt : definition.getBody()) {
                String bodyText = SymbolTable.substitute(bodyStmt.getRawText(), vars);
                LogicalStatement s = reader.read(bodyText, bodyStmt.getStartLine(), bodyStmt.getEndLine());
                if (s == null) {
                    continue;
                }
                String op = upper(s.getOperation());
                if ("EXEC".equals(op)) {
                    handleExec(job, s, state, vars, stack);
                } else if ("DD".equals(op)) {
                    handleDd(job, s, state, vars);
                } else if ("SET".equals(op)) {
                    handleSet(s, vars, state.diagnostics);
                }
            }
        } finally {
            stack.pop();
            job.setCurrentStep(saved);
        }

        List<StepInfo> expanded = new ArrayList<>(
                job.getSteps().subList(before, job.getSteps().size()));
        if (expanded.isEmpty()) {
            state.diagnostics.warning(resolved.getStartLine(),
                    "PROC " + procName + " 未展开出任何 STEP",
                    "请检查过程体是否包含 EXEC 语句。");
            return;
        }
        for (StepInfo step : expanded) {
            if (step.getProcName() == null) {
                step.setProcName(definition.getName());
            }
        }
        // JCL 中写在 EXEC PROC= 之后的 DD 是对过程第一步的覆盖
        job.setCurrentStep(expanded.get(0));
    }

    private void handleDd(JobDraft job, LogicalStatement stmt, ParseState state,
                          Map<String, String> scope) {
        LogicalStatement resolved = substituteStatement(stmt, scope);
        DdStatement dd = DdParser.parse(resolved, state.diagnostics);

        StepInfo step = job.getCurrentStep();
        if (step == null) {
            if (dd.getKind() == com.jcl.model.DdKind.LIBRARY) {
                return;
            }
            state.diagnostics.warning(resolved.getStartLine(),
                    "DD 出现在第一个 EXEC 之前，已忽略",
                    "JOBLIB/STEPLIB 可以放在 JOB 卡之后；普通 DD 必须属于某个 STEP。");
            return;
        }

        if (!isBlank(dd.getDdName())) {
            step.getDdStatements().removeIf(existing -> existing.getDdName() != null
                    && existing.getDdName().equalsIgnoreCase(dd.getDdName()));
        }
        step.getDdStatements().add(dd);
    }

    private void handleSet(LogicalStatement stmt, Map<String, String> symbols, Diagnostics diagnostics) {
        LogicalStatement resolved = substituteStatement(stmt, symbols);
        ParameterMap params = ParameterMap.parse(resolved.getParameters());
        for (String key : params.keys()) {
            symbols.put(key, params.get(key));
        }
        if (params.keys().isEmpty()) {
            diagnostics.warning(resolved.getStartLine(), "SET 语句没有可用的符号定义",
                    "应写成 // SET 名称=值。");
        }
    }

    private StepInfo newStep(JobDraft job, LogicalStatement stmt) {
        int order = job.nextOrder();
        StepInfo step = new StepInfo("step_" + order, stmt.getName(), order);
        step.setLine(stmt.getStartLine());
        step.setExecParameters(stmt.getParameters());
        job.getSteps().add(step);
        job.setCurrentStep(step);
        return step;
    }

    // ===================== JOB 卡 =====================

    private JobInfo parseJobCard(LogicalStatement stmt) {
        String name = stmt.getName().isBlank() ? "UNKNOWN_JOB" : stmt.getName();
        JobInfo job = new JobInfo(name);
        job.setParameters(stmt.getParameters());
        job.setLine(stmt.getStartLine());

        String p = stmt.getParameters();
        if (p != null && !p.isBlank()) {
            parseJobPositionals(p, job);
            ParameterMap jobParams = ParameterMap.parse(p);
            job.setJobClass(jobParams.get("CLASS"));
            job.setMsgClass(jobParams.get("MSGCLASS"));
        }
        return job;
    }

    /**
     * 解析 JOB 的位置参数：账户信息、程序员名。
     * <p>
     * 支持：
     * <ul>
     *   <li>{@code //MYJOB1 JOB 168,NEWMAN.LV}</li>
     *   <li>{@code //MYJOB1 JOB 268,'NEWMAN LV'}</li>
     *   <li>{@code //MYJOB2 JOB ,'NEWMAN LV'}（省略账户）</li>
     *   <li>{@code //MYJOB JOB (ACCT),'PGMR',CLASS=A}</li>
     * </ul>
     */
    private void parseJobPositionals(String params, JobInfo job) {
        List<String> tokens = ParameterMap.splitTopLevel(params);
        if (tokens.isEmpty()) {
            return;
        }

        int index = 0;
        String first = tokens.get(0).strip();
        if (first.isEmpty()) {
            // 账户信息被省略，程序员名从第二个位置开始
            index = 1;
        } else if (first.indexOf('=') < 0) {
            job.setAccount(ParameterMap.unquote(ParameterMap.stripParens(first)));
            index = 1;
        } else {
            // 第一个 token 已经是 CLASS= 之类的关键字参数，没有位置参数
            return;
        }

        if (index < tokens.size()) {
            String second = tokens.get(index).strip();
            if (!second.isEmpty() && second.indexOf('=') < 0) {
                job.setProgrammer(ParameterMap.unquote(second));
            }
        }
    }

    private LogicalStatement substituteStatement(LogicalStatement stmt, Map<String, String> scope) {
        if (stmt.getRawText().indexOf('&') < 0 || scope == null || scope.isEmpty()) {
            return stmt;
        }
        String text = SymbolTable.substitute(stmt.getRawText(), scope);
        LogicalStatement resolved = reader.read(text, stmt.getStartLine(), stmt.getEndLine());
        return resolved == null ? stmt : resolved;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ===================== 内部结构 =====================

    private static final class ParseState {
        private final ProcTable procs;
        private final Diagnostics diagnostics;
        private final Map<String, String> globalSymbols = new HashMap<>();

        private ParseState(ProcTable procs, Diagnostics diagnostics) {
            this.procs = procs;
            this.diagnostics = diagnostics;
        }
    }

    private static final class ProcTable {
        private final Map<String, ProcDefinition> byName = new HashMap<>();
        private final Map<Integer, Integer> ranges = new HashMap<>();

        private void add(int start, int end, ProcDefinition definition) {
            byName.put(definition.nameKey(), definition);
            ranges.put(start, end);
        }

        private boolean isDefinitionStart(int index) {
            return ranges.containsKey(index);
        }

        private int endIndex(int index) {
            return ranges.getOrDefault(index, index);
        }

        private ProcDefinition get(String name) {
            return name == null ? null : byName.get(name.toUpperCase(Locale.ROOT));
        }
    }
}
