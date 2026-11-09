package com.ssafy.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@EnableConfigServer
@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) {

        String privateKey =
                System.getenv("CONFIG_GIT_PRIVATE_KEY");

        if (privateKey != null && !privateKey.isBlank()) {

            String normalized = privateKey.trim();

            // .env 로더가 따옴표까지 전달했을 경우 제거
            if (normalized.startsWith("\"")
                    && normalized.endsWith("\"")) {
                normalized = normalized.substring(
                        1, normalized.length() - 1);
            }

            // 문자열 \n을 실제 줄바꿈으로 변환
            normalized = normalized
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\r\n", "\n");

            System.out.println("RSA BEGIN: " +
                    normalized.startsWith("-----BEGIN RSA PRIVATE KEY-----"));

            System.out.println("RSA END: " +
                    normalized.endsWith("-----END RSA PRIVATE KEY-----"));

            System.out.println("NEWLINE EXISTS: " +
                    normalized.contains("\n"));

            System.setProperty(
                    "spring.cloud.config.server.git.private-key",
                    normalized
            );
        }

        SpringApplication.run(
                ConfigServerApplication.class, args);
    }
}
