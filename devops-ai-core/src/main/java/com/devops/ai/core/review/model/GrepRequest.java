package com.devops.ai.core.review.model;

/**
 * 迭代审查中 LLM 输出的 grep 请求。
 *
 * <p>LLM 在审查过程中发现可疑代码需要查看外部实现时，
 * 通过此结构请求搜索。每个请求包含：
 * <ul>
 *   <li>{@code symbol} — 要搜索的符号（类名.方法名）</li>
 *   <li>{@code file} — 可选，文件路径（有则直接读取该文件的方法体）</li>
 *   <li>{@code reason} — 为什么要看这段代码</li>
 * </ul>
 */
public class GrepRequest {

    /** 要搜索的符号，如 UserRepository.findById */
    private String symbol;

    /** 可选，文件路径（有则 readFile，无则 grep） */
    private String file;

    /** 为什么要看这段代码 */
    private String reason;

    public GrepRequest() {}

    public GrepRequest(String symbol, String file, String reason) {
        this.symbol = symbol;
        this.file = file;
        this.reason = reason;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
