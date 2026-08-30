package com.xscsiem.hsiem_platform.investigation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduling boundary for the optional control-plane-to-ES case mirror. */
@Component
@ConditionalOnProperty(name = "app.operations.runtime-enabled", havingValue = "true", matchIfMissing = true)
public class CaseMirrorReconcileJob {

    private final CaseService cases;

    public CaseMirrorReconcileJob(CaseService cases) {
        this.cases = cases;
    }

    @Scheduled(initialDelayString = "${app.cases.mirror-reconcile-initial-delay-ms:120000}",
            fixedDelayString = "${app.cases.mirror-reconcile-interval-ms:300000}")
    public void reconcile() {
        cases.reconcileMirror();
    }
}
