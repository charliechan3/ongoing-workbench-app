package com.ongoing.workbench.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "settings")
public class Settings {
    @Id
    private String id = "main";
    private Integer pomodoroMin = 25;
    private Integer breakMin = 5;

    /* ===== 工作偏好（前端偏好设置页使用，历史版本缺失，故需要显式补列） ===== */
    /** 每日工时目标（分钟），统计页用于对比实际工时 */
    private Integer dailyGoalMinutes = 0;
    /** 完成待办时自动沉淀 1 个番茄记录 */
    private Boolean autoPomoOnTodo = false;
    /** 完成行动时自动沉淀 1 个番茄记录 */
    private Boolean autoPomoOnAction = false;
    /** 番茄钟运行期间，把剩余时间显示在浏览器标签页标题上（默认关） */
    private Boolean showPomoInTitle = false;
}
