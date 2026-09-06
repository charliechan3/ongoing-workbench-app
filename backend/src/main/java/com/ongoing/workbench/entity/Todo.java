package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Map;

@Data
@Entity
@Table(name = "todos")
public class Todo extends OwnedEntity {
    @Id
    private String id;
    @Column(length = 1000)
    private String text;
    private String date;
    private String status; // todo | done
    private String priority;
    private String projectId;
    private String taskId;
    private String actionId; // 绑定的行动 id（行动在 Todo 侧的投影），无则为纯待办
    private Integer pomoEstimate = 0;
    private Integer pomoCount = 0;
    @Column(length = 4000)
    private String note;
    @Column(length = 4000)
    private String completionNote; // 完成情况（做完后的结果记录，绑定行动时双向同步）
    private String batchKey;
    private String startDate;
    private String endDate;
    private String doneDate;
    @Convert(converter = JsonMapConverter.class)
    @Column(length = 500)
    private Map<String, Object> convertedTo;
    private String createdAt;
}
