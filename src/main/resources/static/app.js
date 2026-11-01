// =========================================================
// 0. 工具函数
// =========================================================
function escapeHtml(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function(c){
        return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
}
function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.arcTo(x + w, y, x + w, y + r, r);
    ctx.lineTo(x + w, y + h - r);
    ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
    ctx.lineTo(x + r, y + h);
    ctx.arcTo(x, y + h, x, y + h - r, r);
    ctx.lineTo(x, y + r);
    ctx.arcTo(x, y, x + r, y, r);
    ctx.closePath();
}

// =========================================================
// 1. 示例 JCL
// =========================================================
const SAMPLE_JCL = `//SAMPLEJOB JOB (ACCOUNT),'DEMO',CLASS=A,MSGCLASS=H
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
//* END OF SAMPLE JCL`;

// =========================================================
// 2. 全局状态
// =========================================================
let network = null;
let nodes = null;
let edges = null;
let stepDefs = [];
let dsnDefs = {};
let jobInfo = null;
let defaultNodeStyles = {};
let defaultEdgeStyles = {};
let stepNodeIds = [];
let allJobs = [];
let currentJobIndex = 0;
let graphEdges = [];
let lastGraph = null;
let loadingTimer = null;
const labelWidthCache = Object.create(null);

// 样式常量
const STEP_COLOR   = '#4A90D9';
const STEP_BORDER  = '#2F6BA8';
const STEP_FONT    = '#ffffff';
const DSN_COLOR    = '#F5A623';
const DSN_BORDER   = '#B87A0E';
const DSN_FONT     = '#333333';
const HL_COLOR     = '#E8590C';
const HL_BORDER    = '#7a1f00';
const FADE_COLOR   = '#DDDDDD';
const FADE_BORDER  = '#CCCCCC';
const EDGE_DEFAULT = '#A0A0A0';
const EDGE_HL      = '#E8590C';
const EDGE_FADE    = '#e5e7eb';
const DSN_LABEL_POS = 'right';
const DSN_LABEL_OFFSET = { x: 12, y: 0 };
const DSN_DEFAULT_SIZE = 7;
const DSN_HIGHLIGHT_SIZE = 12;

// =========================================================
// 3. JCL 语法高亮 mode（CodeMirror）
// =========================================================
CodeMirror.defineMode('jcl', function() {
    const ops = {
        'JOB':1,'EXEC':1,'DD':1,'PROC':1,'PEND':1,'OUTPUT':1,
        'IF':1,'THEN':1,'ELSE':1,'ENDIF':1,'SET':1,'INCLUDE':1,
        'COMMAND':1,'XMIT':1,'CNTL':1,'ENDCNTL':1
    };
    const params = {
        'DSN':1,'DISP':1,'SPACE':1,'UNIT':1,'VOL':1,'DCB':1,
        'LRECL':1,'RECFM':1,'BLKSIZE':1,'SYSOUT':1,'CLASS':1,
        'MSGCLASS':1,'MSGLEVEL':1,'PRTY':1,'REGION':1,'TIME':1,
        'COND':1,'PGM':1,'PARM':1,'LABEL':1,'SHR':1,'OLD':1,
        'NEW':1,'MOD':1,'KEEP':1,'DELETE':1,'CATLG':1,'PASS':1
    };
    return {
        startState: function() { return {}; },
        token: function(stream) {
            // 第 72 列（下标 71）之后为序号/注释区，整段变灰
            if (stream.pos >= 72) { stream.skipToEnd(); return 'shade'; }
            // 行首注释 //*（整行注释，不受 72 列限制）
            if (stream.sol() && stream.match(/^\/\/\*/, true)) { stream.skipToEnd(); return 'comment'; }
            if (stream.eatSpace()) return null;

            const ch = stream.peek();
            const limit = Math.min(72, stream.string.length);
            // 字符串（消费不超过第 72 列）
            if (ch === "'" || ch === '"') {
                const quote = ch;
                stream.next();
                while (stream.pos < limit && stream.peek() !== quote) stream.next();
                if (stream.pos < limit && stream.peek() === quote) stream.next();
                return 'string';
            }
            // 标识符 / 关键字 / 参数（消费不超过第 72 列）
            if (/[A-Za-z@#$]/.test(ch)) {
                while (stream.pos < limit && /[A-Za-z0-9@#$]/.test(stream.peek())) stream.next();
                const cur = stream.current().toUpperCase();
                if (ops[cur]) return 'keyword';
                if (params[cur]) return 'builtin';
                return null;
            }
            // 数字（消费不超过第 72 列）
            if (/[0-9]/.test(ch)) {
                while (stream.pos < limit && /[0-9]/.test(stream.peek())) stream.next();
                return 'number';
            }
            stream.next();
            return null;
        }
    };
});

// =========================================================
// 4. 编辑器初始化（CodeMirror）
// =========================================================
const editor = CodeMirror.fromTextArea(document.getElementById('editor'), {
    mode: 'jcl',
    lineNumbers: true,
    lineWrapping: false,
    styleActiveLine: false,
    indentUnit: 4,
    tabSize: 4,
    extraKeys: { 'Tab': function(cm){ cm.replaceSelection('    ', 'end'); } }
});

function updateGutter(errLines) {
    const total = editor.lineCount();
    for (let i = 0; i < total; i++) {
        editor.removeLineClass(i, 'gutter', 'err');
    }
    if (errLines) {
        errLines.forEach(function(ln) {
            if (ln > 0) editor.addLineClass(ln - 1, 'gutter', 'err');
        });
    }
}

// =========================================================
// 5. 解析按钮：调用后端 API
// =========================================================
document.getElementById('btnParse').addEventListener('click', function(){ parseJcl(true); });
document.getElementById('btnSample').addEventListener('click', function(){
    editor.setValue(SAMPLE_JCL);
    parseJcl();
});
document.getElementById('btnClear').addEventListener('click', function(){
    editor.setValue('');
    hideErrorBar();
    hideWarnings();
    hideLoading();
    updateGutter([]);
    stepDefs = []; dsnDefs = {}; jobInfo = null;
    allJobs = []; currentJobIndex = 0; graphEdges = []; lastGraph = null;    document.getElementById('jobSelectWrap').style.display = 'none';
    document.getElementById('jobSelect').innerHTML = '';
    if (network) { network.destroy(); network = null; }
    document.getElementById('jobName').textContent = '（未解析）';
    document.getElementById('stepCount').textContent = '0';
    document.getElementById('dsnCount').textContent = '0';
});
document.getElementById('btnUpload').addEventListener('click', function(){
    document.getElementById('fileInput').click();
});
document.getElementById('fileInput').addEventListener('change', function(e){
    const f = e.target.files[0];
    if (!f) return;
    const r = new FileReader();
    r.onload = function(){ editor.setValue(r.result); parseJcl(); };
    r.readAsText(f);
});

// 拖拽上传
const editorWrap = document.getElementById('editorWrap');
['dragenter','dragover'].forEach(function(ev){
    editorWrap.addEventListener(ev, function(e){ e.preventDefault(); editorWrap.classList.add('dragover'); });
});
['dragleave','drop'].forEach(function(ev){
    editorWrap.addEventListener(ev, function(e){ e.preventDefault(); editorWrap.classList.remove('dragover'); });
});
editorWrap.addEventListener('drop', function(e){
    const f = e.dataTransfer.files[0];
    if (!f) return;
    const r = new FileReader();
    r.onload = function(){ editor.setValue(r.result); parseJcl(); };
    r.readAsText(f);
});

async function parseJcl(autoCollapse) {
    const src = editor.getValue();
    if (!src.trim()) { showErrorBar([{ line: 0, message: 'JCL 源码为空', suggestion: '请粘贴或加载示例' }]); return; }
    const btn = document.getElementById('btnParse');
    btn.disabled = true; btn.textContent = '解析中...';
    showLoading('正在解析 JCL…');
    try {
        const resp = await fetch('/api/parse', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ jclSource: src })
        });
        const result = await resp.json();
        if (!result.success) {
            const errs = (result.errors || []).slice();
            if (errs.length === 0) {
                errs.push({ line: 0, message: result.message || '解析失败', suggestion: '请检查 JCL 内容' });
            }
            showErrorBar(errs);
            hideWarnings();
            hideLoading();
            return;
        }
        hideErrorBar();
        // 成功时仍可能有可恢复警告（例如续行格式问题），统一展示；错误行才标红
        updateGutter((result.errors || []).map(function(e){ return e.line; }).filter(function(l){ return l > 0; }));
        showWarnings((result.errors || []).concat(result.warnings || []));
        if (!result.jobs || result.jobs.length === 0) {
            showErrorBar([{ line: 0, message: '解析成功但没有生成图数据', suggestion: '请检查 JCL 内容' }]);
            hideLoading();
            return;
        }
        // 先让等待遮罩绘制出来，再执行同步的 vis 布局
        showLoading('正在渲染流程图…');
        const jobsToRender = result.jobs;
        setTimeout(function() {
            try {
                renderJobs(jobsToRender);
                // 解析成功自动收起侧栏
                if (autoCollapse && !document.getElementById('app').classList.contains('collapsed')) {
                    toggleSidebar();
                }
            } catch (err) {
                showErrorBar([{ line: 0, message: '渲染失败：' + err.message, suggestion: '请刷新页面重试' }]);
                hideLoading();
            }
        }, 30);
    } catch (e) {
        showErrorBar([{ line: 0, message: '请求失败：' + e.message, suggestion: '请检查后端服务是否启动' }]);
        hideLoading();
    } finally {
        btn.disabled = false; btn.textContent = '解析生成';
    }
}

