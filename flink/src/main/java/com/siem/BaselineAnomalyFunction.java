package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基线异常检测(Phase 3.5):按主机统计每 1 小时认证失败数,与滚动基线(最近 baselineHours 个
 * 小时)比较,当前值 &gt; μ+3σ(且基线足够)时产出"认证失败率异常"告警。
 *
 * 用途:识别"认证失败率突增"(暴力破解加剧/异常扫描)这类规则写不死的统计型信号。
 * 说明:基线窗口可调大(如 30 天 → baselineHours 按小时计),误报可控场景才启用。
 */
public class BaselineAnomalyFunction extends ProcessWindowFunction<Event, String, String, TimeWindow> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int baselineHours;
    private final int minBaselineHours;
    /** 规则元数据(YAML 化后由声明注入,替代硬编码)。 */
    private final RuleMeta meta;
    private transient ValueState<LinkedList<Double>> baselineState;

    /** 默认元数据(历史测试用;生产走 YAML 加载的 RuleMeta)。 */
    public BaselineAnomalyFunction(int baselineHours, int minBaselineHours) {
        this(baselineHours, minBaselineHours, new RuleMeta(
                "rule-auth-rate-anomaly-001", "认证失败率异常(基线突增)", "auth_rate_anomaly",
                "high", "该主机认证失败数超出滚动基线(μ+3σ),疑似暴力破解加剧", 60,
                List.of("attack.t1110.001"), "experimental", "1.0"));
    }

    public BaselineAnomalyFunction(int baselineHours, int minBaselineHours, RuleMeta meta) {
        this.baselineHours = baselineHours;
        this.minBaselineHours = minBaselineHours;
        this.meta = meta;
    }

    @Override
    public void open(OpenContext openContext) {
        baselineState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("baseline", (Class) LinkedList.class));
    }

    @Override
    public void process(String key, Context ctx, Iterable<Event> events, Collector<String> out) throws Exception {
        long count = 0;
        for (Event e : events) {
            if (EventConditions.isAuthenticationFailure(e)) {
                count++;
            }
        }
        double current = count;

        LinkedList<Double> baseline = baselineState.value();
        if (baseline == null) {
            baseline = new LinkedList<>();
        }

        if (isAnomaly(baseline, current, minBaselineHours)) {
            double[] ms = meanSigma(baseline);
            out.collect(buildAlert(key, current, ms[0], ms[1], ms[0] + 3 * ms[1], ctx.window().getEnd()));
        }

        baseline.add(current);
        while (baseline.size() > baselineHours) {
            baseline.removeFirst();
        }
        baselineState.update(baseline);
    }

    /** 判定:基线足够且当前值超过 μ+3σ(阈值 >0 才有意义)。 */
    static boolean isAnomaly(List<Double> baseline, double current, int minBaselineHours) {
        if (baseline == null || baseline.size() < minBaselineHours) {
            return false;
        }
        double[] ms = meanSigma(baseline);
        double threshold = ms[0] + 3 * ms[1];
        return threshold > 0 && current > threshold;
    }

    /** 均值与标准差。 */
    static double[] meanSigma(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return new double[]{mean, Math.sqrt(variance)};
    }

    private String buildAlert(String host, double count, double mean, double sigma,
                              double threshold, long windowEnd) throws Exception {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("@timestamp", Instant.ofEpochMilli(windowEnd).toString());
        alert.put("alert.created_at", Instant.now().toString());
        alert.put("alert.status", "open");
        alert.put("alert.status_updated_at", Instant.now().toString());
        alert.put("alert.id", UUID.randomUUID().toString());
        alert.put("alert.rule_id", meta.id());
        alert.put("alert.rule_name", meta.name());
        alert.put("alert.type", meta.type());
        alert.put("alert.severity", meta.severity());
        alert.put("alert.risk_score", meta.riskScore());
        alert.put("alert.description", meta.description());
        alert.put("rule.tags", meta.tags());
        alert.put("rule.status", meta.status());
        alert.put("rule.version", meta.version());
        alert.put("host.name", host);
        alert.put("event.action", "authentication_failure");
        alert.put("event_count", (int) count);
        alert.put("anomaly.baseline_mean", Math.round(mean * 100.0) / 100.0);
        alert.put("anomaly.baseline_sigma", Math.round(sigma * 100.0) / 100.0);
        alert.put("anomaly.threshold", Math.round(threshold * 100.0) / 100.0);
        Ocsf.applyAuthView(alert, meta.severity());
        return MAPPER.writeValueAsString(alert);
    }
}
