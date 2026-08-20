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

import java.util.Map;

/** 资产关键度设置 API(story-06):CRUD + 触发实体风险重算。 */
@RestController
@RequestMapping("/api/settings/criticality")
public class CriticalityController {

    private final CriticalityService service;
    private final CriticalityDeployer deployer;
    private final NotificationService notify;

    public CriticalityController(CriticalityService service, CriticalityDeployer deployer,
                                 NotificationService notify) {
        this.service = service;
        this.deployer = deployer;
        this.notify = notify;
    }

    /** 全量:按类型返回 {key: {level, weight}}。 */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> all() {
        return service.all();
    }

    /** 设置/更新某资产级别(PUT,level=low/medium/high/extreme)。 */
    @PutMapping("/{type}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> set(@PathVariable String type, @PathVariable String key,
                                   @RequestBody LevelRequest req) {
        return service.set(type, key, req.level());
    }

    /** 删除某资产。 */
    @DeleteMapping("/{type}/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String type, @PathVariable String key) {
        service.delete(type, key);
    }

    /** 触发实体风险重算(entity-risk.py 读最新权重,约数秒)。 */
    @PostMapping("/recalc")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recalc() {
        String output = deployer.recalcEntityRisk();
        notify.notify("criticality", "entity-risk", "实体风险已按最新资产关键度重算");
        return ResponseEntity.accepted().body(Map.of(
                "status", "recalculated", "output", output));
    }

    public record LevelRequest(String level) {
    }
}
