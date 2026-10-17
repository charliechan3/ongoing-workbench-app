package com.ongoing.workbench;

import com.ongoing.workbench.config.DbDiagnostics;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkbenchApplication {
    public static void main(String[] args) {
        // 云端连不上库时（Hibernate 会吞掉原始异常），设 APP_DIAG_DB=true 打印逐段链路自检
        DbDiagnostics.runIfEnabled();
        SpringApplication.run(WorkbenchApplication.class, args);
    }
}
