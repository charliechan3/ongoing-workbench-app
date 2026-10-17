package com.ongoing.workbench.repo;

import com.ongoing.workbench.entity.PomoSession;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PomoSessionRepo extends OwnedRepo<PomoSession, String> {

    /**
     * 按会话标识原子删除（结算/取消）：影响行数就是"是否由本次请求认领"。
     * sid 不匹配说明另一端已经换成了新会话，本次请求不该动它。
     */
    @Modifying
    @Query("delete from PomoSession p where p.id = :id and p.ownerId = :ownerId and p.sid = :sid")
    int claimBySid(@Param("id") String id, @Param("ownerId") String ownerId, @Param("sid") String sid);

    /** 老会话没有 sid（升级前留下来的），按用户直接删 */
    @Modifying
    @Query("delete from PomoSession p where p.id = :id and p.ownerId = :ownerId")
    int claimById(@Param("id") String id, @Param("ownerId") String ownerId);
}
