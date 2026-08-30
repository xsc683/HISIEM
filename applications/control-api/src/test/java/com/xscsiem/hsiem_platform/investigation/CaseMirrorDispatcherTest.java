package com.xscsiem.hsiem_platform.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseMirrorDispatcherTest {

    @Test
    void delete_http200_completesOutboxInsteadOfRetrying() {
        ControlPlaneStore control = mock(ControlPlaneStore.class);
        ElasticsearchGateway elasticsearch = mock(ElasticsearchGateway.class);
        when(control.claimCaseMirrorBatch(any(), any(Instant.class), eq(50))).thenReturn(List.of(Map.of(
                "id", 7L,
                "caseId", "case-1",
                "operation", "delete",
                "attempts", 0)));
        when(elasticsearch.request("DELETE", "/siem-cases/_doc/case-1", null))
                .thenReturn(new ElasticsearchGateway.Response(200, Map.of("result", "deleted")));

        new CaseMirrorDispatcher(control, elasticsearch, new ObjectMapper(), Duration.ofMinutes(2)).dispatch();

        verify(control).completeCaseMirror(eq(7L), any(), eq(true), eq(null), any(Instant.class));
    }
}
