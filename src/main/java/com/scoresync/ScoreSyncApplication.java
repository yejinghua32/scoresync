package com.scoresync;

/**
 * ScoreSync 应用主类
 * 乒乓球视频记分与渲染应用，基于 Spring Boot 构建
 */

import com.scoresync.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ScoreSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoreSyncApplication.class, args);
    }
}
