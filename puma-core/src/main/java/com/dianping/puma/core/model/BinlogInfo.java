package com.dianping.puma.core.model;

public class BinlogInfo {

    private String binlogFile;

    private Long binlogPosition;

    private Boolean skipToNextPos;

    public String getBinlogFile() {
        return binlogFile;
    }

    public void setBinlogFile(String binlogFile) {
        this.binlogFile = binlogFile;
    }

    public Long getBinlogPosition() {
        return binlogPosition;
    }

    public void setBinlogPosition(Long binlogPosition) {
        this.binlogPosition = binlogPosition;
    }

    public Boolean getSkipToNextPos() {
        return skipToNextPos;
    }

    public void setSkipToNextPos(Boolean skipToNextPos) {
        this.skipToNextPos = skipToNextPos;
    }
}