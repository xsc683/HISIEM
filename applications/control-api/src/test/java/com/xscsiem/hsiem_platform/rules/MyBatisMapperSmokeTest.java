package com.xscsiem.hsiem_platform.rules;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies the managed mapper is wired against the migrated H2 schema. */
@SpringBootTest
class MyBatisMapperSmokeTest {
    @Autowired private ManagedDetectionMapper mapper;

    @Test
    void mapperLoadsExplicitQueriesAgainstH2() {
        assertNull(mapper.findRevision(UUID.randomUUID(), "missing-rule"));
        assertNull(mapper.findPlan(UUID.randomUUID(), DetectionPlanCompiler.VERSION));
        assertNull(mapper.findDeployment("missing-tenant", "missing-rule"));
    }
}
