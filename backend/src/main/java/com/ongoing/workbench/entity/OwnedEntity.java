package com.ongoing.workbench.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据归属基类（多用户数据隔离）：
 * 业务数据实体（区域/目标/项目/任务/行动/待办/番茄/工时/笔记/影视）都带 ownerId，
 * ownerId 为 null 表示历史遗留/种子数据 —— 首个注册账号注册时统一认领。
 * 全局数据（系统设置 Settings 单行按用户 id 分键）不走本基类。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class OwnedEntity {

    @Column(name = "owner_id")
    private String ownerId;
}
