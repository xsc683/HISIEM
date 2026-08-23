package com.siem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        assertTrue(!Boolean.TRUE.equals(update.action().docAsUpsert()));
        assertTrue(update.action().upsert().toString().contains("analyst_verdict"));
        assertTrue(!update.action().doc().toString().contains("analyst_verdict"));
        assertTrue(!update.action().doc().toString().contains("alert.status"));
    }

    @Test
    void orderedHttpUpdateBodyAlsoPreservesAnalystFields() throws Exception {
        String alert = "{\"@timestamp\":\"2026-08-22T10:00:00Z\","
                + "\"alert.rule_id\":\"rule-1\",\"source.ip\":\"192.0.2.10\","
                + "\"alert.status\":\"open\",\"alert.analyst_verdict\":\"true_positive\"}";
        MapView body = MapView.parse(DetectionJob.alertUpdateBody(alert));
        assertTrue(body.upsert().containsKey("alert.analyst_verdict"));
        assertTrue(!body.doc().containsKey("alert.analyst_verdict"));
        assertTrue(!body.doc().containsKey("alert.status"));
        assertTrue(!body.docAsUpsert());
    }

    @Test
    void lifecycleContractUsesStableEsIdAndNestedPayload() throws Exception {
        String alert = "{\"@timestamp\":\"2026-08-22T10:00:00Z\","
                + "\"alert.id\":\"display-random\",\"alert.rule_id\":\"rule-1\","
                + "\"alert.severity\":\"critical\",\"alert.risk_score\":88,"
                + "\"source.ip\":\"192.0.2.10\"}";
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> first = mapper.readValue(AlertLifecycleEventMapper.map(alert), new TypeReference<>() { });
        java.util.Map<String, Object> second = mapper.readValue(AlertLifecycleEventMapper.map(alert), new TypeReference<>() { });
        assertEquals("alert.created", first.get("event_type"));
        assertEquals(first.get("message_id"), second.get("message_id"));
        java.util.Map<?, ?> nested = (java.util.Map<?, ?>) first.get("alert");
        assertEquals(DetectionJob.alertId(alert), nested.get("id"));
        assertEquals(88, nested.get("risk_score"));
    }

    private record MapView(java.util.Map<String, Object> doc, java.util.Map<String, Object> upsert,
                           boolean docAsUpsert) {
        static MapView parse(String json) throws Exception {
            java.util.Map<String, Object> value = new ObjectMapper().readValue(json, new TypeReference<>() { });
            return new MapView((java.util.Map<String, Object>) value.get("doc"),
                    (java.util.Map<String, Object>) value.get("upsert"),
                    Boolean.TRUE.equals(value.get("doc_as_upsert")));
        }
    }
}
