package com.xscsiem.hsiem_platform.control;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/** 后台任务只读 API，供数据源异步生效等控制面任务轮询。 */
@RestController
@RequestMapping("/api/tasks")
public class BackgroundTaskController {

    private final ControlPlaneStore control;

    public BackgroundTaskController(ControlPlaneStore control) {
        this.control = control;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int size) {
        return control.listTasks(size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'AUDIT')")
    public Map<String, Object> get(@PathVariable String id) {
        Map<String, Object> task = control.findTask(id);
        if (task == null) {
            throw new com.xscsiem.hsiem_platform.onboarding.NotFoundException("后台任务不存在: " + id);
        }
        return task;
    }
}
