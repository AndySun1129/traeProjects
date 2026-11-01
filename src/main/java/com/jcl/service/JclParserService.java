package com.jcl.service;

import com.jcl.graph.DatasetResolver;
import com.jcl.graph.GraphBuilder;
import com.jcl.model.DsnInfo;
import com.jcl.model.GraphData;
import com.jcl.model.ParseError;
import com.jcl.model.ParseResult;
import com.jcl.parser.Diagnostics;
import com.jcl.parser.JclJobParser;
import com.jcl.parser.JclPreprocessor;
import com.jcl.parser.JobDraft;
import com.jcl.parser.LogicalStatement;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * JCL 解析编排服务。
 * <p>
 * 负责把“预处理 → 语句解析 → 数据集解析 → 图构建”串起来；具体规则在各自的协作类中。
 */
@Service
public class JclParserService {

    private final JclPreprocessor preprocessor;
    private final JclJobParser jobParser;
    private final DatasetResolver datasetResolver;
    private final GraphBuilder graphBuilder;

    public JclParserService(JclPreprocessor preprocessor,
                            JclJobParser jobParser,
                            DatasetResolver datasetResolver,
                            GraphBuilder graphBuilder) {
        this.preprocessor = preprocessor;
        this.jobParser = jobParser;
        this.datasetResolver = datasetResolver;
        this.graphBuilder = graphBuilder;
    }

    public ParseResult parse(String jclSource) {
        ParseResult result = new ParseResult();
        Diagnostics diagnostics = new Diagnostics();

        if (jclSource == null || jclSource.isBlank()) {
            result.setSuccess(false);
            result.setMessage("JCL 源码为空，请输入有效的 JCL 内容。");
            result.getErrors().add(new ParseError(0, "JCL 源码为空",
                    "请粘贴或上传至少一条 JCL 语句。"));
            return result;
        }

        List<LogicalStatement> statements = preprocessor.read(jclSource, diagnostics);
        if (statements.isEmpty()) {
            if (diagnostics.getErrors().isEmpty()) {
                diagnostics.error(0, "未识别到任何 JCL 语句", "请确认语句以 // 开头，注释为 //*。");
            }
            result.setSuccess(false);
            result.setMessage("未识别到任何 JCL 语句。请确认语句以 // 开头。");
            copyDiagnostics(result, diagnostics);
            return result;
        }

        List<JobDraft> jobs = jobParser.parse(statements, diagnostics);
        if (jobs.isEmpty()) {
            if (diagnostics.getErrors().isEmpty()) {
                diagnostics.error(0, "未识别到可解析的 JOB 或 STEP", "请确认 JOB/EXEC 语句是否完整。");
            }
            result.setSuccess(false);
            result.setMessage("未识别到可解析的 JOB 或 STEP。");
            copyDiagnostics(result, diagnostics);
            return result;
        }

        int stepCount = 0;
        int dsnCount = 0;
        for (JobDraft job : jobs) {
            Map<String, DsnInfo> dsnMap = datasetResolver.resolve(job, diagnostics);
            GraphData graph = graphBuilder.build(job, dsnMap);
            result.getJobs().add(graph);
            stepCount += graph.getStepList().size();
            dsnCount += graph.getDsnList().size();
        }

        result.setSuccess(true);
        result.setMessage(String.format("解析完成：共 %d 个 JOB，%d 个 STEP，%d 个 DSN。",
                jobs.size(), stepCount, dsnCount));
        copyDiagnostics(result, diagnostics);
        return result;
    }

    private static void copyDiagnostics(ParseResult result, Diagnostics diagnostics) {
        result.setErrors(diagnostics.getErrors());
        result.setWarnings(diagnostics.getWarnings());
    }
}
