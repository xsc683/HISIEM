package com.xscsiem.hsiem_platform.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Story 02:模板保存 + 正负样本门禁(全过才允许保存)。 */
class ParserTemplateServiceTest {

    @TempDir
    Path temp;

    private ParserTemplate sshTemplate() {
        ParserTemplate t = new ParserTemplate();
        t.id = "ssh-test";
        t.patterns = List.of(
                "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}");
        ParserTemplate.Test test = new ParserTemplate.Test();
        test.sample = "Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20";
        test.expect = Map.of("user.name", "test", "source.ip", "172.16.1.20");
        t.tests = List.of(test);
        return t;
    }

    private ParserTemplateService svc() {
        return new ParserTemplateService(temp.toString(), new GrokTestService());
    }

    @Test
    void save_validTemplate_writesYaml() throws Exception {
        svc().save(sshTemplate());
        assertTrue(Files.exists(temp.resolve("ssh-test.yaml")));
    }

    @Test
    void save_negativeThatMatches_rejected() {
        ParserTemplate t = sshTemplate();
        // 该行与正样本同构,必然命中 → 门禁应拒绝
        t.negative = List.of("Aug 1 10:20:00 server03 sshd[9999]: Failed password for bad from 10.0.0.1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc().save(t));
        assertTrue(e.getMessage().contains("负样本"), "应报负样本命中: " + e.getMessage());
    }

    @Test
    void save_nonMatchingPositive_rejected() {
        ParserTemplate t = sshTemplate();
        t.tests.get(0).sample = "this is not a syslog ssh line at all";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc().save(t));
        assertTrue(e.getMessage().contains("正样本未命中"), "应报正样本未命中: " + e.getMessage());
    }

    @Test
    void save_expectMismatch_rejected() {
        ParserTemplate t = sshTemplate();
        t.tests.get(0).expect = Map.of("user.name", "WRONG");
        assertThrows(IllegalArgumentException.class, () -> svc().save(t));
    }

    @Test
    void save_noPatterns_rejected() {
        ParserTemplate t = sshTemplate();
        t.patterns = null;
        assertThrows(IllegalArgumentException.class, () -> svc().save(t));
    }

    @Test
    void save_noPositiveSamples_rejected() {
        ParserTemplate t = sshTemplate();
        t.tests = null;
        assertThrows(IllegalArgumentException.class, () -> svc().save(t));
    }
}
