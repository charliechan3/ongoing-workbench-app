package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "pomodoros")
public class Pomodoro extends OwnedEntity {
    @Id
    private String id;
    private String date;
    private String startedAt;
    private String endedAt;
    private Integer duration = 25;
    private Integer minutes; // 实际完成分钟数（前端一直传，但实体没声明会被丢；持久化后才能区分非默认番茄时长）
    private String targetType;
    private String targetId;
    private String title;
    private String createdAt;
}
