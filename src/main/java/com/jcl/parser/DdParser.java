package com.jcl.parser;

import com.jcl.model.DdKind;
import com.jcl.model.DdStatement;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单条 DD 语句的字段解析器。
 * <p>
 * 只做“语法层”解析：识别 DSN 类型、DISP、DCB、SYSOUT、引用等，
 * 读写方向与 DSN 去重由 {@link com.jcl.graph.DatasetResolver} 负责。
 */
public final class DdParser {

    private static final Pattern GDG_PATTERN = Pattern.compile("^(.+)\\(([+-]?\\d+)\\)$");

    private DdParser() {
    }

    public static DdStatement parse(LogicalStatement stmt, Diagnostics diagnostics) {
        String rawParams = stmt.getParameters() == null ? "" : stmt.getParameters().strip();

        DdStatement dd = new DdStatement();
        dd.setDdName(stmt.getName());
        dd.setRawText(stmt.getRawText());
        dd.setParameters(rawParams);
        dd.setLine(stmt.getStartLine());
        dd.setInstreamData(stmt.getInstreamData());

        // DD * / DD DATA / DD DATA,DLM=xx
        if (isInstream(rawParams)) {
            dd.setKind(DdKind.INSTREAM);
            dd.setInstream(true);
            dd.setDsn("INSTREAM");
            dd.setIoType("I");
            return dd;
        }

        ParameterMap params = ParameterMap.parse(rawParams);

        // SYSOUT
        if (params.has("SYSOUT")) {
            dd.setKind(DdKind.SYSOUT);
            dd.setSysout(true);
            dd.setDsn("SYSOUT(" + (isBlank(dd.getDdName()) ? "OUTPUT" : dd.getDdName()) + ")");
            dd.setIoType("O");
            return dd;
        }

        // DUMMY
        if (params.has("DUMMY") || "DUMMY".equalsIgnoreCase(rawParams)) {
            dd.setKind(DdKind.DUMMY);
            dd.setDsn("DUMMY");
            return dd;
        }

        String dsn = params.get("DSN");
        if (dsn == null) {
            dsn = params.get("DSNAME");
        }
        if (dsn != null) {
            dsn = ParameterMap.unquote(dsn);
            if (dsn.isBlank()) {
                dsn = null;
            }
        }

        // NULLFILE：保留为特殊节点；有 DISP 时按 DISP，否则按 DD 名约定判断方向
        if (dsn != null && "NULLFILE".equalsIgnoreCase(dsn)) {
            dd.setKind(DdKind.NULLFILE);
            dd.setDsn("NULLFILE");
            parseDisp(params, dd);
            dd.setIoType(determineIo(dd.getDispStatus(), dd.getDdName()));
            return dd;
        }

        // JOBLIB / STEPLIB：库，不参与数据流
        if ("JOBLIB".equalsIgnoreCase(dd.getDdName()) || "STEPLIB".equalsIgnoreCase(dd.getDdName())) {
            dd.setKind(DdKind.LIBRARY);
            dd.setDsn(dsn);
            parseDisp(params, dd);
            parseDcb(params, dd);
            return dd;
        }

        // 无 DSN、无 SYSOUT：隐式临时数据集
        if (isBlank(dsn)) {
            String tempName = "&&" + (isBlank(dd.getDdName()) ? "TEMP" : dd.getDdName());
            dd.setDsn(tempName);
            dd.setKind(DdKind.TEMPORARY);
            dd.setTemporary(true);
            parseDisp(params, dd);
            parseDcb(params, dd);
            dd.setIoType(ioForTemporary(dd));
            return dd;
        }

        dd.setDsn(dsn);

        // DSN=*.STEP.DD 引用
        if (dsn.startsWith("*.")) {
            dd.setKind(DdKind.REFERENCE);
            parseReference(dsn, dd, diagnostics, stmt.getStartLine());
            parseDisp(params, dd);
            parseDcb(params, dd);
            dd.setIoType("I");
            return dd;
        }

        // GDG / 临时集 / 普通数据集
        Matcher gdg = GDG_PATTERN.matcher(dsn);
        if (gdg.matches()) {
            dd.setKind(DdKind.GDG);
            dd.setBaseDsn(gdg.group(1));
            dd.setGeneration(gdg.group(2));
        } else if (dsn.startsWith("&&")) {
            dd.setKind(DdKind.TEMPORARY);
            dd.setTemporary(true);
        } else {
            dd.setKind(DdKind.DATA);
        }

        parseDisp(params, dd);
        parseDcb(params, dd);

        if (dd.getKind() == DdKind.TEMPORARY) {
            dd.setIoType(ioForTemporary(dd));
        } else {
            dd.setIoType(determineIo(dd.getDispStatus(), dd.getDdName()));
        }
        return dd;
    }