function showErrorBar(errs) {
    const bar = document.getElementById('errorBar');
    if (!errs || errs.length === 0) { bar.classList.remove('show'); return; }
    bar.classList.add('show');
    bar.innerHTML = errs.map(function(e){
        return '<div class="err-item"><span class="err-lno">行 ' + (e.line || 0) + '</span>' +
            escapeHtml(e.message) +
            (e.suggestion ? '<span style="color:#6b7280;margin-left:8px;">建议：' + escapeHtml(e.suggestion) + '</span>' : '') +
            '</div>';
    }).join('');
    updateGutter(errs.map(function(e){ return e.line; }).filter(function(l){ return l > 0; }));
}
function hideErrorBar() { document.getElementById('errorBar').classList.remove('show'); }

function showLoading(text) {
    const overlay = document.getElementById('loadingOverlay');
    if (!overlay) return;
    const label = document.getElementById('loadingText');
    if (label) label.textContent = text || '正在处理…';
    overlay.classList.add('show');
    if (loadingTimer) clearTimeout(loadingTimer);
    // 兜底：即使 afterDrawing 没有触发，也不能让遮罩永久挡住页面
    loadingTimer = setTimeout(hideLoading, 4000);
}

function hideLoading() {
    const overlay = document.getElementById('loadingOverlay');
    if (overlay) overlay.classList.remove('show');
    if (loadingTimer) {
        clearTimeout(loadingTimer);
        loadingTimer = null;
    }
}

function showWarnings(list) {
    const bar = document.getElementById('warnBar');
    if (!list || list.length === 0) { hideWarnings(); return; }
    const shown = list.slice(0, 30);
    bar.innerHTML = shown.map(function(e){
        return '<div class="warn-item"><span class="warn-lno">行 ' + (e.line || 0) + '</span>' +
            escapeHtml(e.message) +
            (e.suggestion ? '<span style="color:#a16207;margin-left:8px;">建议：' + escapeHtml(e.suggestion) + '</span>' : '') +
            '</div>';
    }).join('') + (list.length > shown.length
        ? '<div class="warn-item" style="color:#a16207;">…… 另有 ' + (list.length - shown.length) + ' 条警告未显示</div>'
        : '');
    bar.classList.add('show');
}
function hideWarnings() {
    const bar = document.getElementById('warnBar');
    bar.classList.remove('show');
    bar.innerHTML = '';
}

