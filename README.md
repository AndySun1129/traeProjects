# JCL Visualizer

把 JCL 源码解析成 **STEP / DSN（数据集）数据流图** 的轻量 Web 工具。

- 输入：一段 JCL（支持多 JOB、内嵌 PROC、续行、instream 数据）
- 输出：每个 JOB 一张有向图，STEP 卡片 + DSN 圆点，边表示读写关系和执行顺序
- 交互：点击高亮、DSN 搜索、STEP 详情、多 JOB 切换、导出 PNG

## 快速开始

需要 JDK 21+、Maven 3.8+。

```bash
cd jcl-visualizer
mvn spring-boot:run
# 打开 http://localhost:8080
```

或者先打包再运行：

```bash
mvn -DskipTests package
java -jar target/jcl-visualizer-1.0.0.jar
```

指定端口：

```bash
java -jar target/jcl-visualizer-1.0.0.jar --server.port=18080
```

## 测试

```bash
mvn test
```

测试集中在 `src/test/java/com/jcl/service/JclParserServiceTest.java`，覆盖：

- 标准示例（6 个 STEP，SYSOUT 被过滤）
- 续行参数不被 72 列截断、`DCB=(...)` 嵌套参数
- 错误行号字段 `line`
- `DISP` 缺省 / `DISP=(,CATLG)` 的写方向
- `DSN=*.STEP.DD` 引用
- 行尾逗号误续行的报错与不吞语句
- 内嵌 PROC 展开 + `&SYMBOL` 替换
- 多 JOB 拆分
- JOBLIB / STEPLIB / SYSOUT / DUMMY / INSTREAM 过滤
- GDG 代际合并
- 无名 EXEC 的显示名回退
- `DSN=NULLFILE` 特殊节点
- `DD DATA,DLM=@@`
- 边上的 DD 标签与方向

## API

`POST /api/parse`

```json
{ "jclSource": "//JOB1 JOB ...\n//STEP1 EXEC PGM=..." }
```

响应（节选）：

```json
{
  "success": true,
  "message": "解析完成：共 1 个 JOB，2 个 STEP，3 个 DSN。",
  "jobs": [
    {
      "jobInfo": { "name": "JOB1", "account": "ACCT", "programmer": "PGMR", "line": 1 },
      "stepList": [
        {
          "id": "step_1",
          "name": "STEP1",
          "displayName": "STEP1",
          "order": 1,
          "execType": "PGM",
          "pgm": "IEBGENER",
          "line": 2,
          "ddStatements": [ ... ]
        }
      ],
      "dsnList": [
        {
          "id": "dsn_1",
          "name": "MY.DATA.SET",
          "type": "NORMAL",
          "logicalName": "IN",
          "lrecl": "80",
          "recfm": "FB",
          "blksize": "800",
          "writerSteps": [1],
          "readerSteps": [2]
        }
      ],
      "edges": [
        { "id": "e_step_1", "from": "step_1", "to": "step_2", "type": "STEP" },
        {
          "id": "e_flow_2",
          "from": "step_1",
          "to": "dsn_1",
          "type": "FLOW",
          "ioType": "O",
          "label": "OUT"
        }
      ]
    }
  ],
  "errors": [],
  "warnings": []
}
```

- **单一数据源**：边只由后端生成，前端直接渲染 `edges`，不再从 STEP 的读写 ID 二次推导。
- 后端同时返回 `STEP`（执行顺序）与 `FLOW`（数据流）两类边；前端默认**只渲染 FLOW 边**，避免虚线被误认为断线，也让图更清爽。
- `FLOW` 边的 `label` 为 DD 名；DSN 节点只显示物理名，避免重复。
- 诊断项统一为 `{ "line", "message", "suggestion" }`；可恢复问题进 `warnings`，致命问题进 `errors`。
- 多 JOB 时 `jobs` 有多个元素，前端通过顶部下拉框切换。

## 解析规则

### 列规则与续行

- 第 1–71 列为代码，第 72 列为续行标志，第 73–80 列忽略。
- 续行必须以 `//` 开头，且第 3–15 列为空；否则报错并结束当前语句。
- 兼容“行尾逗号”写法，但同样会校验下一行是否是合法续行；不会再把下一条 `//DD` 吞进当前语句。
- **拼接后的逻辑语句不再按 72 列截断**（这是旧版本会丢 `DCB`/`SPACE` 的根因）。

