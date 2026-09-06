package com.ongoing.workbench.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * cloud 模式启动校验：APP_MODE=cloud 时若缺少云端数据库环境变量，立刻以明确的中文错误终止，
 * 避免落到 HikariCP 的英文底层报错（如 "jdbcUrl is required"）难以排查。
 * local 模式（默认）不受影响。
 */
@Order(Ordered.LOWEST_PRECEDENCE) // 晚于 ConfigDataEnvironmentPostProcessor，确保 application-*.yml 已加载
public class CloudModeChecker implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!env.acceptsProfiles(Profiles.of("cloud"))) {
            return;
        }
        String url = env.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "[ongoing-workbench] APP_MODE=cloud 已启用，但缺少云端数据库配置：请设置环境变量 "
                + "CLOUD_DB_URL / CLOUD_DB_USER / CLOUD_DB_PASS（示例见 application-cloud.yml 注释），"
                + "或改回 APP_MODE=local 使用本地 H2。");
        }
        String user = env.getProperty("spring.datasource.username");
        if (user == null || user.isBlank()) {
            throw new IllegalStateException(
                "[ongoing-workbench] APP_MODE=cloud 已启用，但缺少 CLOUD_DB_USER / CLOUD_DB_PASS 环境变量。");
        }
    }
}
