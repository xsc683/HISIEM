package com.xscsiem.hsiem_platform.control;

/**
 * 控制面持久化边界(聚合视图,按有界上下文拆分为 {@link AuthStore}、{@link NotificationStore}、 {@link CaseStore}、{@link
 * LifecycleOutboxStore}、{@link TaskStore} 五个端口,本接口只是它们 的超集,便于 {@link MyBatisControlPlaneStore} 以单一
 * Spring bean 暴露全部分组)。事件、告警正文和 实体风险不进入这里,仍由 Elasticsearch 负责检索。生产运行由 MyBatis mapper 统一实现 ({@link
 * MyBatisControlPlaneStore});各领域服务应只依赖对应子端口而非本聚合接口。
 */
public interface ControlPlaneStore
        extends AuthStore, NotificationStore, CaseStore, LifecycleOutboxStore, TaskStore {}
