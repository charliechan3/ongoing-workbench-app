package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "tasks")
public class Task extends OwnedEntity {
    @Id
    private String id;
    private String projectId;
    private String areaId;
    private String name;
    @Column(name = "description", length = 2000)
    private String desc;
    private String status;
    private String priority;
    private String startDate;
    private String endDate;
    private Integer pomoEstimate = 0;
    private Integer pomoCount = 0;
    private Double progress; // 手动覆盖进度（可选）
    private Integer sort = 0;
    private String createdAt;
}
