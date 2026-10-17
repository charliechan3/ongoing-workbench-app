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
    private String fromActionId; // 来源行动：行动进度未满 100% 时自动生成的「续办提醒」（不是投影，不参与双向同步）
    private String fromTodoId; // 来源待办：纯待办进度未满 100% 时自动生成的「续办提醒」（同上，不参与双向同步）
    private Integer pomoEstimate = 0;
    private Integer pomoCount = 0;
    private Integer progress = 0; // 纯待办自己的累计进度（0-100）；绑定行动时以行动为准，这里不写
    @Convert(converter = JsonMapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> progressLog; // 每日进度记录：{ "2026-10-17": { add: 20, note: "..." } }
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
