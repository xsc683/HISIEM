package com.xscsiem.hsiem_platform.settings;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 资产关键度(story-06):读写 asset-criticality.json、级别↔权重换算、增删。 */
class CriticalityServiceTest {

    @TempDir
    Path temp;

    private CriticalityService svc() {
        return new CriticalityService(temp.resolve("asset-criticality.json").toString());
    }

    private void writeSample() throws Exception {
        Files.writeString(temp.resolve("asset-criticality.json"), """
                {
                  "_comment": "test",
                  "ip": { "10.0.0.1": 2.0 },
                  "user": { "root": 1.5 },
                  "host": { "server01": 2.0 }
                }
                """);
    }

    @Test
    void all_mapsWeightToLevel() throws Exception {
        writeSample();
        Map<String, Object> all = svc().all();
        @SuppressWarnings("unchecked")
        Map<String, Object> ip = (Map<String, Object>) all.get("ip");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ip.get("10.0.0.1");
        assertEquals("extreme", item.get("level"));
        assertEquals(2.0, item.get("weight"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) all.get("user");
        assertEquals("high", ((Map<?, ?>) user.get("root")).get("level"));
    }

    @Test
    void set_updatesLevelAndPersists() throws Exception {
        writeSample();
        CriticalityService svc = svc();
        svc.set("ip", "10.0.0.1", "medium");
        Map<String, Object> item = svc.set("user", "bob", "extreme");
        assertEquals("extreme", item.get("level"));
        assertEquals(2.0, item.get("weight"));
        // 重读确认持久化 + 新增
        @SuppressWarnings("unchecked")
        Map<String, Object> all = (Map<String, Object>) svc.all();
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) all.get("user");
        assertEquals("extreme", ((Map<?, ?>) user.get("bob")).get("level"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ip = (Map<String, Object>) all.get("ip");
        assertEquals("medium", ((Map<?, ?>) ip.get("10.0.0.1")).get("level"));
    }

    @Test
    void delete_removesAsset() throws Exception {
        writeSample();
        CriticalityService svc = svc();
        svc.delete("ip", "10.0.0.1");
        assertThrows(NotFoundException.class, () -> svc.delete("ip", "10.0.0.1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ip = (Map<String, Object>) svc.all().get("ip");
        assertEquals(0, ip.size());
    }

    @Test
    void set_invalidLevel_throws() throws Exception {
        writeSample();
        assertThrows(IllegalArgumentException.class, () -> svc().set("ip", "1.1.1.1", "extreme2"));
        assertThrows(IllegalArgumentException.class, () -> svc().set("bogus", "x", "high"));
    }

    @Test
    void set_missingFile_creates() throws Exception {
        CriticalityService svc = svc();
        svc.set("host", "web-01", "high");
        Map<String, Object> all = svc.all();
        @SuppressWarnings("unchecked")
        Map<String, Object> host = (Map<String, Object>) all.get("host");
        assertEquals("high", ((Map<?, ?>) host.get("web-01")).get("level"));
    }
}
