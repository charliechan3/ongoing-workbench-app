package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 进行中的番茄钟会话（多端同步用）。
 *
 * <p>一个账号只允许一条（主键直接取 userId，多端 start 天然覆盖同一行，
 * 不会出现两台设备各跑一个会话、互相看不见的情况）。</p>
 *
 * <p>时间戳由服务端给出（startAt/endAt 均为服务器 epoch ms）：客户端把
 * 「endAt - serverNow」换算成本地剩余时长，两端时钟不一致时倒计时也一致。</p>
 *
 * <p>结算（到点入账）时删除本行 —— 删除是原子的，多端同时到点只有一个请求
 * 能删到行，从而保证一颗番茄只记一次。</p>
 */
@Data
@Entity
@Table(name = "pomo_sessions")
public class PomoSession extends OwnedEntity {

    /** 主键 = 用户 id（每用户一条会话） */
    @Id
    private String id;

    /** 客户端会话标识：区分「同一颗番茄」与「另一端重新开始的新番茄」 */
    private String sid;

    private Integer minutes;

    /** 绑定的待办 id（可为空 = 自由专注） */
    private String todoId;

    /** 开始时刻（服务器时间，ms） */
    private Long startAt;

    /** 结束时刻（服务器时间，ms） */
    private Long endAt;

    private Long updatedAt;
}
