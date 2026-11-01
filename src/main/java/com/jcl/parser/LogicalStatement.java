package com.jcl.parser;

/**
 * 一条逻辑 JCL 语句（可能由多行续行拼接而成）。
 * <p>
 * 保留起止行号与完整原始文本，便于后续做“图节点 → 源码行”的溯源。
 */
public final class LogicalStatement {

    private final String name;
    private final String operation;
    private final String parameters;
    private final String rawText;
    private final int startLine;
    private final int endLine;
    private String instreamData;

    public LogicalStatement(String name, String operation, String parameters,
                            String rawText, int startLine, int endLine) {
        this.name = name == null ? "" : name;
        this.operation = operation == null ? "" : operation;
        this.parameters = parameters == null ? "" : parameters;
        this.rawText = rawText == null ? "" : rawText;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getName() {
        return name;
    }

    public String getOperation() {
        return operation;
    }

    public String getParameters() {
        return parameters;
    }

    public String getRawText() {
        return rawText;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getInstreamData() {
        return instreamData;
    }

    public void setInstreamData(String instreamData) {
        this.instreamData = instreamData;
    }

    public boolean isOperation(String op) {
        return operation.equalsIgnoreCase(op);
    }

    /**
     * 判断是否为过程定义结束语句。
     * <p>
     * 标准写法 {@code // PEND} 会被拆成 name=PEND、operation 为空；
     * 也兼容 {@code //PEND PEND} 之类的写法。
     */
    public boolean isPend() {
        return isOperation("PEND") || (operation.isEmpty() && "PEND".equalsIgnoreCase(name));
    }
}
