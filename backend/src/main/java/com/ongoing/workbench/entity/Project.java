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
    // 该项目的行动是否展示在「待办」里：false = 行动只留在进行中，不进待办/日历/首页今日待办。
    // 老数据没有这一列时读出来是 null，按「展示」处理（只有显式 false 才隐藏）。
    private Boolean showActionsInTodo = true;
}
