package com.example.demo.web.dto;

import java.util.List;

public record RagAskResponse(
        String question,
        String answer,
        List<SourceInfo> sources,    // 引用来源列表
        double topScore,             // 最高相关度 (0~1)
        boolean belowThreshold       // 是否因置信度不足被拒绝
) {
    /** 前端展示用的来源信息 */
    public record SourceInfo(
            int refIndex,           // 引用编号，对应答案中的 [1] [2]
            String docId,           // 文档名
            String chunkIndex,      // 块编号
            double score,           // 相关度
            String textPreview      // 内容摘要
    ) {}
}