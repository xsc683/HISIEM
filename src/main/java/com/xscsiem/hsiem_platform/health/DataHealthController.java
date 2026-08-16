package com.xscsiem.hsiem_platform.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 数据源健康 API(story-05):每源健康指标 / 趋势 / 失败日志下钻。 */
@RestController
@RequestMapping("/api/data-health")
public class DataHealthController {

    private final DataHealthService service;

    public DataHealthController(DataHealthService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<Map<String, Object>> sources() {
        return service.sources();
    }

    @GetMapping("/sources/{id}/trend")
    public List<Map<String, Object>> trend(@PathVariable String id) {
        return service.trend(id);
    }

    @GetMapping("/sources/{id}/failures")
    public List<Map<String, Object>> failures(@PathVariable String id,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.failures(id, size);
    }
}
