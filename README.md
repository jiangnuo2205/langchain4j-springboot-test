# AI 外贸智能体 Demo

> AI驱动的选品与获客策略升级 — 转化智能体 + 获客智能体 MVP

## 项目背景

外贸公司（定制实物产品：杯垫、保温杯、户外用品等）面临三个核心痛点：

1. **转化效率低**：阿里国际站询盘未分级管理，90%客户缺少 A/B/C/D 分级，销售无法聚焦高价值客户
2. **获客方式被动**：依赖平台等客户搜索，缺乏主动出击的精准获客能力
3. **选品逻辑单一**：基于产品属性描述，未挖掘场景化需求（如杯垫→宠物纪念）

## 项目拆解与优先级

基于会议纪要分析，三个智能体的优先级：

```
转化智能体 (P0) → 获客智能体 (P1) → 选品智能体 (P2)
```

**MVP 聚焦 P0 + P1 简版**，原因：
- 转化直接影响短期成交，ROI 最高
- 获客意图识别是独立模块，可快速实现
- 选品需要爬虫数据积累，适合后期迭代

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                  Vue3 前端 Dashboard                  │
│   客户列表 · AI分析 · 分级展示 · 获客意图识别            │
└────────────┬──────────────────────┬──────────────────┘
             │ REST API             │
┌────────────▼──────────┐ ┌────────▼──────────────────┐
│   转化智能体 (P0)      │ │   获客智能体 (P1)          │
│                        │ │                            │
│ CustomerAnalysis       │ │ IntentRecognition          │
│   ↓ 客户画像生成       │ │   ↓ 自然语言→结构化需求     │
│ CustomerGrading        │ │   ↓ 目标客户画像            │
│   ↓ A/B/C/D 分级      │ │   ↓ 获客渠道建议            │
│ FollowUpGeneration     │ │   ↓ 风险提醒               │
│   ↓ 跟进话术+策略      │ │                            │
└────────────┬──────────┘ └────────────────────────────┘
             │
┌────────────▼──────────────────────────────────────────┐
│                    基础设施层                           │
│  LangChain4j · DashScope(阿里百炼) · MySQL · Chroma   │
│  ChatModel · EmbeddingModel · SSE流式 · RAG管线       │
└───────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 选型 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3 + JDK 17 | |
| AI平台 | 阿里百炼 (DashScope) | 通过 LangChain4j 统一封装 |
| LLM调用 | LangChain4j ChatModel | 复用现有 ChatService |
| 向量数据库 | ChromaDB | 复用现有 RAG 管线 |
| 关系数据库 | MySQL 8 | 客户数据持久化 |
| 前端 | Vue3 (CDN) | 单文件 HTML，无需构建 |

## 核心 Prompt 设计

### 转化智能体 — 客户分析 Prompt

设计思路：**模拟资深外贸顾问**，而非机械式数据处理

- **角色设定**：资深外贸客户分析顾问
- **结构化输出**：严格 JSON 格式（profile / grade / gradeReason / followUp）
- **分级标准明确**：A-D 四级，每级有清晰的判定条件
- **信息容错**：对缺失字段做合理推断并标注

### 获客智能体 — 意图识别 Prompt

设计思路：**理解表层诉求背后的深层目标**

- 支持口语化/模糊输入（如"我朋友做钓鱼竿想卖去美国"）
- 输出包含：产品类型、目标市场、客户画像、推荐渠道、核心卖点、风险提醒
- 信息不足时基于行业经验推断并标注"（推断）"

## 快速开始

### 1. 准备数据库

```sql
CREATE DATABASE IF NOT EXISTS trade_ai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
```

### 2. 追加 Maven 依赖

在 `pom.xml` 的 `<dependencies>` 中追加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 3. 配置数据库连接

在 `application-local.properties` 中追加：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/trade_ai?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4
spring.datasource.username=root
spring.datasource.password=你的密码
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
```

### 4. 放入新增文件

将以下文件复制到对应目录：

```
src/main/java/com/example/demo/
├── entity/
│   ├── Customer.java
│   └── CommunicationRecord.java
├── repository/
│   ├── CustomerRepository.java
│   └── CommunicationRecordRepository.java
├── service/
│   ├── CustomerAnalysisService.java
│   └── IntentRecognitionService.java
├── controller/
│   └── TradeController.java

src/main/resources/static/
└── trade-dashboard.html
```

### 5. 启动并测试

```bash
# 确保 MySQL 和 Chroma 已启动
mvn spring-boot:run

# 访问 Dashboard
open http://localhost:8090/trade-dashboard.html
```

### 6. 操作流程

1. 点击 **初始化Demo数据** → 插入5个模拟客户及沟通记录
2. 进入 **客户管理** → 点击 **AI分析** 生成画像和分级
3. 点击 **详情** 查看完整的客户画像、分级理由、跟进建议
4. 切换 **获客智能体** → 输入自然语言测试意图识别

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/trade/stats` | Dashboard 统计数据 |
| GET | `/api/trade/customers` | 客户列表（支持 ?grade=A 筛选） |
| GET | `/api/trade/customers/{id}` | 客户详情 + 沟通记录 |
| POST | `/api/trade/customers` | 新增客户 |
| POST | `/api/trade/customers/{id}/records` | 新增沟通记录 |
| POST | `/api/trade/customers/{id}/analyze` | AI 分析单个客户 |
| POST | `/api/trade/analyze-all` | 批量分析未分级客户 |
| POST | `/api/trade/intent` | 获客意图识别 |
| POST | `/api/trade/init-demo` | 初始化Demo数据 |

## 后续扩展路线

### 转化智能体增强
- [ ] 接入阿里国际站询盘数据（Comet截屏 → OCR → 结构化）
- [ ] 邮件监测闭环（OKKI/CIM系统集成）
- [ ] 客户雷达监测（官网更新、LinkedIn动态、海关数据）
- [ ] 飞书推送（每日50-100客户 + 证据链）

### 获客智能体增强
- [ ] 多源验证（LinkedIn + 海关数据 + 展会信息）
- [ ] 客户匹配度评估 + 自动推送
- [ ] 邮箱精准度验证

### 选品智能体（P2）
- [ ] Reddit / Kickstarter 爬虫 → 用户需求数据
- [ ] 聚类分析 → 需求图谱
- [ ] 产品匹配 → 场景化内容生成