// =========================================================
// 6. 后端 GraphData 转前端模型
// =========================================================
function buildFromGraphData(graph) {
    jobInfo = graph.jobInfo || {};
    stepDefs = [];
    dsnDefs = {};
    graphEdges = graph.edges || [];

    (graph.dsnList || []).forEach(function(d){
        dsnDefs[d.id] = {
            physicalName: d.name,
            logicalName: d.logicalName,
            type: d.type,
            dcb: d.dcb,
            lrecl: d.lrecl,
            recfm: d.recfm,
            blksize: d.blksize,
            writerSteps: d.writerSteps || [],
            readerSteps: d.readerSteps || []
        };
    });

    (graph.stepList || []).forEach(function(s){
        stepDefs.push({
            id: s.id,
            stepName: s.displayName || s.name || s.pgm || s.proc || ('STEP ' + s.order),
            rawName: s.name || '',
            program: s.pgm || s.proc || '',
            order: s.order,
            procName: s.procName || '',
            line: s.line || 0,
            execParameters: s.execParameters || '',
            parm: s.parm || '',
            dds: s.ddStatements || []
        });
    });
}

// =========================================================
// 6.5 按数据依赖计算 STEP 深度（同一层并行 STEP 上下排列）
// =========================================================
function computeStepDepths() {
    const depth = {};
    const orderToId = {};
    stepDefs.forEach(function(s) {
        depth[s.id] = 0;
        orderToId[s.order] = s.id;
    });

    const inputs = {};
    stepDefs.forEach(function(s) { inputs[s.id] = []; });
    graphEdges.forEach(function(e) {
        if (e.type !== 'FLOW' || e.ioType !== 'I') return;
        const dsn = dsnDefs[e.from];
        if (!dsn) return;
        (dsn.writerSteps || []).forEach(function(order) {
            const writerId = orderToId[order];
            if (writerId && writerId !== e.to && inputs[e.to].indexOf(writerId) < 0) {
                inputs[e.to].push(writerId);
            }
        });
    });

    // 最长路径松弛；passes 上限可避免脏数据形成环时死循环
    const passes = stepDefs.length + 1;
    for (let p = 0; p < passes; p++) {
        let changed = false;
        stepDefs.forEach(function(s) {
            let d = 0;
            inputs[s.id].forEach(function(writerId) {
                d = Math.max(d, (depth[writerId] || 0) + 1);
            });
            if (d > depth[s.id]) {
                depth[s.id] = d;
                changed = true;
            }
        });
        if (!changed) break;
    }
    return depth;
}

// =========================================================
// 7. 构造 vis-network 节点与边
// =========================================================
function buildNodes() {
    const arr = [];
    const stepDepth = computeStepDepths();
    const depthByOrder = {};
    stepDefs.forEach(function(s) {
        depthByOrder[s.order] = stepDepth[s.id] || 0;
    });
    const BASE_LEVEL = 2;

    stepDefs.forEach(function(s){
        arr.push({
            id: s.id,
            label: s.stepName,
            shape: 'custom',
            level: (stepDepth[s.id] || 0) * 2 + BASE_LEVEL,
            _type: 'step',
            _step: s,
            _faded: false
        });
    });
    Object.keys(dsnDefs).forEach(function(id){
        const d = dsnDefs[id];
        const isNullFile = d.type === 'NULLFILE';
        const isTemporary = d.type === 'TEMPORARY';
        // 只显示物理名；DD 名已经在边标签上，避免重复
        const labelText = d.physicalName;
        // 计算层级：有写入方的 DSN 紧跟写入方右侧；仅有读取方的外部数据集放在最早读取方左侧
        const wOrders = d.writerSteps || [];
        const rOrders = d.readerSteps || [];
        let dsnLevel;
        if (wOrders.length > 0) {
            // 写入方可能在不同深度：放到最右写入方的下一层
            const maxWriterDepth = Math.max.apply(null, wOrders.map(function(o) {
                return depthByOrder[o] || 0;
            }));
            dsnLevel = maxWriterDepth * 2 + BASE_LEVEL + 1;
        } else if (rOrders.length > 0) {
            // 外部输入：放到最早读取方的左侧一层
            const minReaderDepth = Math.min.apply(null, rOrders.map(function(o) {
                return depthByOrder[o] || 0;
            }));
            dsnLevel = minReaderDepth * 2 + BASE_LEVEL - 1;
        } else {
            dsnLevel = BASE_LEVEL;
        }
        const node = {
            id: id,
            label: labelText,
            shape: isNullFile ? 'diamond' : (isTemporary ? 'circle' : 'dot'),
            level: dsnLevel,
            size: isNullFile ? 11 : (isTemporary ? 8 : DSN_DEFAULT_SIZE),
            color: isNullFile
                ? { background: '#e5e7eb', border: '#6b7280' }
                : (isTemporary
                    ? { background: '#ffffff', border: '#7c3aed' }
                    : { background: DSN_COLOR, border: DSN_BORDER }),
            borderWidth: (isNullFile || isTemporary) ? 2 : 1.5,
            font: isNullFile
                ? { color: '#4b5563', size: 10.5, face: 'Consolas', ital: true }
                : (isTemporary
                    ? { color: '#6d28d9', size: 10.5, face: 'Consolas', ital: true }
                    : { color: DSN_FONT, size: 10.5, face: 'Consolas' }),
            labelPosition: DSN_LABEL_POS,
            labelOffset: DSN_LABEL_OFFSET,
            _type: 'dsn',
            _dsn: d
        };
        if (isNullFile) {
            node.shapeProperties = { borderDashes: [4, 4] };
        } else if (isTemporary) {
            node.shapeProperties = { borderDashes: [3, 3] };
        }
        arr.push(node);
    });
    return arr;
}

