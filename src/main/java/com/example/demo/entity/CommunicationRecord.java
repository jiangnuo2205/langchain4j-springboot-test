package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "communication_record")
public class CommunicationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联客户 */
    @Column(nullable = false)
    private Long customerId;

    /** 沟通渠道：email / whatsapp / alibaba / phone / exhibition */
    private String channel;

    /** 沟通方向：inbound（客户发来）/ outbound（我方发出） */
    private String direction;

    /** 沟通内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 沟通时间 */
    private LocalDateTime communicatedAt;

    /** 创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.communicatedAt == null) {
            this.communicatedAt = LocalDateTime.now();
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getCommunicatedAt() { return communicatedAt; }
    public void setCommunicatedAt(LocalDateTime communicatedAt) { this.communicatedAt = communicatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
