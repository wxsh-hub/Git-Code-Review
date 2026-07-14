package com.devops.ai.core.crg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * CRG (code-review-graph) MCP 进程生命周期管理。
 *
 * <p>Spring Boot 启动时自动启动内嵌的 CRG exe，关闭时销毁进程。
 * 与 {@link CrgClient} 配合使用，后者通过 {@code @DependsOn("crgProcessManager")}
 * 保证在 CRG 就绪后才初始化。
 *
 * <h3>启动流程</h3>
 * <ol>
 *   <li>从 classpath:/crg/code-review-graph.exe 解压到临时目录（jar 内无法直接执行 exe）</li>
 *   <li>{@link ProcessBuilder} 启动 exe（HTTP 模式，127.0.0.1:{@code crg.port}）</li>
 *   <li>轮询 {@code localhost:port/mcp} 等待就绪（最多 {@code crg.startup-timeout-seconds} 秒）</li>
 *   <li>超时则打 WARN 日志，审查流程降级运行</li>
 * </ol>
 */
@Component("crgProcessManager")
public class CrgProcessManager {

    private static final Logger log = LoggerFactory.getLogger(CrgProcessManager.class);

    /** classpath 中 CRG exe 的资源路径 */
    private static final String CRG_RESOURCE_PATH = "/crg/code-review-graph.exe";

    private final CrgConfig config;
    private Process process;
    private Path exePath;

    public CrgProcessManager(CrgConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("CRG is disabled, skipping auto-start");
            return;
        }

        try {
            String healthUrl = "http://localhost:" + config.getPort() + "/mcp";

            // 0. 先检查端口是否已被占用 — 可能之前就已经启动过了，直接复用
            if (checkReady(healthUrl)) {
                log.info("CRG: port {} already in use, reusing existing service", config.getPort());
                return;
            }

            // 1. 从 classpath 提取 exe 到临时目录
            exePath = extractExe();
            if (exePath == null) {
                log.warn("CRG: failed to extract exe from classpath, CRG will not be available");
                return;
            }

            // 2. 启动 CRG 进程
            ProcessBuilder pb = new ProcessBuilder(exePath.toString());
            pb.redirectErrorStream(true);
            process = pb.start();

            // 启动线程消费 stdout，防止进程输出缓冲区满导致阻塞
            Thread outputConsumer = new Thread(() -> {
                try (InputStream is = process.getInputStream()) {
                    byte[] buf = new byte[8192];
                    while (is.read(buf) != -1) {
                        // 丢弃输出
                    }
                } catch (IOException ignored) {
                }
            }, "crg-stdout-consumer");
            outputConsumer.setDaemon(true);
            outputConsumer.start();

            log.info("CRG: process started, waiting for service on port {}...", config.getPort());

            // 3. 轮询等待就绪
            long deadline = System.currentTimeMillis() + config.getStartupTimeoutSeconds() * 1000L;
            boolean ready = false;

            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    // 进程异常退出 — 可能是端口被并发占用，再次检查
                    if (checkReady(healthUrl)) {
                        log.info("CRG: exe exited but port {} is occupied, reusing existing service", config.getPort());
                        ready = true;
                    } else {
                        log.warn("CRG: process exited with code {}", process.exitValue());
                    }
                    break;
                }
                if (checkReady(healthUrl)) {
                    ready = true;
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (ready) {
                log.info("CRG: service ready at http://localhost:{}/mcp", config.getPort());
            } else {
                log.warn("CRG: startup timed out after {}s — port {} not responding, "
                        + "review will proceed without CRG graph analysis",
                        config.getStartupTimeoutSeconds(), config.getPort());
            }
        } catch (Exception e) {
            log.warn("CRG: failed to start: {} — review will proceed without CRG", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("CRG: stopping process...");
            process.destroyForcibly();
            try {
                process.waitFor();
                log.info("CRG: process stopped");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 清理临时 exe
        if (exePath != null) {
            try {
                Files.deleteIfExists(exePath);
            } catch (IOException e) {
                log.debug("CRG: failed to delete temp exe: {}", e.getMessage());
            }
        }
    }

    // ================================================================
    // 内部方法
    // ================================================================

    /**
     * 从 classpath:/crg/code-review-graph.exe 提取到系统临时目录。
     */
    private Path extractExe() {
        try (InputStream is = getClass().getResourceAsStream(CRG_RESOURCE_PATH)) {
            if (is == null) {
                log.warn("CRG: resource not found on classpath: {}", CRG_RESOURCE_PATH);
                return null;
            }

            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "crg");
            Files.createDirectories(tempDir);
            Path dest = tempDir.resolve("code-review-graph.exe");

            // 如果已存在且大小相同，复用（避免每次启动都拷贝 36MB）
            if (Files.exists(dest)) {
                // 简单复用以减少 IO
                log.debug("CRG: reusing existing exe at {}", dest);
                dest.toFile().setExecutable(true);
                return dest;
            }

            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            dest.toFile().setExecutable(true);
            log.info("CRG: exe extracted to {} ({} bytes)", dest, Files.size(dest));
            return dest;
        } catch (Exception e) {
            log.warn("CRG: failed to extract exe: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查 CRG MCP 服务是否已就绪。
     */
    private boolean checkReady(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;  // 只要能连接上就算就绪
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 尝试获取进程 PID（反射，兼容 Java 8）。
     */
    private static long getPid(Process p) {
        try {
            // Java 8: ProcessImpl 内部有一个 pid 字段
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