function buildEdges(graphEdgesIn) {
    const arr = [];
    (graphEdgesIn || []).forEach(function(e){
        // 只渲染数据流边。STEP 执行顺序边不画，避免虚线被误认为断线/干扰数据流阅读。
        if (e.type === 'STEP') return;
        arr.push({
            id: e.id,
            from: e.from,
            to: e.to,
            // 标签不用 vis 自带的 edge.label（它按节点中心定位，会压到卡片上），
            // 改由 afterDrawing 在“两端边界之间的中点”绘制。
            _label: e.label || '',
            arrows: { to: { enabled: true, scaleFactor: 0.9 } },
            color: {
                color: EDGE_DEFAULT,
                highlight: EDGE_HL,
                hover: EDGE_HL,
                inherit: false
            },
            width: 1.5,
            smooth: { type: 'cubicBezier', roundness: 0.5 },
            _kind: e.ioType === 'O' ? 'write' : 'read'
        });
    });
    return arr;
}

// =========================================================
// 7.5 多 JOB 切换
// =========================================================
function renderJobs(jobs) {
    allJobs = jobs || [];
    const wrap = document.getElementById('jobSelectWrap');
    const select = document.getElementById('jobSelect');

    if (allJobs.length > 1) {
        select.innerHTML = allJobs.map(function(j, i){
            const name = (j.jobInfo && j.jobInfo.name) ? j.jobInfo.name : ('JOB ' + (i + 1));
            return '<option value="' + i + '">' + escapeHtml(name) + '</option>';
        }).join('');
        select.value = '0';
        wrap.style.display = '';
        currentJobIndex = 0;
    } else {
        select.innerHTML = '';
        wrap.style.display = 'none';
        currentJobIndex = 0;
    }

    if (allJobs.length > 0) {
        renderGraph(allJobs[0]);
    }
}

// =========================================================
// 7.6 数据流边标签（画在两端边界之间的中点，避免压住 STEP 卡片）
// =========================================================
function drawEdgeLabels(ctx) {
    if (!network || !edges || !ctx) return;
    ctx.save();
    try {
        ctx.font = '10px Consolas, "Courier New", monospace';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        const positions = network.getPositions(nodes.getIds());

        edges.forEach(function(edge){
            if (!edge._label) return;
            const fromIsDsn = !!dsnDefs[edge.from];
            const toIsDsn = !!dsnDefs[edge.to];
            if (fromIsDsn === toIsDsn) return;

            const dsnId = fromIsDsn ? edge.from : edge.to;
            const stepId = fromIsDsn ? edge.to : edge.from;
            const stepPos = positions[stepId];
            const dsnPos = positions[dsnId];
            if (!stepPos || !dsnPos) return;

            const dx = dsnPos.x - stepPos.x;
            const dy = dsnPos.y - stepPos.y;
            const distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < 1) return;

            const ux = dx / distance;
            const uy = dy / distance;
            // 从 STEP 卡片边界到 DSN 圆/菱形边界，取可见连线的中点（无曲线信息时使用）
            const stepHalfW = 75;
            const stepHalfH = 26;
            const stepOffset = Math.min(
                stepHalfW / Math.max(Math.abs(ux), 1e-6),
                stepHalfH / Math.max(Math.abs(uy), 1e-6)
            );
            const dsnNode = nodes.get(dsnId);
            const dsnRadius = (dsnNode && dsnNode.size) ? dsnNode.size : DSN_DEFAULT_SIZE;
            const start = stepOffset + 5;
            const end = distance - dsnRadius - 5;
            if (end <= start) return;

            const text = String(edge._label);
            const textWidth = labelWidthCache[text] || (labelWidthCache[text] = ctx.measureText(text).width);
            const halfW = textWidth / 2 + 6;
            const halfH = 9;

            // 用 vis 的 cubicBezier 参数直接算曲线点：不依赖内部 API，
            // 拖拽后也能取到当前节点位置对应的真实曲线中点与切线。
            const bodyEdge = network.body && network.body.edges ? network.body.edges[edge.id] : null;
            const edgeType = bodyEdge && bodyEdge.edgeType;
            const fromPos = positions[edge.from];
            const toPos = positions[edge.to];
            let getPoint = null;

            if (fromPos && toPos) {
                const fromPoint = (edgeType && edgeType.fromPoint) ? edgeType.fromPoint : fromPos;
                const toPoint = (edgeType && edgeType.toPoint) ? edgeType.toPoint : toPos;
                const cdx = fromPos.x - toPos.x;
                const cdy = fromPos.y - toPos.y;
                const roundness = 0.5;
                let via1;
                let via2;
                if (Math.abs(cdx) > Math.abs(cdy)) {
                    via1 = { x: fromPos.x - roundness * cdx, y: fromPos.y };
                    via2 = { x: toPos.x + roundness * cdx, y: toPos.y };
                } else {
                    via1 = { x: fromPos.x, y: fromPos.y - roundness * cdy };
                    via2 = { x: toPos.x, y: toPos.y + roundness * cdy };
                }
                getPoint = function(t) {
                    const u = 1 - t;
                    const x = u * u * u * fromPoint.x
                        + 3 * u * u * t * via1.x
                        + 3 * u * t * t * via2.x
                        + t * t * t * toPoint.x;
                    const y = u * u * u * fromPoint.y
                        + 3 * u * u * t * via1.y
                        + 3 * u * t * t * via2.y
                        + t * t * t * toPoint.y;
                    return { x: x, y: y };
                };
            } else if (edgeType && typeof edgeType.getPoint === 'function') {
                getPoint = function(t) {
                    try {
                        return edgeType.getPoint(t);
                    } catch (e) {
                        return null;
                    }
                };
            }

            const ratios = [0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8];
            let chosen = null;
            for (let i = 0; i < ratios.length; i++) {
                const ratio = ratios[i];
                let cx;
                let cy;
                if (getPoint) {
                    const point = getPoint(ratio);
                    if (!point) continue;
                    cx = point.x;
                    cy = point.y;
                } else {
                    const candidateT = start + (end - start) * ratio;
                    cx = stepPos.x + ux * candidateT;
                    cy = stepPos.y + uy * candidateT;
                }

                let overlaps = Math.abs(cx - dsnPos.x) < dsnRadius + halfW
                        && Math.abs(cy - dsnPos.y) < dsnRadius + halfH;
                if (!overlaps) {
                    for (let j = 0; j < stepDefs.length; j++) {
                        const sp = positions[stepDefs[j].id];
                        if (!sp) continue;
                        if (Math.abs(cx - sp.x) < 75 + halfW
                                && Math.abs(cy - sp.y) < 26 + halfH) {
                            overlaps = true;
                            break;
                        }
                    }
                }
                if (!overlaps) {
                    chosen = { x: cx, y: cy, ratio: ratio };
                    break;
                }
            }
            if (!chosen) return; // 所有候选都压在卡片/节点上，宁可不画

            // 切线角度：曲线用相邻两点求切线，直线回退到节点连线方向
            let angle;
            if (getPoint) {
                const p1 = getPoint(Math.max(0.01, chosen.ratio - 0.02));
                const p2 = getPoint(Math.min(0.99, chosen.ratio + 0.02));
                angle = (p1 && p2) ? Math.atan2(p2.y - p1.y, p2.x - p1.x) : Math.atan2(uy, ux);
            } else {
                angle = Math.atan2(uy, ux);
            }
            if (angle > Math.PI / 2 || angle < -Math.PI / 2) {
                angle += Math.PI;
            }

            const faded = edge.width <= 1.1;
            // 文字中心锚在曲线点上，旋转后仍然居中
            ctx.save();
            ctx.translate(chosen.x, chosen.y);
            ctx.rotate(angle);
            // 旋转后再次明确文字锚点，保证以文字中心对齐曲线点
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            // 不用矩形底：给文字描一圈浅色光晕，斜线/拖拽后不会出现“补丁”方块
            ctx.lineJoin = 'round';
            ctx.lineWidth = 4;
            ctx.strokeStyle = 'rgba(250,251,252,0.95)';
            ctx.strokeText(text, 0, 0);
            ctx.fillStyle = faded ? '#cbd5e1' : '#4b5563';
            ctx.fillText(text, 0, 0);
            ctx.restore();
        });
    } finally {
        ctx.restore();
    }
}