    private static boolean isInstream(String rawParams) {
        String p = rawParams.strip();
        if (p.equals("*")) {
            return true;
        }
        String upper = p.toUpperCase(Locale.ROOT);
        return upper.equals("DATA") || upper.startsWith("DATA,");
    }

    private static void parseReference(String dsn, DdStatement dd, Diagnostics diagnostics, int line) {
        String ref = dsn.substring(2);
        int dot = ref.indexOf('.');
        String stepName;
        String ddName;
        if (dot > 0) {
            stepName = ref.substring(0, dot);
            ddName = ref.substring(dot + 1);
        } else {
            stepName = "";
            ddName = ref;
        }
        dd.setReferenceStep(stepName);
        dd.setReferenceDd(ddName);
        if (ddName.isBlank()) {
            diagnostics.warning(line, "数据集引用格式不完整：" + dsn,
                    "应写成 DSN=*.STEP名.DD名。");
        }
    }

    private static void parseDisp(ParameterMap params, DdStatement dd) {
        String disp = params.get("DISP");
        if (disp == null) {
            return;
        }
        String d = ParameterMap.stripParens(disp);
        String[] parts = d.split(",", -1);
        if (parts.length >= 1 && !parts[0].isBlank()) {
            dd.setDispStatus(parts[0].strip().toUpperCase(Locale.ROOT));
        }
        if (parts.length >= 2) {
            dd.setDispNormal(parts[1].strip());
        }
        if (parts.length >= 3) {
            dd.setDispAbend(parts[2].strip());
        }
    }

    /**
     * 解析 DCB：既支持顶层 LRECL=/RECFM=/BLKSIZE=，也支持 DCB=(LRECL=...,RECFM=...)。
     */
    private static void parseDcb(ParameterMap params, DdStatement dd) {
        String dcb = params.get("DCB");
        if (dcb != null) {
            dd.setDcb(dcb);
            String inner = ParameterMap.stripParens(dcb);
            ParameterMap dcbParams = ParameterMap.parse(inner);
            if (dcbParams.get("LRECL") != null) {
                dd.setLrecl(dcbParams.get("LRECL"));
            }
            if (dcbParams.get("RECFM") != null) {
                dd.setRecfm(dcbParams.get("RECFM"));
            }
            if (dcbParams.get("BLKSIZE") != null) {
                dd.setBlksize(dcbParams.get("BLKSIZE"));
            }
        }
        if (params.get("LRECL") != null) {
            dd.setLrecl(params.get("LRECL"));
        }
        if (params.get("RECFM") != null) {
            dd.setRecfm(params.get("RECFM"));
        }
        if (params.get("BLKSIZE") != null) {
            dd.setBlksize(params.get("BLKSIZE"));
        }
    }

    /**
     * 读写方向判定。
     * <p>
     * 优先级：
     * <ol>
     *   <li>显式 DISP：SHR/OLD → 读，NEW/MOD → 写；</li>
     *   <li>DISP 缺省/空状态时，按 DD 名约定：
     *       SYSOUT/OUT/W 开头 → 写；SYSIN/IN/R 开头 → 读；</li>
     *   <li>都没有命中时按 JCL 默认 NEW 处理，即写。</li>
     * </ol>
     */
    private static String determineIo(String status, String ddName) {
        if (status != null && !status.isBlank()) {
            return switch (status.toUpperCase(Locale.ROOT)) {
                case "SHR", "OLD" -> "I";
                case "NEW", "MOD" -> "O";
                default -> "I";
            };
        }

        String name = ddName == null ? "" : ddName.toUpperCase(Locale.ROOT);
        if (name.contains("SYSOUT")) {
            return "O";
        }
        if (name.contains("SYSIN")) {
            return "I";
        }
        if (name.startsWith("R") || name.startsWith("IN")) {
            return "I";
        }
        if (name.startsWith("W") || name.startsWith("OUT")) {
            return "O";
        }
        return "O";
    }

    private static String ioForTemporary(DdStatement dd) {
        return determineIo(dd.getDispStatus(), dd.getDdName());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
