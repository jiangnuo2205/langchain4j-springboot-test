package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "salesperson_profile")
public class SalespersonProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务员唯一标识 */
    @Column(nullable = false, unique = true)
    private String salespersonId;

    /** 姓名 */
    private String name;

    /** AI 生成的能力画像（JSON） */
    @Column(columnDefinition = "TEXT")
    private String abilityProfile;

    /** 擅长品类 */
    private String strongCategories;

    /** 擅长市场 */
    private String strongMarkets;

    /** 累计会话数 */
    private int totalSessions;

    /** 累计有效对话轮次 */
    private int totalValidTurns;

    /** AI 识别的能力短板（JSON） */
    @Column(columnDefinition = "TEXT")
    private String weaknesses;

    /** AI 识别的成功模式（JSON） */
    @Column(columnDefinition = "TEXT")
    private String successPatterns;

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
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getAbilityProfile() { return abilityProfile; }
    public void setAbilityProfile(String a) { this.abilityProfile = a; }
    public String getStrongCategories() { return strongCategories; }
    public void setStrongCategories(String s) { this.strongCategories = s; }
    public String getStrongMarkets() { return strongMarkets; }
    public void setStrongMarkets(String s) { this.strongMarkets = s; }
    public int getTotalSessions() { return totalSessions; }
    public void setTotalSessions(int t) { this.totalSessions = t; }
    public int getTotalValidTurns() { return totalValidTurns; }
    public void setTotalValidTurns(int t) { this.totalValidTurns = t; }
    public String getWeaknesses() { return weaknesses; }
    public void setWeaknesses(String w) { this.weaknesses = w; }
    public String getSuccessPatterns() { return successPatterns; }
    public void setSuccessPatterns(String s) { this.successPatterns = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
