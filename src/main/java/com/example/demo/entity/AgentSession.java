package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_session")
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务员标识（用于关联画像） */
    @Column(nullable = false)
    private String salespersonId;

    /** 会话标题（自动从首条消息生成） */
    private String title;

    /** 当前 L1 意图：FIND_CUSTOMER / CLOSE_DEAL / CREATIVE / OFF_TOPIC */
    private String currentIntentL1;

    /** 当前 L2 深层目标 */
    @Column(columnDefinition = "TEXT")
    private String currentIntentL2;

    /** 累计上下文摘要（压缩后的历史） */
    @Column(columnDefinition = "TEXT")
    private String contextSummary;

    /** 涉及的客户信息快照（JSON） */
    @Column(columnDefinition = "TEXT")
    private String customerInfoSnapshot;

    /** 总消息数 */
    private int messageCount;

    /** 上次摘要时的消息序号 */
    private int lastSummaryAtCount;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // ── Getters/Setters ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSalespersonId() { return salespersonId; }
    public void setSalespersonId(String s) { this.salespersonId = s; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getCurrentIntentL1() { return currentIntentL1; }
    public void setCurrentIntentL1(String s) { this.currentIntentL1 = s; }
    public String getCurrentIntentL2() { return currentIntentL2; }
    public void setCurrentIntentL2(String s) { this.currentIntentL2 = s; }
    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String s) { this.contextSummary = s; }
    public String getCustomerInfoSnapshot() { return customerInfoSnapshot; }
    public void setCustomerInfoSnapshot(String s) { this.customerInfoSnapshot = s; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int c) { this.messageCount = c; }
    public int getLastSummaryAtCount() { return lastSummaryAtCount; }
    public void setLastSummaryAtCount(int c) { this.lastSummaryAtCount = c; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
