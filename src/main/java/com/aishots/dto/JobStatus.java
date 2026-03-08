package com.aishots.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobStatus {
    public enum Status { PENDING, PROCESSING, COMPLETED, ERROR }

    private String     jobId;
    private Status     status;
    private int        progress;
    private String     message;
    private ScriptData script;
    private String     videoUrl;
    // thumbnailUrl 제거 — 유튜브 쇼츠는 썸네일 업로드 불가
}
