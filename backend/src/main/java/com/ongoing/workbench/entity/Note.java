package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "notes")
public class Note extends OwnedEntity {
    @Id
    private String id;
    private String projectId;
    private String title;
    // 跨方言：不用 @Lob（PG 会映射成 oid 大对象）；H2-MySQL 模式/MySQL/PostgreSQL 均支持 TEXT
    @Column(columnDefinition = "TEXT")
    private String content;
    private String publishedAt;
    private String updatedAt;
    private String createdAt;
}
