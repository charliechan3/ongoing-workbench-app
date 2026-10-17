package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(name = "actions")
public class Action extends OwnedEntity {
    @Id
    private String id;
    private String taskId;
    private String projectId;
    private String areaId;
    private String name;
    @Column(name = "description", length = 2000)
    private String desc;
    private String status;
    private String priority;
    private String startDate;
    private String endDate;
    @Convert(converter = JsonMapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> repeat; // {type, weekdays[], days[]}
    @Convert(converter = StringListConverter.class)
    @Column(length = 2000)
    private List<String> doneDates;
    @Column(length = 4000)
    private String note;
    @Column(length = 4000)
    private String completionNote; // 完成情况（与投影 todo 双向同步）
    private Integer pomoCount = 0;
    private Integer pomoEstimate = 0; // 番茄估算总数（与绑定 todo 双向同步）
    private Integer progress = 0; // 累计完成进度（0-100，按天叠加；仅非重复行动使用）
    @Convert(converter = JsonMapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> progressLog; // 每日进度记录：{ "2026-10-17": { add: 20, note: "..." } }
    private Integer sort = 0;
    private String createdAt;
}
