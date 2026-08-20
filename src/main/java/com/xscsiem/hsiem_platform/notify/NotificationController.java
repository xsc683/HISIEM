package com.xscsiem.hsiem_platform.notify;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/** 通知中心 API(story-10,MVP):列表/已读/清空/删除。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> list(@RequestParam(required = false) Boolean unread) {
        return service.list(unread);
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable String id) {
        service.read(id);
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        service.readAll();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
