package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 子项（把一条行动/待办拆成的小步骤）。
 * 只有名字、完成状态、排序三个业务字段，刻意不带番茄/优先级/日期等属性：
 * 子项是"清单式"的最轻量拆分，番茄与排期仍归父级行动/待办。
 *
 * 归属：actionId 与 todoId 二选一（写入时优先落在 action 侧）。
 * 由于 Todo 是 Action 的投影，读取时会同时匹配两者，因此投影两侧看到的是同一份子项。
 */
@Data
@Entity
@Table(name = "checklist_items")
public class ChecklistItem extends OwnedEntity {
    @Id
    private String id;
    /** 归属行动（与 todoId 二选一） */
    private String actionId;
    /** 归属待办（纯待办、无绑定行动时用） */
    private String todoId;
    @Column(length = 500)
    private String name;
    private Boolean done = false;
    private Integer sort = 0;
    private String createdAt;
}
