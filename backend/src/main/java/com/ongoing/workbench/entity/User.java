package com.ongoing.workbench.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "users")
public class User {
    @Id
    private String id;
    @Column(unique = true, length = 40)
    private String username;
    @Column(name = "password_hash", length = 200)
    private String passwordHash;
    @Column(length = 40)
    private String nickname;
    private String createdAt;
}
