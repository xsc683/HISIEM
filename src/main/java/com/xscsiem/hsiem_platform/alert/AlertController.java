package com.xscsiem.hsiem_platform.alert;

import com.xscsiem.hsiem_platform.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 告警三线处置 API(story-04,替代 triage-alert.py 的交互版)。 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService service;
    private final AuthService auth;

    public AlertController(AlertService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    /** 告警列表(open 默认,risk_score DESC)。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "100") int size) {
        return service.list(status, size);
    }

    /** 告警详情。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 三线流转(open→ack→investigating→resolved/closed)。 */
    @PostMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable String id, @RequestBody StatusRequest req,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.update(id, req.status(), null, operator(authHeader));
    }

    /** 打 verdict(true_positive/false_positive/duplicate)。 */
    @PostMapping("/{id}/verdict")
    public Map<String, Object> updateVerdict(@PathVariable String id, @RequestBody VerdictRequest req,
                                             @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.update(id, null, req.verdict(), operator(authHeader));
    }

    /** 批量状态(批量 close 前置已补 verdict)。 */
    @PostMapping("/batch-status")
    public Map<String, Object> batchStatus(@RequestBody BatchStatusRequest req,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.batch(req.ids(), req.status(), null, operator(authHeader));
    }

    /** 批量 verdict。 */
    @PostMapping("/batch-verdict")
    public Map<String, Object> batchVerdict(@RequestBody BatchVerdictRequest req,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.batch(req.ids(), null, req.verdict(), operator(authHeader));
    }

    /** 按规则 FP 率(FP/(TP+FP),>50% 高亮)。 */
    @GetMapping("/fp-rate")
    public List<Map<String, Object>> fpRate() {
        return service.fpRate();
    }

    private String operator(String authHeader) {
        try {
            String token = authHeader != null && authHeader.startsWith("Bearer ")
                    ? authHeader.substring(7) : "";
            return auth.me(token).username;
        } catch (Exception e) {
            return "anonymous";
        }
    }

    public record StatusRequest(String status) {
    }

    public record VerdictRequest(String verdict) {
    }

    public record BatchStatusRequest(List<String> ids, String status) {
    }

    public record BatchVerdictRequest(List<String> ids, String verdict) {
    }
}
