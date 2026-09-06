package com.ongoing.workbench.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * 归属数据仓库基类：业务实体仓库都基于它，天然获得按 ownerId 过滤的查询。
 * 配合 AuthInterceptor → UserContext.currentUserId() 使用即可实现多用户数据隔离。
 */
@NoRepositoryBean
public interface OwnedRepo<T, ID> extends JpaRepository<T, ID> {

    List<T> findByOwnerId(String ownerId);

    /** 历史遗留/种子数据（尚无归属账号，首个注册账号认领用） */
    List<T> findByOwnerIdIsNull();

    long countByOwnerId(String ownerId);

    boolean existsByIdAndOwnerId(ID id, String ownerId);

    /** 仅删除当前用户自己的记录（覆盖恢复前清空用），需在事务内调用 */
    void deleteByOwnerId(String ownerId);
}
