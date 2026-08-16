package com.xscsiem.hsiem_platform.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动聚合调度(story-07 FR-1):每 5min 扫描近 30min open 告警,
 * 按实体(source.ip 优先,退 user.name)聚类,组内 ≥2 条建案。
 * 幂等可重跑(已入他案告警跳过;已有同实体 open 案不再新建)。
 */
@Component
@EnableScheduling
public class CaseAggregateJob {

    private static final Logger log = LoggerFactory.getLogger(CaseAggregateJob.class);

    private final CaseService cases;
    private final int lookbackMinutes;

    public CaseAggregateJob(CaseService cases,
                            @Value("${app.case.aggregate-lookback-min:30}") int lookbackMinutes) {
        this.cases = cases;
        this.lookbackMinutes = lookbackMinutes;
    }

    /** 每 5min 轮询(cron 固定延迟,避免重入)。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void run() {
        try {
            int created = cases.aggregateAuto(lookbackMinutes);
            if (created > 0) {
                log.info("[CaseAggregateJob] 自动聚合新建 {} 个案件(近 {}min open 告警)", created, lookbackMinutes);
            }
        } catch (Exception e) {
            log.warn("[CaseAggregateJob] 轮询失败(下次重试): {}", e.getMessage());
        }
    }
}
