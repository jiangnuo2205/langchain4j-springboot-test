package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_message")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    /** user / assistant / system */
    @Column(nullable = false)
    private String role;

    /** 消息内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 对应的评估结果（JSON，仅 assistant 消息有） */
    @Column(columnDefinition = "TEXT")
    private String evaluation;

    /** 是否为有效业务对话（兜底拒绝的不计入） */
    private boolean validBusiness;

    /** 消息序号 */
    private int seqNum;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    // ── Getters/Setters ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sid) { this.sessionId = sid; }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
    public String getEvaluation() { return evaluation; }
    public void setEvaluation(String e) { this.evaluation = e; }
    public boolean isValidBusiness() { return validBusiness; }
    public void setValidBusiness(boolean v) { this.validBusiness = v; }
    public int getSeqNum() { return seqNum; }
    public void setSeqNum(int s) { this.seqNum = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
