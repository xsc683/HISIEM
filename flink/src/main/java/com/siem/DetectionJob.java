package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siem.config.RuleBuilder;
import com.siem.config.RuleConfigLoader;
import com.siem.config.RuleDecl;
import com.siem.config.RuntimeTuning;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaSinkBuilder;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import co.elastic.clients.elasticsearch.core.bulk.UpdateAction;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperationVariant;
import co.elastic.clients.json.JsonData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;


/**
 * SIEM 检测作业(规则驱动)。
 *
 * 规则从 infra/rules/*.yaml 加载(检测即代码单一来源),按 type 分四类分支:
 * - single_event → DetectionFunction(单事件条件匹配)+ AlertSuppressor(抑制)
 * - window → WindowRuleFunction(窗口计数)
 * - cep → CEP Pattern + BruteforceSuccessFunction(攻击链序列)
 * - baseline → BaselineAnomalyFunction(统计基线异常)
 * enabled=false 的规则不注册(启停 = 改 YAML enabled → deploy → 重启 job)。
 *
 * 规则目录解析:args[0] > 环境变量 SIEM_RULES_DIR > 默认 /opt/flink/rules(由 deploy.sh 同步)。
 */
public class DetectionJob {

    private static final ObjectMapper ALERT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_CHECKPOINT_ROOT = "file:///opt/flink/checkpoints";
    private static final String DEFAULT_SAVEPOINT_ROOT = "file:///opt/flink/savepoints";

    /** Fixed, machine-parseable name shared with the process runtime adapter. */
    public static String structuredJobName(DetectionJobArguments arguments) {
        if (arguments == null || arguments.legacy()) {
            return "SIEM Detection Engine";
        }
        return "SIEM-DETECTION-" + arguments.jobKey() + "-g" + arguments.generation()
                + "-m" + arguments.manifestHash();
    }

