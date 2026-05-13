package io.github.veh.maven.plugin;

public class AutoCleanMojoLateralClause {
    String content;
    int startLine;

    protected String getContent() {
        return content;
    }

    protected void setContent(String content) {
        this.content = content;
    }

    protected int getStartLine() {
        return startLine;
    }

    protected void setStartLine(int startLine) {
        this.startLine = startLine;
    }
}
