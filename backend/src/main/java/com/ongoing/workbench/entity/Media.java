package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "media")
public class Media extends OwnedEntity {
    @Id
    private String id;
    private String type;   // book | movie | tv | doc | other
    private String title;
    private String status; // wish | doing | done | paused | quit
    private Integer progress = 0;
    private String startDate;
    private String endDate;
    private Integer rating;
    private String note;
    private String createdAt;
}
