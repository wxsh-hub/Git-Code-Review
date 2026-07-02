package com.devops.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@SpringBootApplication
@EntityScan(basePackages = "com.devops.ai.infrastructure.entity")
@EnableJpaRepositories(basePackages = "com.devops.ai.infrastructure.repository")
@EnableAsync
public class DevOpsAiApplication {

    // 全局跳过 SSL 验证 —— 在所有 Spring Bean 初始化前执行
    static {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                }
            };
            SSLContext ctx = SSLContext.getInstance("SSL");  // "SSL" 在所有 JDK 8+ 上都支持
            ctx.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            System.out.println("[devops-ai] SSL certificate verification disabled — all HTTPS connections trusted");
        } catch (Exception e) {
            System.err.println("[devops-ai] WARNING: Failed to disable SSL verification: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(DevOpsAiApplication.class, args);
    }
}
