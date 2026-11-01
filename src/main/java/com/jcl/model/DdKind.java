package com.jcl.model;

/**
 * DD 语句的数据集/资源类型。
 * <p>
 * {@link #DATA}、{@link #TEMPORARY}、{@link #GDG}、{@link #NULLFILE} 会生成图中的 DSN 节点；
 * SYSOUT / DUMMY / INSTREAM / LIBRARY 属于“非数据流”DD，保留在 STEP 详情中，但不污染数据流图。
 */
public enum DdKind {
    /** 普通数据集 */
    DATA,
    /** 临时数据集（&& 开头或隐式临时集） */
    TEMPORARY,
    /** GDG（按 base 名合并为一个节点） */
    GDG,
    /** DSN=NULLFILE，丢弃输出，作为特殊汇点节点 */
    NULLFILE,
    /** 对其它 STEP/DD 的引用（DSN=*.STEP.DD） */
    REFERENCE,
    /** SYSOUT 输出 */
    SYSOUT,
    /** DUMMY 哑元（NULLFILE 之外的 DUMMY） */
    DUMMY,
    /** DD * / DD DATA 流内数据 */
    INSTREAM,
    /** JOBLIB / STEPLIB 等库 */
    LIBRARY
}
