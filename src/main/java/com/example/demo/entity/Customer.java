package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公司名称 */
    @Column(nullable = false)
    private String companyName;

    /** 联系人姓名 */
    private String contactName;

    /** 联系人邮箱 */
    private String email;

    /** 国家/地区 */
    private String country;

    /** 行业 */
    private String industry;

    /** 企业规模描述，如 "50-100人零售商" */
    private String companySize;

    /** 产品线/感兴趣的品类 */
    private String productInterest;

    /** 客户来源：alibaba / email / whatsapp / exhibition */
    private String source;

    /** AI 生成的客户画像（JSON 或长文本） */
    @Column(columnDefinition = "TEXT")
    private String aiProfile;

    /** 客户分级：A / B / C / D */
    @Column(length = 2)
    private String grade;

    /** AI 分级理由 */
    @Column(columnDefinition = "TEXT")
    private String gradeReason;

    /** AI 生成的跟进建议 */
    @Column(columnDefinition = "TEXT")
    private String followUpSuggestion;

    /** 最近一次跟进时间 */
    private LocalDateTime lastFollowUpAt;

    /** 创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getCompanySize() { return companySize; }
    public void setCompanySize(String companySize) { this.companySize = companySize; }

    public String getProductInterest() { return productInterest; }
    public void setProductInterest(String productInterest) { this.productInterest = productInterest; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getAiProfile() { return aiProfile; }
    public void setAiProfile(String aiProfile) { this.aiProfile = aiProfile; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getGradeReason() { return gradeReason; }
    public void setGradeReason(String gradeReason) { this.gradeReason = gradeReason; }

    public String getFollowUpSuggestion() { return followUpSuggestion; }
    public void setFollowUpSuggestion(String followUpSuggestion) { this.followUpSuggestion = followUpSuggestion; }

    public LocalDateTime getLastFollowUpAt() { return lastFollowUpAt; }
    public void setLastFollowUpAt(LocalDateTime lastFollowUpAt) { this.lastFollowUpAt = lastFollowUpAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
