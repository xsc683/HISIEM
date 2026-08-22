package com.siem;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperationVariant;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionJobSinkTest {

    @Test
    void alertOperationUsesPartialUpdateAndPreservesAnalystFields() {
        String alert = "{\"@timestamp\":\"2026-08-22T10:00:00Z\","
                + "\"alert.rule_id\":\"rule-1\",\"source.ip\":\"192.0.2.10\","
                + "\"alert.status\":\"open\",\"alert.analyst_verdict\":\"true_positive\","
                + "\"alert.risk_score\":80}";

        BulkOperationVariant variant = DetectionJob.alertOperation(alert);
        assertEquals(BulkOperation.Kind.Update, variant._bulkOperationKind());
        UpdateOperation<?, ?> update = variant._toBulkOperation().update();
        assertTrue(Boolean.TRUE.equals(update.action().docAsUpsert()));
        assertTrue(update.action().upsert().toString().contains("analyst_verdict"));
        assertTrue(!update.action().doc().toString().contains("analyst_verdict"));
        assertTrue(!update.action().doc().toString().contains("alert.status"));
    }
}