// =========================================================
// 8. 渲染流程图
// =========================================================
function renderGraph(graph) {
    lastGraph = graph;
    showLoading('正在渲染流程图…');
    buildFromGraphData(graph);

    document.getElementById('jobName').textContent = (jobInfo && jobInfo.name) ? jobInfo.name : '（未命名 JOB）';
    document.getElementById('jobAccount').textContent = (jobInfo && jobInfo.account) ? jobInfo.account : '—';
    document.getElementById('jobProgrammer').textContent = (jobInfo && jobInfo.programmer) ? jobInfo.programmer : '—';
    document.getElementById('jobClass').textContent = (jobInfo && jobInfo.jobClass) ? jobInfo.jobClass : '—';
    document.getElementById('jobMsgClass').textContent = (jobInfo && jobInfo.msgClass) ? jobInfo.msgClass : '—';
    document.getElementById('stepCount').textContent = stepDefs.length;
    document.getElementById('dsnCount').textContent = Object.keys(dsnDefs).length;
    document.getElementById('infoBar').classList.remove('show-dsn');

    const container = document.getElementById('mynetwork');
    if (network) { network.destroy(); network = null; }

    nodes = new vis.DataSet(buildNodes());
    edges = new vis.DataSet(buildEdges(graph.edges));
    const data = { nodes: nodes, edges: edges };

    const options = {
        layout: {
            hierarchical: {
                enabled: true, direction: 'LR', sortMethod: 'directed',
                levelSeparation: 180, nodeSpacing: 80, treeSpacing: 120,
                shakeTowards: 'roots', parentCentralization: true
            }
        },
        physics: { enabled: false },
        interaction: { hover: true, tooltipDelay: 120, selectable: true, multiselect: false, dragNodes: true },
        nodes: {
            borderWidth: 1.5,
            shadow: { enabled: true, color: 'rgba(0,0,0,0.12)', size: 4, x: 1, y: 2 },
            ctxRenderer: function(args) {
                const target = nodes.get(args.id);
                if (!target || target._type !== 'step' || !target._step) { return {}; }
                const st = target._step;
                const faded = !!target._faded;
                const W = 150, H = 52, topH = 22, r = 8;
                const left = args.x - W / 2, top = args.y - H / 2;
                const textColor = faded ? '#9ca3af' : '#ffffff';
                const subColor = faded ? '#b0b0b0' : '#eaf2fb';
                const borderColor = faded ? FADE_BORDER : STEP_BORDER;
                return {
                    nodeDimensions: { width: W, height: H },
                    drawNode: function() {
                        const ctx = args.ctx;
                        ctx.save();
                        try {
                            const grd = ctx.createLinearGradient(left, top, left, top + H);
                            if (faded) { grd.addColorStop(0, '#e5e7eb'); grd.addColorStop(1, '#d1d5db'); }
                            else { grd.addColorStop(0, '#5aa0e8'); grd.addColorStop(1, '#2f6ba8'); }
                            roundRect(ctx, left, top, W, H, r);
                            ctx.fillStyle = grd;
                            ctx.fill();
                            ctx.lineWidth = args.state.selected ? 2.5 : 1.5;
                            ctx.strokeStyle = borderColor;
                            ctx.stroke();
                            // 上下分隔线（贯穿卡片全宽）
                            ctx.fillStyle = faded ? 'rgba(255,255,255,0.3)' : 'rgba(255,255,255,0.55)';
                            ctx.fillRect(left, top + topH, W, 1);
                            // 右上角序号徽章（canvas 直接绘制圆形徽章）
                            const badgeR = 8;
                            const badgeCx = left + W - 16;
                            const badgeCy = top + topH / 2 + 1;
                            ctx.beginPath();
                            ctx.arc(badgeCx, badgeCy, badgeR, 0, Math.PI * 2);
                            ctx.fillStyle = faded ? '#e5e7eb' : '#ffffff';
                            ctx.fill();
                            ctx.lineWidth = 1;
                            ctx.strokeStyle = faded ? '#cbd5e1' : STEP_BORDER;
                            ctx.stroke();
                            ctx.textAlign = 'center';
                            ctx.textBaseline = 'middle';
                            ctx.font = 'bold 10px "Segoe UI", "Microsoft YaHei", sans-serif';
                            ctx.fillStyle = faded ? '#9ca3af' : '#2f6ba8';
                            ctx.fillText(String(st.order), badgeCx, badgeCy + 0.5);
                            // 上半：STEP 名称（左对齐）
                            ctx.textAlign = 'left';
                            ctx.textBaseline = 'middle';
                            ctx.font = 'bold 13px "Segoe UI", "Microsoft YaHei", sans-serif';
                            ctx.fillStyle = textColor;
                            ctx.fillText(st.stepName || '', left + 12, top + topH / 2 + 1);
                            // 下半：PGM 名称
                            ctx.textAlign = 'center';
                            ctx.textBaseline = 'middle';
                            ctx.font = '11px "Consolas", monospace';
                            ctx.fillStyle = subColor;
                            ctx.fillText(st.program || '', args.x, top + topH + (H - topH) / 2);
                        } finally {
                            // 即使绘制过程中出现异常，也必须恢复 canvas 状态，避免整张图花屏
                            ctx.restore();
                        }
                    }
                };
            }
        },
        edges: {
            shadow: false,
            arrows: { to: { enabled: true, scaleFactor: 0.9 } },
            smooth: { type: 'cubicBezier', roundness: 0.3 }
        }
    };

    network = new vis.Network(container, data, options);
    network.on('afterDrawing', function(ctx){ drawEdgeLabels(ctx); });
    network.once('afterDrawing', hideLoading);
    setTimeout(hideLoading, 1500);

    // 初始层次布局完成后冻结布局：之后高亮/重置只更新样式，不会把用户拖拽过的节点拉回原位。
    let layoutFrozen = false;
    const freezeLayout = function() {
        if (layoutFrozen || !network) return;
        layoutFrozen = true;
        network.setOptions({ layout: { hierarchical: { enabled: false } } });
    };
    network.once('afterDrawing', freezeLayout);
    setTimeout(freezeLayout, 800);

    defaultNodeStyles = {};
    stepNodeIds = [];
    nodes.forEach(function(n){
        if (n._type === 'step') {
            stepNodeIds.push(n.id);
        } else {
            defaultNodeStyles[n.id] = {
                size: n.size,
                color: { background: n.color.background, border: n.color.border },
                borderWidth: n.borderWidth,
                font: Object.assign({}, n.font),
                labelPosition: n.labelPosition,
                labelOffset: Object.assign({}, n.labelOffset)
            };
        }
    });
    defaultEdgeStyles = {};
    edges.forEach(function(e){
        defaultEdgeStyles[e.id] = {
            color: {
                color: (e.color && e.color.color) ? e.color.color : EDGE_DEFAULT,
                highlight: (e.color && e.color.highlight) ? e.color.highlight : EDGE_HL,
                hover: (e.color && e.color.hover) ? e.color.hover : EDGE_HL,
                inherit: false
            },
            width: e.width || 1.5
        };
    });

    network.on('selectNode', function(params){
        if (params.nodes.length !== 1) return;
        const nodeId = params.nodes[0];
        const node = nodes.get(nodeId);
        if (node._type === 'step') {
            highlightStep(nodeId);
        } else if (node._type === 'dsn') {
            highlightDsn(nodeId);
        }
    });
    network.on('doubleClick', function(params){
        if (params.nodes.length !== 1) return;
        const node = nodes.get(params.nodes[0]);
        if (node && node._type === 'step') {
            openStepModal(params.nodes[0]);
        }
    });
    network.on('click', function(params){
        if (params.nodes.length === 0 && params.edges.length === 0) resetStyles();
    });
    network.once('stabilized', function(){
        network.fit({ animation: true });
    });
    // physics:false 时 stabilized 可能不触发，用 setTimeout 兜底适配视图
    setTimeout(function(){ if (network) network.fit({ animation: false }); }, 300);
}

