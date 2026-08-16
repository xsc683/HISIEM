package com.siem.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.io.Serializable;
import java.util.List;

/**
 * 检测规则声明(infra/rules/*.yaml,检测即代码的单一来源)。
 *
 * 四种类型(type):
 * - single_event:单事件条件匹配(condition)→ 复用 {@link com.siem.Rule}/{@link com.siem.DetectionFunction}
 * - window:窗口计数(keyField 分组,windowMinutes 窗口内 condition 命中数 ≥ threshold)→ 复用 {@link com.siem.WindowRule}
 * - cep:序列关联(cep.pattern)→ 构建 Flink CEP Pattern
 * - baseline:统计基线异常(baseline 参数)→ 复用 {@link com.siem.BaselineAnomalyFunction}
 *
 * enabled=false 时 Flink 启动不注册该规则(启停 = 改 enabled → deploy → 重启 job)。
 * 元数据字段(severity/riskScore/tags/status/version)供告警输出与覆盖度分析。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RuleDecl implements Serializable {

    public String id;
    public String name;
    /** 规则类别(分支依据):single_event / window / cep / baseline。 */
    public String category;
    /** 告警 type(如 ssh_authentication_failure / ssh_bruteforce_success),进 alert.type。 */
    public String type;
    /** 启停开关(默认 true;false 则不注册)。 */
    public boolean enabled = true;
    public String severity;
    public String description;
    public Integer riskScore;
    public List<String> tags;
    /** experimental / stable / deprecated。 */
    public String status;
    public String version;

    // ---- single_event / window:判定条件 ----
    public ConditionSpec condition;
    // ---- window:分组字段 + 窗口参数 ----
    public String keyField;
    public Long windowMinutes;
    public Integer threshold;
    // ---- cep:序列参数 ----
    public CepDecl cep;
    // ---- baseline:统计参数 ----
    public BaselineDecl baseline;

    // ---- 条件声明(field_equals / field_in / all / any / not) ----
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ConditionSpec implements Serializable {
        public String type;
        public String field;
        public Object value;
        public List<Object> values;
        public List<ConditionSpec> conditions;
    }

    // ---- CEP 序列声明 ----
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class CepDecl implements Serializable {
        /** 整个序列的时间上限(分钟)。 */
        public Long withinMinutes;
        /** 序列步骤(首个 begin,其余 next / followedBy)。 */
        public List<CepStep> pattern;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class CepStep implements Serializable {
        public String name;
        /** begin / next / followedBy。 */
        public String type;
        /** 该步重复次数(可选;如 failures 5-100 次)。 */
        public Integer timesMin;
        public Integer timesMax;
        public ConditionSpec condition;
    }

    // ---- 基线统计参数 ----
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class BaselineDecl implements Serializable {
        /** 分组字段(如 host.name)。 */
        public String keyField;
        /** 统计窗口(小时),默认 1。 */
        public Long windowHours = 1L;
        /** 滚动基线覆盖的小时数(如 24)。 */
        public Integer baselineHours;
        /** 基线最少样本小时数(低于则不判异常,如 3)。 */
        public Integer minBaselineHours;
    }
}
