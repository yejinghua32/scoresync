package com.scoresync.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BrowserOpenerConfig {

    @Bean
    CommandLineRunner openBrowser(AppProperties appProperties) {
        return args -> {
            int port = appProperties.getServerPort();
            String url = "http://localhost:" + port;
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", "\"" + url + "\"");
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
            } catch (Exception e) {
                System.err.println("Failed to open browser: " + e.getMessage());
            }
        };
    }
}
