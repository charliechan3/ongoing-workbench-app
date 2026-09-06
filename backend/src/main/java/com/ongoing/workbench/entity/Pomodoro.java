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
    private String targetType;
    private String targetId;
    private String title;
    private String createdAt;
}
