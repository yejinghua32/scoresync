package com.scoresync.config;

/**
 * 数据源配置
 * 配置 SQLite 数据库路径，并在启动时确保数据目录存在
 */

import jakarta.annotation.PostConstruct;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class DataSourceConfig {

    private final AppProperties appProperties;

    public DataSourceConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 初始化数据目录
     */
    @PostConstruct
    public void init() {
        String dataDir = appProperties.getStorage().getDataDir();
        new File(dataDir).mkdirs();
    }

    /**
     * 构建 SQLite 数据源
     */
    @Bean
    public javax.sql.DataSource dataSource() {
        String dataDir = appProperties.getStorage().getDataDir();
        String url = "jdbc:sqlite:" + dataDir + "/score-sync.db";
        return DataSourceBuilder.create()
                .driverClassName("org.sqlite.JDBC")
                .url(url)
                .build();
    }
}
