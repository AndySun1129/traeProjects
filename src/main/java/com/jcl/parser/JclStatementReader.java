package com.jcl.parser;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 将拼接后的逻辑语句拆成“名称 / 操作 / 参数”三段。
 * <p>
 * 这里刻意不再按 72 列截断：72 列规则只作用于物理行，拼接后的逻辑语句可以任意长。
 */
@Component
public class JclStatementReader {

    private static final Set<String> OPERATION_KEYWORDS = Set.of(
            "JOB", "EXEC", "DD", "OUTPUT", "PROC", "PEND", "COMMAND", "XMIT",
            "SET", "IF", "THEN", "ELSE", "ENDIF", "INCLUDE", "CNTL", "ENDCNTL"
    );

    public LogicalStatement read(String text, int startLine, int endLine) {
        if (text == null) {
            return null;
        }
        String t = text.stripTrailing();
        if (!t.startsWith("//")) {
            return null;
        }
        String body = t.substring(2).strip();
        if (body.isEmpty()) {
            return null;
        }

        String[] tokens = body.split("\\s+", 3);
        String first = tokens[0];
        boolean firstIsOp = isOperationKeyword(first);
        boolean secondIsOp = tokens.length >= 2 && isOperationKeyword(tokens[1]);

        String name;
        String operation;
        String parameters;

        if (firstIsOp && !secondIsOp) {
            name = "";
            operation = first;
            parameters = tokens.length >= 2 ? tokens[1] : "";
            if (tokens.length >= 3) {
                parameters += " " + tokens[2];
            }
        } else if (tokens.length >= 2) {
            name = first;
            operation = tokens[1];
            parameters = tokens.length >= 3 ? tokens[2] : "";
        } else {
            name = first;
            operation = "";
            parameters = "";
        }

        // JCL 常见写法：//MYPROC PROC,ACCT=PROD（操作符与参数之间没有空格）
        int comma = operation.indexOf(',');
        if (comma >= 0) {
            String tail = operation.substring(comma + 1).strip();
            operation = operation.substring(0, comma);
            if (!tail.isEmpty()) {
                parameters = parameters.isBlank() ? tail : tail + " " + parameters;
            }
        }

        return new LogicalStatement(name, operation, parameters, t, startLine, endLine);
    }

    private static boolean isOperationKeyword(String token) {
        return token != null && OPERATION_KEYWORDS.contains(token.toUpperCase(Locale.ROOT));
    }
}
