package com.devops.ai.core.efficiency.model;

/**
 * 单个开发者的效率画像：
 * 提交了多少代码，引入了多少需要别人修复的问题，帮别人修复了多少问题。
 */
public class DeveloperEfficiency {

    private String authorName;
    private String authorEmail;

    private int totalCommits;
    private int totalLinesChanged;

    /** 自己引入的、被别人修复的问题数 */
    private int fixesIntroduced;
    /** 自己修复别人的问题数 */
    private int fixesMadeForOthers;
    /** 自己做的功能增强数 */
    private int enhancementsMade;
    /** 参与重复修改的总次数 */
    private int repeatedChangeCount;

    /** 效率评分：越高越好。公式见 DeveloperEfficiencyService */
    private double efficiencyScore;

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorEmail() { return authorEmail; }
    public void setAuthorEmail(String authorEmail) { this.authorEmail = authorEmail; }

    public int getTotalCommits() { return totalCommits; }
    public void setTotalCommits(int totalCommits) { this.totalCommits = totalCommits; }

    public int getTotalLinesChanged() { return totalLinesChanged; }
    public void setTotalLinesChanged(int totalLinesChanged) { this.totalLinesChanged = totalLinesChanged; }

    public int getFixesIntroduced() { return fixesIntroduced; }
    public void setFixesIntroduced(int fixesIntroduced) { this.fixesIntroduced = fixesIntroduced; }

    public int getFixesMadeForOthers() { return fixesMadeForOthers; }
    public void setFixesMadeForOthers(int fixesMadeForOthers) { this.fixesMadeForOthers = fixesMadeForOthers; }

    public int getEnhancementsMade() { return enhancementsMade; }
    public void setEnhancementsMade(int enhancementsMade) { this.enhancementsMade = enhancementsMade; }

    public int getRepeatedChangeCount() { return repeatedChangeCount; }
    public void setRepeatedChangeCount(int repeatedChangeCount) { this.repeatedChangeCount = repeatedChangeCount; }

    public double getEfficiencyScore() { return efficiencyScore; }
    public void setEfficiencyScore(double efficiencyScore) { this.efficiencyScore = efficiencyScore; }
}
