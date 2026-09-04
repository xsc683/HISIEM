package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 案件有界上下文的持久化端口：PostgreSQL 案件事实的 CRUD、告警关系与 ES 镜像 outbox。 由控制面 MyBatis 实现({@link
 * MyBatisControlPlaneStore})提供,是 {@link ControlPlaneStore} 的子面。
 *
 * <p>案件写路径的镜像机制是：事实变更在同一事务内先落 PostgreSQL 并由 {@code enqueueCaseMirror} 写入 outbox，再由 {@code
 * claimCaseMirrorBatch}/{@code completeCaseMirror} 投递给 Elasticsearch；业务正常写路径不应绕过此端口直接写 ES。
 */
public interface CaseStore {

    List<Map<String, Object>> listCases(String status, String entity, int size);

    /** 大屏使用的案件全量状态计数，避免把列表的前 200 条误当成总体。 */
    Map<String, Long> caseStatusCounts();

    Map<String, Object> findCase(String id);

    void createCase(Map<String, Object> document, List<String> alertIds);

    Map<String, Object> importCaseDocument(Map<String, Object> document);

    Map<String, Object> updateCase(
            String id, long expectedVersion, Map<String, Object> document, List<String> alertIds);

    boolean deleteCase(String id);

    /** 在案件事实源事务中写入 ES 镜像 outbox，避免删除/更新只成功一侧。 */
    void enqueueCaseMirror(String caseId, String operation, Map<String, Object> document);

    /** 由单个 dispatcher 持有租约领取待投递的镜像操作。 */
    List<Map<String, Object>> claimCaseMirrorBatch(String owner, Instant leaseUntil, int size);

    void completeCaseMirror(
            long id, String owner, boolean success, String error, Instant nextAttemptAt);

    boolean hasAlert(String alertId);
}
