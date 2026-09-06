package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "projects")
public class Project extends OwnedEntity {
    @Id
    private String id;
    private String areaId;
    private String name;
    @Column(name = "description", length = 2000)
    private String desc;
    private String status;
    private String priority;
    private String startDate;
    private String endDate;
    private String color;
    private Integer sort = 0;
    private String createdAt;
}