    public static void main(String[] args) throws Exception {

        DetectionJobArguments arguments = DetectionJobArguments.parse(args);
        String rulesDir = arguments.rulesDir();

        // Each managed job gets an isolated state path.  Legacy launches retain the historical
        // shared path until they are redeployed with the typed 5B arguments.
        String checkpointRoot = System.getenv().getOrDefault("SIEM_CHECKPOINT_ROOT", DEFAULT_CHECKPOINT_ROOT);
        String savepointRoot = System.getenv().getOrDefault("SIEM_SAVEPOINT_ROOT", DEFAULT_SAVEPOINT_ROOT);
        String stateSuffix = arguments.managed() ? "/" + arguments.jobKey() : "";

        // checkpoint/savepoint 落到 Docker 挂载的持久卷,按 jobKey 隔离 managed jobs。
        Configuration conf = new Configuration();
        conf.setString("state.checkpoints.dir", appendPath(checkpointRoot, stateSuffix));
        conf.setString("execution.checkpointing.savepoint-dir", appendPath(savepointRoot, stateSuffix));
        // 重启策略(Phase 3.0-F3):exponential-delay,Flink 2.x 通过 Configuration 选项配置
        // (旧版 RestartStrategies 工厂已移除)。
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "exponential-delay");
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_INITIAL_BACKOFF, Duration.ofSeconds(5));
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_MAX_BACKOFF, Duration.ofMinutes(2));
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_BACKOFF_MULTIPLIER, 1.5);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_JITTER_FACTOR, 0.1);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_EXPONENTIAL_DELAY_ATTEMPTS, 10);

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(conf);
        RuntimeTuning tuning = RuntimeTuning.fromEnvironment();

        // 并行度(Phase 3.0-K1):与 2-slot taskmanager 匹配,
        // KafkaSource 以 2 个并行消费者分摊 3 分区的 siem-events。
        env.setParallelism(2);

        // 可靠性基线(Phase 3.0-F3):
        // 1) EXACTLY_ONCE + 明确 timeout/min-pause/max-concurrent,避免 checkpoint 风暴;
        // 2) tolerable-failed-checkpoints=3,单次失败不杀 job。
        env.enableCheckpointing(tuning.checkpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setCheckpointTimeout(tuning.checkpointTimeoutMs());
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(tuning.minPauseBetweenCheckpointsMs());
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().setTolerableCheckpointFailureNumber(tuning.tolerableCheckpointFailures());
        System.out.println("[DetectionJob] runtime tuning=" + tuning);

        // 规则加载(检测即代码):解析目录,enabled 才注册
        RuleConfigLoader loader = new RuleConfigLoader();
        List<RuleDecl> decls = loader.loadDir(rulesDir);
        new RuntimeManifestVerifier().verify(Path.of(rulesDir), arguments, decls);
        List<RuleDecl> enabled = decls.stream().filter(d -> d.enabled).toList();
        RuleBuilder builder = new RuleBuilder();
        System.out.println("[DetectionJob] 加载规则目录 " + rulesDir
                + ": 共 " + decls.size() + " 条,enabled " + enabled.size() + " 条");


        String kafkaBootstrap = System.getenv().getOrDefault("SIEM_KAFKA_BOOTSTRAP", "kafka:9092");
        KafkaSourceBuilder<String> sourceBuilder = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaBootstrap)
                .setTopics("siem-events")
                .setGroupId(arguments.managed() ? "siem-detection-" + arguments.jobKey()
                        : "siem-detection")
                .setStartingOffsets(
                        // 从已提交的 group offset 恢复;首次运行(无已提交 offset)回退到 earliest
                        OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST)
                )
                .setValueOnlyDeserializer(new SimpleStringSchema());
        applyKafkaSecurity(sourceBuilder);
        KafkaSource<String> source = sourceBuilder.build();


        DataStream<String> events =
                env.fromSource(
                                source,
                                WatermarkStrategy.noWatermarks(),
                                "siem-events-source"
                        )
                        .uid("kafka-source");

        // 解析事件:扁平字段 + 事件时间戳(供事件时间窗口使用)
        SingleOutputStreamOperator<Event> parsed = events
                .process(new EventParsingProcessFunction())
                .uid("event-parser");
        KafkaSinkBuilder<String> dlqSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaBootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(System.getenv().getOrDefault("SIEM_EVENTS_DLQ_TOPIC", "siem-events-dlq"))
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        applyKafkaSecurity(dlqSink);
        parsed.getSideOutput(EventParsingProcessFunction.DLQ)
                .sinkTo(dlqSink.build())
                .uid("event-parser-dlq-kafka");

        // 共享事件时间流:窗口/CEP/基线三个分支共用同一 watermark(有界乱序 10s + idle 60s)
        DataStream<Event> parsedTimed = parsed
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                                .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
                                // 日志暂停(SSH 突发写入)时推进 watermark,窗口仍能按时关闭(Phase 3.1-F5)
                                .withIdleness(Duration.ofSeconds(60))
                )
                .uid("window-watermark");


        /*
         * 1) 单事件规则:逐规则匹配 → 告警 JSON → 抑制(同一规则+实体窗口内只发一条)。
         */
        List<RuleDecl> singleDecls = enabled.stream()
                .filter(d -> "single_event".equals(d.category)).toList();
        List<Rule> singleRules = singleDecls.stream().map(builder::toRule).toList();
        long singleSuppressionMinutes = singleEventSuppressionMinutes(singleDecls);
        DataStream<String> singleAlerts = parsed
                .flatMap(new DetectionFunction(new RuleRegistry(singleRules)))
                .uid("single-event-detection")
                .keyBy(AlertSuppressor::suppressionKey)
                .process(new AlertSuppressor(Duration.ofMinutes(singleSuppressionMinutes)))
                .uid("alert-suppression");


        /*
         * 2) 时间窗口规则:keyField 分组,windowMinutes 窗口内 condition 命中数 >= threshold。
         *    例:同一源 IP 5 分钟内 authentication_failure >= 5 次 → critical 告警。
         */
        List<DataStream<String>> windowStreams = new ArrayList<>();
        for (RuleDecl d : enabled.stream().filter(x -> "window".equals(x.category)).toList()) {
            WindowRule wr = builder.toWindowRule(d);
            // F7:slidingMinutes > 0 → 滑动窗口(5min/步长1min 修边界盲区);否则 tumbling 固定窗口
            KeyedStream<Event, String> keyed = parsedTimed.keyBy(e -> String.valueOf(
                    e.getFields().getOrDefault(wr.getKeyField(), "unknown")));
            SingleOutputStreamOperator<String> windowed;
            if (wr.getSlidingMinutes() != null && wr.getSlidingMinutes() > 0) {
                windowed = keyed.window(SlidingEventTimeWindows.of(
                                Duration.ofMinutes(wr.getWindowMinutes()),
                                Duration.ofMinutes(wr.getSlidingMinutes())))
                        .process(new WindowRuleFunction(wr));
            } else {
                windowed = keyed.window(TumblingEventTimeWindows.of(Duration.ofMinutes(wr.getWindowMinutes())))
                        .process(new WindowRuleFunction(wr));
            }
            // 滑动窗口会对同一批事件产生多个相邻窗口命中;按规则+实体抑制,
            // 保留边界覆盖能力,同时避免 ES 中为同一活跃攻击创建重复告警文档。
            windowed.uid("window-" + wr.getId());
            windowStreams.add(windowed
                    .keyBy(alert -> WindowAlertSuppressor.suppressionKey(alert, wr.getKeyField()))
                    .process(new WindowAlertSuppressor(
                            Duration.ofMinutes(wr.getAlertSuppressionMinutes())))
                    .uid("window-alert-suppression-" + wr.getId()));
        }
        DataStream<String> windowAlerts = windowStreams.isEmpty()
                ? null : windowStreams.stream().reduce((a, b) -> a.union(b)).get();


        /*
         * 3) CEP 攻击链规则:同 keyField 事件时间窗内按 pattern 序列匹配。
         *    例:10 分钟内 ≥5 次认证失败 → 随后 1 次成功登录 = 暴力破解成功。
         */
        List<DataStream<String>> cepStreams = new ArrayList<>();
        for (RuleDecl d : enabled.stream().filter(x -> "cep".equals(x.category)).toList()) {
            RuleMeta meta = builder.toMeta(d);
            cepStreams.add(CEP.pattern(
                            parsedTimed.keyBy(e -> String.valueOf(
                                    e.getFields().getOrDefault(d.keyField, "unknown"))),
                            buildCepPattern(d.cep))
                    .process(new BruteforceSuccessFunction(
                            meta.id(), meta.name(), meta.type(), meta.severity(),
                            meta.description(), meta.riskScore(), meta.tags(), meta.status(),
                            meta.version(), d.cep.failureStep, d.cep.successStep))
                    .uid("cep-" + d.id));
        }
        DataStream<String> cepAlerts = cepStreams.isEmpty()
                ? null : cepStreams.stream().reduce((a, b) -> a.union(b)).get();


        /*
         * 4) 基线异常:按 keyField 统计每 windowHours 小时命中数,与滚动基线比较,
         *    当前值 > μ+3σ 且基线足够时产出告警。
         */
        List<DataStream<String>> anomalyStreams = new ArrayList<>();
        for (RuleDecl d : enabled.stream().filter(x -> "baseline".equals(x.category)).toList()) {
            RuleDecl.BaselineDecl b = d.baseline;
            if (b == null || b.baselineHours == null || b.minBaselineHours == null) {
                throw new IllegalArgumentException("baseline 规则缺少 baseline 参数: " + d.id);
            }
            RuleMeta meta = builder.toMeta(d);
            long windowHours = b.windowHours == null ? 1L : b.windowHours;
            double sigmaMultiplier = b.sigmaMultiplier == null ? 3.0d : b.sigmaMultiplier;
            Condition baselineCondition = d.condition == null
                    ? new FieldEqualsCondition("event.action", "authentication_failure")
                    : builder.buildCondition(d.condition);
            anomalyStreams.add(parsedTimed
                    .keyBy(e -> String.valueOf(
                            e.getFields().getOrDefault(b.keyField, "unknown")))
                    .window(TumblingEventTimeWindows.of(Duration.ofHours(windowHours)))
                    .process(new BaselineAnomalyFunction(b.baselineHours, b.minBaselineHours,
                            baselineCondition, sigmaMultiplier, meta))
                    .uid("baseline-" + d.id));
        }
        DataStream<String> anomalyAlerts = anomalyStreams.isEmpty()
                ? null : anomalyStreams.stream().reduce((a, b) -> a.union(b)).get();


        List<DataStream<String>> allAlerts = new ArrayList<>();
        allAlerts.add(singleAlerts);
        if (windowAlerts != null) {
            allAlerts.add(windowAlerts);
        }
        if (cepAlerts != null) {
            allAlerts.add(cepAlerts);
        }
        if (anomalyAlerts != null) {
            allAlerts.add(anomalyAlerts);
        }
        DataStream<String> alerts = allAlerts.stream().reduce((a, b) -> a.union(b)).get();

        alerts.print();
        String esUrl = System.getenv().getOrDefault("SIEM_ES_URL", "http://siem-elasticsearch:9200");
        String esUser = System.getenv("SIEM_ES_USERNAME");
        String esPassword = System.getenv().getOrDefault("SIEM_ES_PASSWORD", "");
        DataStream<String> indexedAlerts = AsyncDataStream.unorderedWait(
                        alerts, new AlertElasticsearchIndexer(esUrl, esUser, esPassword),
                        30, TimeUnit.SECONDS, tuning.esMaxBufferedRequests())
                .uid("es-index-and-forward");

        KafkaSinkBuilder<String> lifecycleSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaBootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(System.getenv().getOrDefault(
                                "SIEM_ALERT_LIFECYCLE_TOPIC", "siem-alert-lifecycle"))
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        applyKafkaSecurity(lifecycleSink);
        indexedAlerts.map(AlertLifecycleEventMapper::map)
                .uid("alert-lifecycle-contract")
                .sinkTo(lifecycleSink.build())
                .uid("alert-lifecycle-kafka");


        env.execute(structuredJobName(arguments));
    }

    static long singleEventSuppressionMinutes(List<RuleDecl> declarations) {
        long selected = 60L;
        boolean explicit = false;
        for (RuleDecl declaration : declarations) {
            if (declaration.alertSuppressionMinutes == null) {
                continue;
            }
            long value = declaration.alertSuppressionMinutes;
            if (value <= 0) {
                throw new IllegalArgumentException("single_event alertSuppressionMinutes 必须 > 0");
            }
            if (explicit && selected != value) {
                throw new IllegalArgumentException("同一 Job Group 的 single_event 抑制时长必须一致");
            }
            selected = value;
            explicit = true;
        }
        return selected;
    }

    private static String appendPath(String root, String suffix) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("state path root must not be blank");
        }
        if (suffix == null || suffix.isEmpty()) return root;
        return root.replaceAll("/+$", "") + "/" + suffix.replaceAll("^/+", "");
    }

    private static void applyKafkaSecurity(KafkaSourceBuilder<?> builder) {
        Map<String, String> env = System.getenv();
        String protocol = env.get("SIEM_KAFKA_SECURITY_PROTOCOL");
        if (protocol == null || protocol.isBlank()) return;
        builder.setProperty("security.protocol", protocol);
        setKafkaProperty(builder, "sasl.mechanism", env.get("SIEM_KAFKA_SASL_MECHANISM"));
        setKafkaProperty(builder, "sasl.jaas.config", env.get("SIEM_KAFKA_SASL_JAAS_CONFIG"));
        setKafkaProperty(builder, "ssl.truststore.location", env.get("SIEM_KAFKA_SSL_TRUSTSTORE_LOCATION"));
        setKafkaProperty(builder, "ssl.truststore.password", env.get("SIEM_KAFKA_SSL_TRUSTSTORE_PASSWORD"));
    }

    private static void setKafkaProperty(KafkaSourceBuilder<?> builder, String key, String value) {
        if (value != null && !value.isBlank()) builder.setProperty(key, value);
    }

    private static void applyKafkaSecurity(KafkaSinkBuilder<?> builder) {
        Map<String, String> env = System.getenv();
        setKafkaProperty(builder, "security.protocol", env.get("SIEM_KAFKA_SECURITY_PROTOCOL"));
        setKafkaProperty(builder, "sasl.mechanism", env.get("SIEM_KAFKA_SASL_MECHANISM"));
        setKafkaProperty(builder, "sasl.jaas.config", env.get("SIEM_KAFKA_SASL_JAAS_CONFIG"));
        setKafkaProperty(builder, "ssl.truststore.location", env.get("SIEM_KAFKA_SSL_TRUSTSTORE_LOCATION"));
        setKafkaProperty(builder, "ssl.truststore.password", env.get("SIEM_KAFKA_SSL_TRUSTSTORE_PASSWORD"));
    }

    private static void setKafkaProperty(KafkaSinkBuilder<?> builder, String key, String value) {
        if (value != null && !value.isBlank()) builder.setProperty(key, value);
    }

    /**
     * 告警检测字段使用部分更新，避免 suppression timer 或重放覆盖分析师处置字段。
     * 新告警通过 upsert 保留完整文档；已有告警只更新检测侧字段。
     */
    static BulkOperationVariant alertOperation(String element) {
        try {
            Map<String, Object> full = ALERT_MAPPER.readValue(element, Map.class);
            Map<String, Object> patch = new HashMap<>(full);
            Set<String> protectedFields = Set.of(
                    "alert.status", "alert.analyst_verdict", "alert.operator",
                    "alert.status_updated_at", "alert.case_id");
            protectedFields.forEach(patch::remove);
            JsonData fullDoc = JsonData.fromJson(element);
            JsonData partialDoc = JsonData.fromJson(ALERT_MAPPER.writeValueAsString(patch));
            return new UpdateOperation.Builder<JsonData, JsonData>()
                            .index("siem-alerts")
                            .id(alertId(element))
                            .action(new UpdateAction.Builder<JsonData, JsonData>()
                                    .doc(partialDoc)
                                    .upsert(fullDoc)
                                    .build())
                            .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("告警 JSON 无法生成 ES 更新操作", e);
        }
    }

    static String alertUpdateBody(String element) {
        try {
            Map<String, Object> full = ALERT_MAPPER.readValue(element, Map.class);
            Map<String, Object> patch = new HashMap<>(full);
            Set<String> protectedFields = Set.of(
                    "alert.status", "alert.analyst_verdict", "alert.operator",
                    "alert.status_updated_at", "alert.case_id");
            protectedFields.forEach(patch::remove);
            return ALERT_MAPPER.writeValueAsString(Map.of(
                    "doc", patch, "upsert", full));
        } catch (Exception e) {
            throw new IllegalArgumentException("告警 JSON 无法生成 ES 更新请求", e);
        }
    }

    /** 构建 CEP 序列 Pattern:首个 begin 步骤 + 后续 next/followedBy 步骤,整体 within。 */
    private static Pattern<Event, ?> buildCepPattern(RuleDecl.CepDecl cep) {
        if (cep == null || cep.pattern == null || cep.pattern.isEmpty()) {
            throw new IllegalArgumentException("cep 规则缺少 pattern");
        }
        Pattern<Event, ?> p = null;
        for (RuleDecl.CepStep step : cep.pattern) {
            SimpleCondition<Event> cond = SimpleCondition.of(
                    (FilterFunction<Event>) e ->
                            RuleBuilder.buildCondition(step.condition).matches(e.getFields()));
            if ("begin".equals(step.type)) {
                Pattern<Event, ?> begin = Pattern.<Event>begin(step.name).where(cond);
                if (step.timesMax != null) {
                    int min = step.timesMin == null ? step.timesMax : step.timesMin;
                    begin = begin.times(min, step.timesMax);
                } else if (step.timesMin != null) {
                    begin = begin.times(step.timesMin);
                }
                p = begin;
            } else if ("next".equals(step.type)) {
                p = p.next(step.name).where(cond);
            } else if ("followedBy".equals(step.type)) {
                p = p.followedBy(step.name).where(cond);
            } else {
                throw new IllegalArgumentException("未知 CEP 步骤类型: " + step.type);
            }
        }
        if (p != null && cep.withinMinutes != null) {
            p = p.within(Duration.ofMinutes(cep.withinMinutes));
        }
        return p;
    }

    /**
     * 计算告警的确定性 _id:sha1(rule_id + 实体 + 事件时间)。
     * 同一事件被重放时计算得到相同 _id,ES 写入变为幂等覆盖,避免重复告警。
     */
    static String alertId(String element) {
        try {
            Map<String, Object> alert = ALERT_MAPPER.readValue(element, Map.class);
            String ruleId = String.valueOf(alert.getOrDefault("alert.rule_id", "unknown"));
            Object declaredEntity = alert.get("alert.entity");
            Object ip = alert.get("source.ip");
            Object user = alert.get("user.name");
            String entity = declaredEntity != null ? String.valueOf(declaredEntity)
                    : (ip != null ? String.valueOf(ip)
                    : (user != null ? String.valueOf(user) : "unknown"));
            String ts = String.valueOf(alert.getOrDefault("@timestamp", "unknown"));
            return sha1Hex(ruleId + "|" + entity + "|" + ts);
        } catch (Exception e) {
            return sha1Hex(element);
        }
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
