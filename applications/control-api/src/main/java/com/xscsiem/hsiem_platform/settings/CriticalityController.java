package com.xscsiem.hsiem_platform.settings;

import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

/** 资产关键度设置 API(story-06):CRUD + 触发实体风险重算。 */
@RestController
@RequestMapping("/api/settings/criticality")
public class CriticalityController {

    private final CriticalityService service;
    private final CriticalityRecalcCoordinator recalc;

    public CriticalityController(CriticalityService service, CriticalityRecalcCoordinator recalc) {
        this.service = service;
        this.recalc = recalc;
    }

    /** 全量:按类型返回 {key: {level, weight}}。 */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> all() {
        return service.all();
    }

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> search(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String type,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String query,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int size) {
        return service.search(type, query, size);
    }

    /** 设置/更新某资产级别(PUT,level=low/medium/high/extreme)。 */
    @PutMapping("/{type}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> set(@PathVariable String type, @PathVariable String key,
                                   @RequestBody LevelRequest req) {
        Map<String, Object> result = service.set(type, key, req.level(), operator());
        result.put("recalcTaskId", recalc.enqueue(operator()));
        return result;
    }

    /** 删除某资产。 */
    @DeleteMapping("/{type}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable String type, @PathVariable String key) {
        service.delete(type, key, operator());
        return Map.of("deleted", type + "/" + key, "recalcTaskId", recalc.enqueue(operator()));
    }

    /** 批量导入：全部校验通过后原子替换并排队重算。 */
    @PostMapping("/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> batch(@RequestBody BatchRequest req) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(
                service.batch(req.items(), operator()));
        result.put("recalcTaskId", recalc.enqueue(operator()));
        return result;
    }

    /** 触发实体风险重算(entity-risk.py 读最新权重,后台任务可查询)。 */
    @PostMapping("/recalc")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recalc() {
        String taskId = recalc.enqueue(operator());
        return ResponseEntity.accepted().body(Map.of(
                "status", "queued", "taskId", taskId));
    }

    public record LevelRequest(String level) {
    }

    public record BatchRequest(List<CriticalityService.Entry> items) {
    }

    private String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName() == null ? "anonymous" : a.getName();
    }
}