// =========================================================
// 9. 重置样式
// =========================================================
function resetStyles() {
    if (!nodes || !edges) return;
    const nUpdates = stepNodeIds.map(function(id){ return { id: id, _faded: false }; })
        .concat(Object.keys(defaultNodeStyles).map(function(id){
            return Object.assign({ id: id }, defaultNodeStyles[id]);
        }));
    nodes.update(nUpdates);
    edges.update(Object.keys(defaultEdgeStyles).map(function(id){
        return Object.assign({ id: id }, defaultEdgeStyles[id]);
    }));
    document.getElementById('infoBar').classList.remove('show-dsn');
}

// =========================================================
// 10. DSN 高亮 + 顶部信息栏
// =========================================================
function highlightDsn(dsnId) {
    if (!network || !dsnDefs[dsnId]) return;
    const dsnInfo = dsnDefs[dsnId];
    const writerStepIds = [];
    const readerStepIds = [];
    const relatedEdgeIds = new Set();
    edges.forEach(function(edge){
        if (edge._kind === 'step') return;
        if (edge.to === dsnId) {
            writerStepIds.push(edge.from);
            relatedEdgeIds.add(edge.id);
        }
        if (edge.from === dsnId) {
            readerStepIds.push(edge.to);
            relatedEdgeIds.add(edge.id);
        }
    });
    const uniqueWriters = Array.from(new Set(writerStepIds));
    const uniqueReaders = Array.from(new Set(readerStepIds));

    const writerNames = uniqueWriters.map(function(sid){
        const s = stepDefs.find(function(x){ return x.id === sid; });
        return s ? '<span class="step-tag">' + escapeHtml(s.stepName) + '</span>' : '';
    }).join('');
    const readerNames = uniqueReaders.map(function(sid){
        const s = stepDefs.find(function(x){ return x.id === sid; });
        return s ? '<span class="step-tag">' + escapeHtml(s.stepName) + '</span>' : '';
    }).join('');

    document.getElementById('infoBar').classList.add('show-dsn');
    document.getElementById('dsnPhysical').textContent = dsnInfo.physicalName;
    const dcbParts = [];
    if (dsnInfo.lrecl) dcbParts.push('LRECL=' + dsnInfo.lrecl);
    if (dsnInfo.recfm) dcbParts.push('RECFM=' + dsnInfo.recfm);
    if (dsnInfo.blksize) dcbParts.push('BLKSIZE=' + dsnInfo.blksize);
    document.getElementById('dsnDcb').textContent = dcbParts.length
        ? dcbParts.join(', ')
        : (dsnInfo.dcb || '—');
    document.getElementById('dsnWriters').innerHTML = writerNames || '<span style="color:#999;">无</span>';
    document.getElementById('dsnReaders').innerHTML = readerNames || '<span style="color:#999;">无</span>';

    const keep = new Set([dsnId].concat(uniqueWriters).concat(uniqueReaders));
    const nUpdates = [];
    nodes.forEach(function(node){
        if (node._type === 'step') {
            nUpdates.push({ id: node.id, _faded: !keep.has(node.id) });
        } else {
            if (node.id === dsnId) {
                nUpdates.push({
                    id: node.id,
                    size: DSN_HIGHLIGHT_SIZE,
                    color: { background: HL_COLOR, border: HL_BORDER },
                    borderWidth: 2.5,
                    font: { color: '#ff0000', bold: true, size: 12, face: 'Consolas' },
                    labelPosition: DSN_LABEL_POS, labelOffset: DSN_LABEL_OFFSET
                });
            } else {
                nUpdates.push({
                    id: node.id, size: DSN_DEFAULT_SIZE,
                    color: { background: FADE_COLOR, border: FADE_BORDER },
                    borderWidth: 1,
                    font: { color: '#AAAAAA', size: 10, face: 'Consolas' },
                    labelPosition: DSN_LABEL_POS, labelOffset: DSN_LABEL_OFFSET
                });
            }
        }
    });
    nodes.update(nUpdates);

    const eUpdates = [];
    edges.forEach(function(edge){
        if (relatedEdgeIds.has(edge.id)) {
            eUpdates.push({
                id: edge.id,
                color: { color: EDGE_HL, highlight: EDGE_HL, hover: EDGE_HL, inherit: false },
                width: 2.8
            });
        } else {
            eUpdates.push({
                id: edge.id,
                color: { color: EDGE_FADE, highlight: EDGE_FADE, hover: EDGE_FADE, inherit: false },
                width: 1.0
            });
        }
    });
    edges.update(eUpdates);

    network.focus(dsnId, { scale: 1.2, animation: { duration: 400, easingFunction: 'easeInOutQuad' } });
}

