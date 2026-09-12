package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "areas")
public class Area extends OwnedEntity {
    @Id
    private String id;
    private String name;
    private String note;
    private String color;
    private Integer sort = 0;
    private String createdAt;
}
