package com.xscsiem.hsiem_platform.control;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int size) {
        return control.listTasks(size);
    }
}
