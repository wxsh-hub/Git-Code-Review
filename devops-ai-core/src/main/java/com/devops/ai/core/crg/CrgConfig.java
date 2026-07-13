package com.devops.ai.core.crg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CRG (code-review-graph) 配置。
 *
 * <p>读取 application.yml 中 crg.* 配置项。
 */
@Component
public class CrgConfig {

    /** 是否启用 CRG 集成（关闭后完全走原逻辑） */
    @Value("${crg.enabled:true}")
    private boolean enabled;

    /** CRG MCP 服务地址 */
    @Value("${crg.url:http://localhost:9527}")
    private String url;

    /** HTTP 连接超时（毫秒） */
    @Value("${crg.connect-timeout:10000}")
    private int connectTimeout;

    /** HTTP 读取超时 — 查询类操作（毫秒，默认 2 分钟） */
    @Value("${crg.read-timeout:120000}")
    private int readTimeout;

    /** HTTP 读取超时 — 构建/后处理类操作（毫秒，默认 30 分钟）。
     *  大仓库（500+ 文件）构建可能需要 10-15 分钟，预留充足时间。 */
    @Value("${crg.build-read-timeout:1800000}")
    private int buildReadTimeout;

    /** 全局摘要 — 高扇入方法数量 */
    @Value("${crg.summary.top-fanin-methods:10}")
    private int topFaninMethods;

    /** 全局摘要 — 执行流数量 */
    @Value("${crg.summary.top-flows:5}")
    private int topFlows;

    /** 查询 — callers_of 最大返回数 */
    @Value("${crg.query.max-callers:5}")
    private int maxCallers;

    /** 查询 — callees_of 最大返回数 */
    @Value("${crg.query.max-callees:5}")
    private int maxCallees;

    /** 高扇入阈值（被 >= 此数的方法调用才算高扇入） */
    @Value("${crg.summary.fanin-threshold:3}")
    private int faninThreshold;

    // ---- getters ----

    public boolean isEnabled() { return enabled; }
    public String getUrl() { return url; }
    public int getConnectTimeout() { return connectTimeout; }
    public int getReadTimeout() { return readTimeout; }
    public int getBuildReadTimeout() { return buildReadTimeout; }
    public int getTopFaninMethods() { return topFaninMethods; }
    public int getTopFlows() { return topFlows; }
    public int getMaxCallers() { return maxCallers; }
    public int getMaxCallees() { return maxCallees; }
    public int getFaninThreshold() { return faninThreshold; }
}
