package com.jcl.service;

import com.jcl.graph.DatasetResolver;
import com.jcl.graph.GraphBuilder;
import com.jcl.model.DdKind;
import com.jcl.model.DdStatement;
import com.jcl.model.DsnInfo;
import com.jcl.model.GraphData;
import com.jcl.model.GraphEdge;
import com.jcl.model.ParseResult;
import com.jcl.model.StepInfo;
import com.jcl.parser.JclJobParser;
import com.jcl.parser.JclPreprocessor;
import com.jcl.parser.JclStatementReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JclParserServiceTest {

    private JclParserService newService() {
        JclStatementReader reader = new JclStatementReader();
        return new JclParserService(
                new JclPreprocessor(reader),
                new JclJobParser(reader),
                new DatasetResolver(),
                new GraphBuilder()
        );
    }

    private static final String SAMPLE = """
            //SAMPLEJOB JOB (ACCOUNT),'DEMO',CLASS=A,MSGCLASS=H
            //* JCL 数据流可视化工具 - 示例作业
            //STEP01  EXEC PGM=IEBGENER
            //SYSPRINT DD SYSOUT=*
            //SYSUT1   DD DSN=MYDATA.PROD.INPUT.RAW,DISP=SHR
            //SYSUT2   DD DSN=MYDATA.PROD.CLEAN,
            //             DISP=(NEW,CATLG,DELETE),
            //             SPACE=(TRK,(5,2)),UNIT=SYSDA,
            //             DCB=(LRECL=80,RECFM=FB,BLKSIZE=800)
            //STEP02  EXEC PGM=SORT
            //SORTIN  DD DSN=MYDATA.PROD.CLEAN,DISP=SHR
            //SORTOUT DD DSN=MYDATA.PROD.SORTED,DISP=(NEW,CATLG),SPACE=(CYL,(2,1))
            //SYSIN   DD *
              SORT FIELDS=(1,10,A)
            /*
            //STEP03  EXEC PGM=VALIDATE
            //INPUT   DD DSN=MYDATA.PROD.SORTED,DISP=SHR
            //VALID   DD DSN=MYDATA.PROD.VALID,DISP=(NEW,CATLG),LRECL=100,RECFM=FB
            //BAD     DD SYSOUT=*
            //STEP04  EXEC PGM=ENRICH
            //INPUT   DD DSN=MYDATA.PROD.VALID,DISP=SHR
            //REF     DD DSN=MYDATA.REF.CUSTOMER.MASTER,DISP=SHR
            //OUTPUT  DD DSN=MYDATA.PROD.ENRICHED,DISP=(NEW,CATLG)
            //STEP05  EXEC PGM=FORMAT
            //INPUT   DD DSN=MYDATA.PROD.ENRICHED,DISP=SHR
            //REPORT  DD DSN=MYDATA.PROD.REPORT.FINAL,DISP=(NEW,CATLG),
            //             LRECL=133,RECFM=FA
            //STEP06  EXEC PGM=ARCHIVE
            //INPUT   DD DSN=MYDATA.PROD.ENRICHED,DISP=SHR
            //ARCHIVE DD DSN=MYDATA.ARCH.ENRICHED.DATA,DISP=(NEW,CATLG)
            //SYSOUT  DD SYSOUT=*
            """;

    @Test
    void sampleJclBuildsExpectedGraph() {
        ParseResult result = newService().parse(SAMPLE);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getJobs().size());

        GraphData graph = result.getJobs().get(0);
        assertEquals("SAMPLEJOB", graph.getJobInfo().getName());
        assertEquals(6, graph.getStepList().size());
        // SYSPRINT / BAD / SYSOUT 三个 SYSOUT 不再生成节点
        assertEquals(8, graph.getDsnList().size());
        assertTrue(graph.getDsnList().stream().noneMatch(d -> d.getName().startsWith("SYSOUT")));

        DsnInfo clean = dsn(graph, "MYDATA.PROD.CLEAN");
        assertNotNull(clean);
        assertEquals("80", clean.getLrecl());
        assertEquals("FB", clean.getRecfm());
        assertEquals("800", clean.getBlksize());
        assertEquals(List.of(1), clean.getWriterSteps());
        assertEquals(List.of(2), clean.getReaderSteps());

        // 5 条 STEP 顺序边 + 13 条数据流边
        assertEquals(5, graph.getEdges().stream().filter(e -> "STEP".equals(e.getType())).count());
        assertEquals(13, graph.getEdges().stream().filter(e -> "FLOW".equals(e.getType())).count());
    }

    @Test
    void continuationStatementIsNotTruncatedAtColumn72() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=IEBGENER
                //SYSUT2 DD DSN=A.B.C,
                //             DISP=(NEW,CATLG,DELETE),
                //             SPACE=(TRK,(5,2)),
                //             DCB=(LRECL=80,RECFM=FB,BLKSIZE=800)
                """;
        GraphData graph = single(newService().parse(jcl));

        DdStatement dd = graph.getStepList().get(0).getDdStatements().get(0);
        assertTrue(dd.getRawText().length() > 72, "续行拼接后的语句不应被截断到 72 字符");
        assertEquals("80", dd.getLrecl());
        assertEquals("FB", dd.getRecfm());
        assertEquals("800", dd.getBlksize());
        assertEquals("(LRECL=80,RECFM=FB,BLKSIZE=800)", dd.getDcb());
    }

    @Test
    void errorLineUsesLineField() {
        String jcl = """
                //J1 JOB (A),'P'
                NOT JCL
                //S1 EXEC PGM=X
                """;
        ParseResult result = newService().parse(jcl);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getErrors().size());
        assertEquals(2, result.getErrors().get(0).getLine());
    }

    @Test
    void missingDispAndEmptyStatusDefaultToWrite() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //OUT1 DD DSN=A.B.ONE
                //OUT2 DD DSN=A.B.TWO,DISP=(,CATLG)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(List.of(1), dsn(graph, "A.B.ONE").getWriterSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.TWO").getWriterSteps());
        assertTrue(dsn(graph, "A.B.ONE").getReaderSteps().isEmpty());
    }

    @Test
    void backwardReferencePointsToSameDatasetNode() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //OUT DD DSN=A.B.OUT,DISP=(NEW,CATLG)
                //S2 EXEC PGM=Y
                //IN  DD DSN=*.S1.OUT,DISP=SHR
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getDsnList().size());
        DsnInfo out = dsn(graph, "A.B.OUT");
        assertEquals(List.of(1), out.getWriterSteps());
        assertEquals(List.of(2), out.getReaderSteps());

        DdStatement in = graph.getStepList().get(1).getDdStatements().get(0);
        assertEquals(DdKind.REFERENCE, in.getKind());
        assertEquals(out.getId(), in.getDsnId());
    }

    @Test
    void trailingCommaDoesNotSwallowNextStatement() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //D1 DD DSN=A.B.ONE,
                //D2 DD DSN=A.B.TWO,DISP=SHR
                """;
        ParseResult result = newService().parse(jcl);
        GraphData graph = single(result);

        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).getMessage().contains("续行"));

        assertEquals(2, graph.getStepList().get(0).getDdStatements().size());
        assertEquals(List.of(1), dsn(graph, "A.B.ONE").getWriterSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.TWO").getReaderSteps());
    }

    @Test
    void procIsExpandedWithSymbolSubstitution() {
        String jcl = """
                //J1 JOB (A),'P'
                //MYPROC PROC,ACCT=PROD
                //PS1 EXEC PGM=X
                //IN  DD DSN=A.B.&ACCT,DISP=SHR
                // PEND
                //S1 EXEC PROC=MYPROC,ACCT=TEST
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getStepList().size());
        StepInfo step = graph.getStepList().get(0);
        assertEquals("PS1", step.getName());
        assertEquals("MYPROC", step.getProcName());
        assertEquals(List.of(1), dsn(graph, "A.B.TEST").getReaderSteps());
    }

    @Test
    void multipleJobsAreReturnedSeparately() {
        String jcl = """
                //JOB1 JOB (A1),'P1'
                //S1 EXEC PGM=X
                //D1 DD DSN=A.B.ONE,DISP=(NEW,CATLG)
                //JOB2 JOB (A2),'P2'
                //S2 EXEC PGM=Y
                //D2 DD DSN=A.B.TWO,DISP=(NEW,CATLG)
                """;
        ParseResult result = newService().parse(jcl);

        assertEquals(2, result.getJobs().size());
        assertEquals("JOB1", result.getJobs().get(0).getJobInfo().getName());
        assertEquals("JOB2", result.getJobs().get(1).getJobInfo().getName());
        assertEquals(1, result.getJobs().get(0).getStepList().size());
        assertEquals(1, result.getJobs().get(1).getStepList().size());
        assertEquals("A2", result.getJobs().get(1).getJobInfo().getAccount());
    }

    @Test
    void systemAndLibraryDdsDoNotCreateGraphNodes() {
        String jcl = """
                //J1 JOB (A),'P'
                //JOBLIB DD DSN=MY.LOADLIB,DISP=SHR
                //S1 EXEC PGM=X
                //STEPLIB DD DSN=MY.STEPLIB,DISP=SHR
                //SYSPRINT DD SYSOUT=*
                //DUMMY1 DD DUMMY
                //SYSIN DD *
                 some input
                /*
                //OUT DD DSN=A.B.OUT,DISP=(NEW,CATLG)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getDsnList().size());
        assertEquals("A.B.OUT", graph.getDsnList().get(0).getName());
        assertTrue(graph.getStepList().get(0).getDdStatements().stream()
                .anyMatch(d -> d.getKind() == DdKind.LIBRARY));
        assertTrue(graph.getStepList().get(0).getDdStatements().stream()
                .anyMatch(d -> d.getKind() == DdKind.SYSOUT));
        assertTrue(graph.getStepList().get(0).getDdStatements().stream()
                .anyMatch(d -> d.getKind() == DdKind.INSTREAM));
    }

    @Test
    void gdgGenerationsShareOneNode() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //OUT DD DSN=A.B.GDG(+1),DISP=(NEW,CATLG)
                //S2 EXEC PGM=Y
                //IN  DD DSN=A.B.GDG(0),DISP=SHR
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getDsnList().size());
        DsnInfo gdg = graph.getDsnList().get(0);
        assertEquals("A.B.GDG", gdg.getName());
        assertEquals("GDG", gdg.getType());
        assertEquals(List.of(1), gdg.getWriterSteps());
        assertEquals(List.of(2), gdg.getReaderSteps());
    }

    @Test
    void jobPositionalParametersSupportQuotedAndUnquotedProgrammer() {
        GraphData unquoted = single(newService().parse("""
                //MYJOB1 JOB 168,NEWMAN.LV
                //S1 EXEC PGM=X
                """));
        assertEquals("168", unquoted.getJobInfo().getAccount());
        assertEquals("NEWMAN.LV", unquoted.getJobInfo().getProgrammer());

        GraphData omittedAccount = single(newService().parse("""
                //MYJOB2 JOB ,'NEWMAN LV'
                //S1 EXEC PGM=X
                """));
        assertNull(omittedAccount.getJobInfo().getAccount());
        assertEquals("NEWMAN LV", omittedAccount.getJobInfo().getProgrammer());
    }

    @Test
    void unnamedExecFallsBackToProgramName() {
        String jcl = """
                //J1 JOB (A),'P'
                // EXEC PGM=IEFBR14
                """;
        GraphData graph = single(newService().parse(jcl));

        StepInfo step = graph.getStepList().get(0);
        assertEquals("", step.getName());
        assertEquals("IEFBR14", step.getDisplayName());
    }

    @Test
    void dcbWithDelimiterAndInstreamDataIsSkipped() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //SYSIN DD DATA,DLM=@@
                // this line must not be parsed as JCL
                @@
                //OUT DD DSN=A.B.OUT,DISP=(NEW,CATLG)
                """;
        ParseResult result = newService().parse(jcl);
        GraphData graph = single(result);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(1, graph.getDsnList().size());
        assertTrue(graph.getStepList().get(0).getDdStatements().stream()
                .anyMatch(d -> d.getKind() == DdKind.INSTREAM));
    }

    @Test
    void nullFileGetsSpecialNode() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //OUT  DD DSN=NULLFILE
                //S2 EXEC PGM=Y
                //OUT2 DD DSN=NULLFILE,DISP=(NEW,DELETE)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getDsnList().size());
        DsnInfo nullFile = graph.getDsnList().get(0);
        assertEquals("NULLFILE", nullFile.getName());
        assertEquals("NULLFILE", nullFile.getType());
        assertEquals(List.of(1, 2), nullFile.getWriterSteps());
        assertEquals(2, graph.getEdges().stream()
                .filter(e -> "FLOW".equals(e.getType()) && "O".equals(e.getIoType()))
                .count());
    }

    @Test
    void ddNameConventionsUsedWhenDispMissing() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //RIN DD DSN=A.B.RIN
                //WOUT DD DSN=A.B.WOUT
                //SYSIN1 DD DSN=A.B.SYSIN1
                //SYSOUT DD DSN=A.B.SYSOUT
                //INPUT DD DSN=NULLFILE
                //OUTPUT DD DSN=A.B.OUTPUT
                //FORCE DD DSN=A.B.FORCE,DISP=(NEW,CATLG)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(List.of(1), dsn(graph, "A.B.RIN").getReaderSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.WOUT").getWriterSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.SYSIN1").getReaderSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.SYSOUT").getWriterSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.OUTPUT").getWriterSteps());
        assertEquals(List.of(1), dsn(graph, "A.B.FORCE").getWriterSteps());

        DsnInfo nullFile = dsn(graph, "NULLFILE");
        assertEquals(List.of(1), nullFile.getReaderSteps());
        assertTrue(nullFile.getWriterSteps().isEmpty());
        assertTrue(graph.getEdges().stream().anyMatch(e ->
                "FLOW".equals(e.getType())
                        && "INPUT".equals(e.getLabel())
                        && nullFile.getId().equals(e.getFrom())));
    }

    @Test
    void instreamStarDataEndsAtNextJclStatementAndParmIsCaptured() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=ABC,PARM=('F1')
                //SYSIN DD *
                F1001
                //S2 EXEC PGM=DEF
                //OUT DD DSN=A.B.OUT,DISP=(NEW,CATLG)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(2, graph.getStepList().size());
        assertEquals("('F1')", graph.getStepList().get(0).getParm());
        DdStatement sysin = graph.getStepList().get(0).getDdStatements().stream()
                .filter(DdStatement::isInstream)
                .findFirst()
                .orElseThrow();
        assertEquals("F1001", sysin.getInstreamData());
    }

    @Test
    void ddDataDoesNotEndAtDoubleSlash() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //SYSIN DD DATA
                // THIS IS DATA
                /*
                //S2 EXEC PGM=Y
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(2, graph.getStepList().size());
        DdStatement sysin = graph.getStepList().get(0).getDdStatements().stream()
                .filter(DdStatement::isInstream)
                .findFirst()
                .orElseThrow();
        assertEquals("// THIS IS DATA", sysin.getInstreamData());
    }

    @Test
    void setSymbolsApplyButDoNotBreakTemporaryDatasets() {
        String jcl = """
                //J1 JOB (A),'P'
                // SET TEMP=OOPS
                //S1 EXEC PGM=X
                //T1 DD DSN=&&TEMP,DISP=(NEW,PASS)
                //T2 DD DSN=&&TEMP,DISP=(OLD,PASS)
                """;
        GraphData graph = single(newService().parse(jcl));

        assertEquals(1, graph.getDsnList().size());
        DsnInfo temp = graph.getDsnList().get(0);
        assertEquals("&&TEMP", temp.getName());
        assertEquals(List.of(1), temp.getWriterSteps());
        assertEquals(List.of(1), temp.getReaderSteps());
    }

    @Test
    void jobCardFieldsAreParsed() {
        GraphData graph = single(newService().parse("""
                //JOBX JOB (ACCT),'PGMR',CLASS=A,MSGCLASS=H
                //S1 EXEC PGM=X
                """));
        assertEquals("JOBX", graph.getJobInfo().getName());
        assertEquals("ACCT", graph.getJobInfo().getAccount());
        assertEquals("PGMR", graph.getJobInfo().getProgrammer());
        assertEquals("A", graph.getJobInfo().getJobClass());
        assertEquals("H", graph.getJobInfo().getMsgClass());
        assertEquals(1, graph.getJobInfo().getLine());
    }

    @Test
    void emptySourceFailsGracefully() {
        ParseResult result = newService().parse("   ");
        assertFalse(result.isSuccess());
        assertTrue(result.getJobs().isEmpty());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void flowEdgesCarryDdNameAndDirection() {
        String jcl = """
                //J1 JOB (A),'P'
                //S1 EXEC PGM=X
                //OUT DD DSN=A.B.OUT,DISP=(NEW,CATLG)
                //S2 EXEC PGM=Y
                //IN  DD DSN=A.B.OUT,DISP=SHR
                """;
        GraphData graph = single(newService().parse(jcl));

        GraphEdge write = graph.getEdges().stream()
                .filter(e -> "FLOW".equals(e.getType()) && "O".equals(e.getIoType()))
                .findFirst().orElseThrow();
        assertEquals("OUT", write.getLabel());
        assertEquals("step_1", write.getFrom());

        GraphEdge read = graph.getEdges().stream()
                .filter(e -> "FLOW".equals(e.getType()) && "I".equals(e.getIoType()))
                .findFirst().orElseThrow();
        assertEquals("IN", read.getLabel());
        assertEquals("step_2", read.getTo());
        assertEquals(graph.getDsnList().get(0).getId(), write.getTo());
    }

    private GraphData single(ParseResult result) {
        assertTrue(result.isSuccess(), () -> "parse failed: " + result.getMessage());
        assertEquals(1, result.getJobs().size());
        return result.getJobs().get(0);
    }

    private DsnInfo dsn(GraphData graph, String name) {
        return graph.getDsnList().stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DSN not found: " + name));
    }
}
