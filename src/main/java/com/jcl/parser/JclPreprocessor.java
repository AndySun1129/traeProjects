package com.jcl.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JCL 物理行预处理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>列规则：第 1-71 列为代码，第 72 列为续行标志，第 73-80 列忽略；</li>
 *   <li>跳过空行、{@code //*} 注释、{@code /*} 控制语句；</li>
 *   <li>处理 {@code DD *} / {@code DD DATA} / {@code DD DATA,DLM=xx} 的流内数据；</li>
 *   <li>按 JCL 规则拼接续行：续行必须以 {@code //} 开头且第 3-15 列为空。</li>
 * </ul>
 * 输出为完整的逻辑语句，不再做 72 列截断。
 */
@Component
public class JclPreprocessor {

    /** 代码列：第 1-71 列（0 基下标 0..70） */
    private static final int CODE_COLUMNS = 71;

    /** 续行标志列：第 72 列（0 基下标 71） */
    private static final int CONTINUATION_COLUMN = 71;

    /** 续行语句名称区结束列：第 15 列（0 基下标 14） */
    private static final int NAME_FIELD_END = 15;

    private static final Pattern DLM_PATTERN =
            Pattern.compile("(?i)(?:^|,)\\s*DLM=([^,\\s]+)");

    private final JclStatementReader reader;

    public JclPreprocessor(JclStatementReader reader) {
        this.reader = reader;
    }

    public List<LogicalStatement> read(String source, Diagnostics diagnostics) {
        List<LogicalStatement> statements = new ArrayList<>();
        String[] rawLines = source.split("\r?\n", -1);

        StringBuilder pending = new StringBuilder();
        int pendingStart = -1;
        InstreamState instream = new InstreamState();

        for (int i = 0; i < rawLines.length; i++) {
            String raw = rawLines[i];
            int lineNo = i + 1;

            // 流内数据：DD * 遇到新的 // 语句即结束；DD DATA 必须用分隔符结束
            if (instream.active) {
                if (raw.startsWith(instream.delimiter)) {
                    instream.finish();
                    continue;
                }
                if (instream.endsOnSlashSlash && raw.startsWith("//")) {
                    instream.finish();
                    // 当前行是新的 JCL 语句，继续往下解析
                } else {
                    instream.data.append(raw).append('\n');
                    continue;
                }
            }

            // 第 1-71 列为代码；第 72 列之后丢弃
            String code = raw.length() > CODE_COLUMNS ? raw.substring(0, CODE_COLUMNS) : raw;

            if (code.isBlank()) {
                continue;
            }
            if (code.startsWith("//*")) {
                continue;
            }
            if (code.startsWith("/*") && !code.startsWith("//")) {
                continue;
            }

            // 第 72 列非空 = 续行；行尾逗号作为兼容写法（后续仍会校验下一行）
            boolean continuationFlag = raw.length() > CONTINUATION_COLUMN
                    && raw.charAt(CONTINUATION_COLUMN) != ' '
                    && raw.charAt(CONTINUATION_COLUMN) != '\t';
            boolean trailingComma = code.stripTrailing().endsWith(",");
            boolean continues = continuationFlag || trailingComma;

            // 已有一条待续语句
            if (pending.length() > 0) {
                if (isContinuationLine(code)) {
                    pending.append(code.substring(2).stripLeading());
                    if (continues) {
                        continue;
                    }
                    LogicalStatement stmt = finish(pending.toString(), pendingStart, lineNo, statements);
                    if (stmt != null && isInstreamStart(stmt)) {
                        instream.start(stmt, instreamDelimiter(stmt), isStarInstream(stmt));
                    }
                    pending.setLength(0);
                    pendingStart = -1;
                    continue;
                }

                diagnostics.error(pendingStart,
                        "语句以续行标记或逗号结尾，但下一行不是合法的续行",
                        "续行必须以 // 开头，且第 3-15 列必须为空。");
                LogicalStatement stmt = finish(pending.toString(), pendingStart, lineNo - 1, statements);
                if (stmt != null && isInstreamStart(stmt)) {
                    instream.start(stmt, instreamDelimiter(stmt), isStarInstream(stmt));
                }
                pending.setLength(0);
                pendingStart = -1;
                // 继续处理当前行
            }

            if (!code.startsWith("//")) {
                diagnostics.error(lineNo, "语句未以 // 开头：" + code.strip(),
                        "JCL 语句必须以 // 开头（注释为 //*）。");
                continue;
            }

            if (continues) {
                pending.append(code);
                pendingStart = lineNo;
            } else {
                LogicalStatement stmt = finish(code, lineNo, lineNo, statements);
                if (stmt != null && isInstreamStart(stmt)) {
                    instream.start(stmt, instreamDelimiter(stmt), isStarInstream(stmt));
                }
            }
        }

        if (pending.length() > 0) {
            diagnostics.warning(pendingStart, "语句在文件末尾仍未结束",
                    "如果使用了续行，请补齐后续参数。");
            finish(pending.toString(), pendingStart, rawLines.length, statements);
        }
        if (instream.active) {
            instream.finish();
            diagnostics.warning(rawLines.length, "DD 流内数据缺少结束标记",
                    "DD * 可在新的 // 语句处结束；DD DATA 需以 /* 或 DLM 指定的分隔符结束。");
        }
        return statements;
    }

    private LogicalStatement finish(String text, int startLine, int endLine,
                                    List<LogicalStatement> statements) {
        LogicalStatement stmt = reader.read(text, startLine, endLine);
        if (stmt != null) {
            statements.add(stmt);
        }
        return stmt;
    }

    /**
     * 续行判断：以 {@code //} 开头，且第 3-15 列为空白。
     */
    private boolean isContinuationLine(String code) {
        if (!code.startsWith("//")) {
            return false;
        }
        int end = Math.min(code.length(), NAME_FIELD_END);
        for (int i = 2; i < end; i++) {
            char c = code.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private boolean isInstreamStart(LogicalStatement stmt) {
        if (!stmt.isOperation("DD")) {
            return false;
        }
        String p = stmt.getParameters().strip();
        if (p.equals("*")) {
            return true;
        }
        String upper = p.toUpperCase(Locale.ROOT);
        return upper.equals("DATA") || upper.startsWith("DATA,");
    }

    /**
     * DD * 的流内数据可以由新的 // 语句结束；DD DATA 不能，必须用分隔符。
     */
    private boolean isStarInstream(LogicalStatement stmt) {
        return stmt.getParameters().strip().equals("*");
    }

    private String instreamDelimiter(LogicalStatement stmt) {
        Matcher matcher = DLM_PATTERN.matcher(stmt.getParameters());
        if (matcher.find()) {
            return ParameterMap.unquote(matcher.group(1));
        }
        return "/*";
    }

    private static final class InstreamState {
        private boolean active;
        private LogicalStatement statement;
        private final StringBuilder data = new StringBuilder();
        private String delimiter = "/*";
        private boolean endsOnSlashSlash;

        private void start(LogicalStatement stmt, String delimiter, boolean endsOnSlashSlash) {
            this.active = true;
            this.statement = stmt;
            this.data.setLength(0);
            this.delimiter = delimiter;
            this.endsOnSlashSlash = endsOnSlashSlash;
        }

        private void finish() {
            if (statement != null) {
                statement.setInstreamData(data.toString().stripTrailing());
            }
            active = false;
            statement = null;
            data.setLength(0);
            delimiter = "/*";
            endsOnSlashSlash = false;
        }
    }
}