// STEP 高亮：单击选中 STEP，高亮其关联 DSN 与连线，其余变灰，并放大聚焦
function highlightStep(stepId) {
    if (!network || !stepDefs.some(function(s){ return s.id === stepId; })) return;
    const keep = new Set([stepId]);
    const relatedEdgeIds = new Set();
    edges.forEach(function(edge){
        if (edge._kind === 'step') return;
        if (edge.from === stepId || edge.to === stepId) {
            relatedEdgeIds.add(edge.id);
            keep.add(edge.from);
            keep.add(edge.to);
        }
    });

    const nUpdates = [];
    nodes.forEach(function(node){
        if (node._type === 'step') {
            nUpdates.push({ id: node.id, _faded: !keep.has(node.id) });
        } else {
            if (keep.has(node.id)) {
                nUpdates.push(Object.assign({ id: node.id }, defaultNodeStyles[node.id]));
            } else {
                nUpdates.push({
                    id: node.id, size: DSN_DEFAULT_SIZE,
                    color: { background: FADE_COLOR, border: FADE_BORDER },
                    borderWidth: 1,
                    font: { color: '#AAAAAA', size: 10, face: 'Consolas' },
                    labelPosition: DSN_LABEL_POS, labelOffset: DSN_LABEL_OFFSET
                });
            }
        }
    });
    nodes.update(nUpdates);

    const eUpdates = [];
    edges.forEach(function(edge){
        if (relatedEdgeIds.has(edge.id)) {
            eUpdates.push({
                id: edge.id,
                color: { color: EDGE_HL, highlight: EDGE_HL, hover: EDGE_HL, inherit: false },
                width: 2.8
            });
        } else {
            eUpdates.push({
                id: edge.id,
                color: { color: EDGE_FADE, highlight: EDGE_FADE, hover: EDGE_FADE, inherit: false },
                width: 1.0
            });
        }
    });
    edges.update(eUpdates);

    network.focus(stepId, { scale: 1.2, animation: { duration: 400, easingFunction: 'easeInOutQuad' } });
}

// =========================================================
// 11. STEP 详情 Modal
// =========================================================
const modalOverlay = document.getElementById('stepModal');
const modalTitle = document.getElementById('modalTitle');
const modalBody = document.getElementById('modalBody');
document.getElementById('modalClose').addEventListener('click', closeModal);
modalOverlay.addEventListener('click', function(e){ if (e.target === modalOverlay) closeModal(); });
document.addEventListener('keydown', function(e){ if (e.key === 'Escape') closeModal(); });

