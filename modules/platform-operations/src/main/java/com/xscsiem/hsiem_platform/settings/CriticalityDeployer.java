package com.xscsiem.hsiem_platform.settings;

/** 资产关键度保存后触发实体风险重算(运行 entity-risk.py)。 */
public interface CriticalityDeployer {

    /** 运行 infra/elasticsearch/entity-risk.py 重算实体风险分,返回输出摘要。 */
    String recalcEntityRisk();
}