### 数据集

| 类型 | 识别方式 | 图中节点 |
|---|---|---|
| NORMAL | `DSN=...` | 是 |
| TEMPORARY | `DSN=&&...` 或隐式临时集 | 是 |
| GDG | `DSN=...(+1)` / `(0)` | 是，按 base 名合并 |
| REFERENCE | `DSN=*.STEP.DD` | 是，复用目标 DSN 节点 |
| NULLFILE | `DSN=NULLFILE` | 是，灰色虚线菱形特殊节点（丢弃输出） |
| SYSOUT | `SYSOUT=` | 否（保留在 STEP 详情） |
| DUMMY | `DUMMY` | 否 |
| INSTREAM | `DD *` / `DD DATA` / `DD DATA,DLM=` | 否 |
| LIBRARY | `JOBLIB` / `STEPLIB` | 否 |

读写方向：

- `DISP=NEW` / `MOD` → 写
- `DISP=SHR` / `OLD` → 读
- `DISP` 缺省或 `DISP=(,CATLG)` → 写（JCL 默认 NEW）
- 临时集首次出现为写，后续为读；显式 `DISP` 优先

### PROC

- 先扫描 `//name PROC ... // PEND` 定义，再在 `EXEC PROC=` 处展开。
- 支持 `&SYMBOL` 替换，优先级：`SET` / 外层作用域 < PROC 默认值 < EXEC 覆盖值。
- 未找到的 PROC 保留一个占位 STEP，并给出警告。
- 写在 `EXEC PROC=` 之后的 DD 视为对过程第一步的覆盖。

## 架构

```text
controller/
  PageController          GET /            页面
  JclApiController        POST /api/parse  JSON API
service/
  JclParserService        编排：预处理 → 作业解析 → 数据集解析 → 图构建
parser/
  JclPreprocessor         物理行、列规则、续行、instream
  JclStatementReader      逻辑语句 → name / operation / parameters
  ParameterMap            顶层逗号、括号、引号安全的 KV 解析
  JclJobParser            两遍扫描：PROC 定义 + JOB/EXEC/DD/PROC 展开
  DdParser                DISP / DCB / DSN / SYSOUT / 引用
  LogicalStatement        语句 + 起止行号 + 原始文本
  Diagnostics             errors / warnings
graph/
  DatasetResolver         DSN 去重、读写方向、*.DD 引用、GDG、临时集
  GraphBuilder            STEP 链边 + FLOW 边（唯一数据源）
model/                    JSON DTO
```

设计要点：

- 每个 STEP / DD 都带 `line`，为后续“点图跳源码”预留。
- `DdKind` 明确区分数据流 DD 与系统/库 DD，避免 SYSOUT 节点污染图。
- `StepInfo` 不再保存 `readDsnIds/writeDsnIds`，读写关系只在 `GraphEdge` 中表达。
- 控制器使用构造器注入；页面控制器与 JSON API 控制器分离。
- 多 JOB 每个 JOB 一张图，由前端选择器切换。

## 已知限制

- `IF/THEN/ELSE` 条件分支不参与图建模，只给出警告；条件中的 STEP 仍会按顺序展示。
- `INCLUDE` 不展开，只给出警告。
- 外部 PROC（PDS 中的过程）无法展开，会保留占位 STEP。
- 符号替换只做文本级 `&NAME`；系统符号（如 `&SYSUID`）保持原样。
- GDG 只做 base 级合并，不区分具体代际的先后关系。
- 前端依赖 CDN：CodeMirror 5.65.16、vis-network 10.1.2。**内网/离线部署需保证能访问 jsDelivr 与 cdnjs**，或自行把两个库落到 `static/vendor/`。

## 版本

- Spring Boot 2.7.18
- Java 21（实测 JDK 23 可运行）
- Maven 构建，产物 `target/jcl-visualizer-1.0.0.jar`

> 升级到 Spring Boot 3.x 需要把 `javax.*` 迁移到 `jakarta.*`，本项目当前不涉及 Servlet API 直接调用，迁移成本较低，但不在本次改动范围内。
