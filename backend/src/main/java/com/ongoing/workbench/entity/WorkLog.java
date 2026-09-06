package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "work_logs")
public class WorkLog extends OwnedEntity {
    @Id
    private String id;
    private String date;
    private Integer minutes;
    private String source; // pomodoro | manual
    private String note;
    private String createdAt;
}