function openStepModal(stepId) {
    const step = stepDefs.find(function(s){ return s.id === stepId; });
    if (!step) return;

    const readItems = [];
    const writeItems = [];
    edges.forEach(function(e){
        if (e._kind === 'write' && e.from === stepId) {
            writeItems.push({ dsnId: e.to, ddName: e._label || '' });
        } else if (e._kind === 'read' && e.to === stepId) {
            readItems.push({ dsnId: e.from, ddName: e._label || '' });
        }
    });

    const dedupe = function(items) {
        const seen = new Set();
        return items.filter(function(item){
            const key = item.dsnId + '|' + item.ddName;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
    };
    const read = dedupe(readItems);
    const write = dedupe(writeItems);

    const renderList = function(items) {
        if (!items.length) return '<div style="color:#999;">（无）</div>';
        return '<ul class="dsn-list">' + items.map(function(item){
            const info = dsnDefs[item.dsnId] || { physicalName: item.dsnId, logicalName: '' };
            const ddName = item.ddName || info.logicalName || '';
            return '<li>' + escapeHtml(info.physicalName) +
                '<span class="logical">' + escapeHtml(ddName) + '</span></li>';
        }).join('') + '</ul>';
    };

    const instreamItems = (step.dds || []).filter(function(d) {
        return d.instream || d.kind === 'INSTREAM';
    });
    const renderInstream = function() {
        if (!instreamItems.length) return '';
        return '<div class="info-row"><div class="k">流内数据</div><div class="v">' +
            instreamItems.map(function(d) {
                const data = d.instreamData || '';
                return '<div class="instream-block">' +
                    '<div class="instream-head">' + escapeHtml(d.ddName || 'DD') +
                    ' · ' + escapeHtml(d.parameters || '') + '</div>' +
                    (data
                        ? '<pre>' + escapeHtml(data) + '</pre>'
                        : '<div style="color:#999;padding:6px 8px;">（空）</div>') +
                    '</div>';
            }).join('') + '</div></div>';
    };

    modalTitle.textContent = step.stepName + (step.program ? ' · ' + step.program : '');
    modalBody.innerHTML =
        '<div class="info-row"><div class="k">STEP 名</div><div class="v">' + escapeHtml(step.stepName) + '</div></div>' +
        '<div class="info-row"><div class="k">程序</div><div class="v">' + escapeHtml(step.program || '') + '</div></div>' +
        (step.parm ? '<div class="info-row"><div class="k">PARM</div><div class="v">' + escapeHtml(step.parm) + '</div></div>' : '') +
        (step.execParameters ? '<div class="info-row"><div class="k">EXEC 参数</div><div class="v">' + escapeHtml(step.execParameters) + '</div></div>' : '') +
        (step.procName ? '<div class="info-row"><div class="k">来源 PROC</div><div class="v">' + escapeHtml(step.procName) + '</div></div>' : '') +
        '<div class="info-row"><div class="k">序号</div><div class="v">' + step.order + '</div></div>' +
        (step.line ? '<div class="info-row"><div class="k">源码行</div><div class="v">' + step.line + '</div></div>' : '') +
        '<div class="info-row"><div class="k"><span class="badge read">读</span></div><div class="v">' + renderList(read) + '</div></div>' +
        '<div class="info-row"><div class="k"><span class="badge write">写</span></div><div class="v">' + renderList(write) + '</div></div>' +
        renderInstream();
    modalOverlay.classList.add('active');
}
function closeModal() { modalOverlay.classList.remove('active'); }

// =========================================================
// 12. 工具栏
// =========================================================
document.getElementById('btnFit').addEventListener('click', toggleSidebar);
document.getElementById('jobSelect').addEventListener('change', function(e){
    const idx = parseInt(e.target.value, 10);
    if (!isNaN(idx) && allJobs[idx]) {
        currentJobIndex = idx;
        renderGraph(allJobs[idx]);
    }
});
document.getElementById('btnResetView').addEventListener('click', function(){
    if (lastGraph) {
        // 重置按钮：重新走一次层次布局，节点回到自动排布位置
        const graphToRender = lastGraph;
        showLoading('正在重新布局…');
        setTimeout(function() {
            try {
                renderGraph(graphToRender);
            } catch (err) {
                showErrorBar([{ line: 0, message: '重排失败：' + err.message, suggestion: '请刷新页面重试' }]);
                hideLoading();
            }
        }, 30);
    } else {
        resetStyles();
        if (network) network.fit({ animation: { duration: 500, easingFunction: 'easeInOutQuad' } });
    }
});
document.getElementById('backBtn').addEventListener('click', function(){
    resetStyles();
    if (network) network.fit({ animation: true });
});

document.getElementById('searchInput').addEventListener('keydown', function(e){
    if (e.key !== 'Enter') return;
    const q = e.target.value.trim().toUpperCase();
    if (!q || !network) return;
    let match = null;
    Object.keys(dsnDefs).forEach(function(id){
        const d = dsnDefs[id];
        if ((d.physicalName || '').toUpperCase().indexOf(q) >= 0 || (d.logicalName || '').toUpperCase().indexOf(q) >= 0) {
            match = id;
        }
    });
    if (match) {
        network.selectNodes([match]);
        highlightDsn(match);
    } else {
        e.target.style.borderColor = '#ef4444';
        setTimeout(function(){ e.target.style.borderColor = ''; }, 800);
    }
});

document.getElementById('btnExportPng').addEventListener('click', function(){
    if (!network) return;
    const canvas = document.querySelector('#mynetwork canvas');
    if (!canvas) return;

    // 记录当前视图 → fit 让全图完整显示 → 导出 → 恢复原视图，避免只截到可视区域
    const savePos = network.getViewPosition();
    const saveScale = network.getScale();
    network.fit({ animation: false });

    setTimeout(function(){
        const w = canvas.width, h = canvas.height;
        const out = document.createElement('canvas');
        out.width = w; out.height = h;
        const ctx = out.getContext('2d');
        ctx.fillStyle = '#fafbfc';
        ctx.fillRect(0, 0, w, h);
        ctx.drawImage(canvas, 0, 0);

        network.moveTo({ position: savePos, scale: saveScale, animation: false });

        const link = document.createElement('a');
        link.download = 'jcl-flow-' + Date.now() + '.png';
        link.href = out.toDataURL('image/png');
        link.click();
    }, 60);
});

// =========================================================
// 13. 侧栏收起
// =========================================================
function toggleSidebar() {
    const app = document.getElementById('app');
    const collapsed = app.classList.toggle('collapsed');
    document.getElementById('leftPanel').classList.toggle('collapsed', collapsed);
    document.getElementById('btnFit').textContent = collapsed ? '展开侧栏' : '收起侧栏';
    setTimeout(function(){
        editor.refresh();
        if (network) network.fit({ animation: false });
    }, 350);
}
document.getElementById('collapseBtn').addEventListener('click', toggleSidebar);

// =========================================================
// 14. 初始化
// =========================================================
// 初始不自动加载示例，保持空编辑器；只有点击“加载示例”才填入并解析
hideLoading();