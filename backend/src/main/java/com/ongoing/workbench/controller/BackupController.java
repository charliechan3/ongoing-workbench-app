package com.ongoing.workbench.controller;

import com.ongoing.workbench.service.BackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/backup")
public class BackupController {

    @Autowired
    private BackupService backupService;

    /** 导出完整 JSON 备份 */
    @GetMapping("/export")
    public Map<String, Object> export() {
        return backupService.exportBundle();
    }

    /** 导出 Markdown 阅读版 */
    @GetMapping(value = "/export/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE + ";charset=UTF-8")
    public String exportMarkdown() {
        return backupService.exportMarkdown();
    }

    /** 导入预览：检查格式 / ID 冲突 / 悬空关联 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return backupService.preview(data == null ? Map.of() : data);
    }

    /** 执行恢复：mode = overwrite | merge */
    @PostMapping("/import")
    public Map<String, Object> apply(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        String mode = (String) body.getOrDefault("mode", "merge");
        if (data == null) {
            return Map.of("ok", false, "message", "备份数据为空");
        }
        return backupService.apply(data, mode);
    }
}
