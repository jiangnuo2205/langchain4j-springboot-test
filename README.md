# AI 外贸智能体 Demo

> AI驱动的选品与获客策略升级 — 转化智能体 + 获客智能体 MVP

页面一：客户管理面板：http://localhost:8090/trade-dashboard.html
- 📊 Dashboard     — 统计概览
- 👥 客户管理      — 列表/详情/AI分析分级
- 📥 导入询盘(NEW) — PDF上传OCR + 文本粘贴 → 自动创建客户+沟通记录
- 📡 雷达监测      — 成交抵达度 + 联络节点 + 信号分析
- 🌐 网站分析      — URL爬取 → 客户画像 → 存库
- 🎯 获客智能体（非对话式）    — 自然语言意图识别
  
页面二：获客智能体（对话式：含意图识别及业务员评估体系） http://localhost:8090/agent-chat.html
- 🎯 对话式获客智能体    — 两层意图识别思维链+业务员评估体系


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

## 客户生命周期管理系统
可查看项目位置思维导图：langchain4j-springboot-test/REDME_DESIGN_HISTORY/外贸获客Demo思维导图.xmind
框架图片：langchain4j-springboot-test/REDME_DESIGN_HISTORY/客户生命周期管理系统.jpg

## 业务模型详解

### 1. 成交抵达度（Deal Readiness Score）

这是**客户推进进度的量化评估**，本质是一个 0-100 的分数，由信号加权计算得出：

| 阶段      | 抵达度 | 判定依据                         |
| --------- | ------ | -------------------------------- |
| 初步接触  | 20%    | 收到询盘但只问了一句话           |
| 需求确认  | 40%    | 客户明确了品类、数量、或要了目录 |
| 报价/样品 | 60%    | 我方已报价或寄样                 |
| 谈判中    | 80%    | 在讨论价格/付款条件/交期         |
| 成交      | 100%   | 下单                             |

AI 从沟通记录中**自动识别关键信号**来判定当前阶段，比如客户说了"send me samples"就是60%的信号，说了"your price is acceptable"就是80%的信号。

### 2. 联络节点（Contact Schedule）

**基于客户所处阶段 + 雷达信号，自动计算下一次应该联系客户的时间和方式：**

- S1 首次触达 → 24小时内必须回复（黄金窗口）
- S2 深度沟通 → 3-5天跟进一次
- S3 报价/样品阶段 → 7天催促（"Have you received the samples?"）
- S4 谈判阶段 → 按约定时间节点

**雷达事件可以触发"打断性联络"**：比如实时监测到客户官网新增了一个产品线，这就是一个切入机会，系统应该立刻提醒销售跟进。

### 3. 雷达监测的信号收集链路

MVP 阶段怎么做（你两天内能实现的）：

**离线雷达** → 已有数据，直接分析：

- 把历史询盘/邮件导入 `communication_record` 表
- LLM 分析沟通记录，提取：情感倾向、需求变化、响应速度

**实时雷达** → MVP 阶段用"定时爬取网站"模拟：

- 复用你已有的 `WebsiteAnalysisService`，定期爬取客户官网
- 对比前后两次爬取结果，识别变化（新产品线、新招聘等）

### 联络节点和成交抵达度的信号收集链路

信号全部从**已有数据**中提取：

**离线信号（从 communication_record 表）→ LLM 自动识别：**

- 客户说"send samples" → 成交抵达度跳到 60%
- 客户说"price is acceptable" → 跳到 80%
- 客户主动联系次数多 → positive 信号
- 超过30天没回复 → negative 信号

**实时信号（从 WebsiteAnalysisService）→ 定期爬取对比：**

- 官网新增产品线 → opportunity 信号，触发立即联络
- 招聘采购岗 → opportunity 信号

**联络节点计算逻辑：**

- 根据 dealStage 确定基础间隔（24h/3天/7天）
- 如果有 opportunity 信号 → 覆盖为"立即联络"
- 最终输出：具体时间 + 联系方式 + 话题建议

这些全部由 LLM 从沟通记录中自动判断，不需要人工打标签。



## 获客Agent (3意图+1兜底)
业务架构图片：langchain4j-springboot-test/REDME_DESIGN_HISTORY/获客智能体 对话式Agent架构.jpg

### 获客Agent (3意图+1兜底) 核心难点和解法

**1. 上下文管理（你提到的"避免每次载入所有信息"）**

采用**滑动窗口 + 定期摘要**的策略：

- 保留最近10条原始对话
- 每10轮由LLM生成一次摘要，替换旧消息
- System Prompt 中注入：摘要 + 业务员画像 + 当前评估状态

**2. 意图识别两层**

| 层级    | 功能                        | 示例                                   |
| ------- | --------------------------- | -------------------------------------- |
| L1 表层 | 分类为3种业务意图 + 1个兜底 | "找客户"/"促成交"/"创意获客"/"非业务"  |
| L2 深层 | 识别对话背后的真实目标      | "客户担心资金安全" → 深层目标=风险规避 |


| L1 场景分类     | L2 细分意图                                      | 示例输入                    |
| --------------- | ------------------------------------------------ | --------------------------- |
| **A: 找客户**   | 平台获客、展会获客、社媒获客、行业数据获客       | "我做杯垫，想找美国零售商"  |
| **B: 促成交**   | 异议处理、价格谈判、信任建立、交期协商、付款方式 | "客户说担心资金安全"        |
| **C: 创意获客** | 场景联想、跨界匹配、信息源推荐                   | "涂鸦+杯垫能有什么玩法"     |
| **D: 兜底**     | 非外贸内容                                       | "帮我写个Python脚本" → 拒绝 |

**3. 实时评估面板**

每次AI回复时，同时返回结构化评估JSON，前端右侧面板实时更新。评估包含：意图链路、外贸人员能力评估、客户信息沉淀、下一步指引。

### 获客Agent (3意图+1兜底) 核心设计要点

**1. 两层意图识别**

在 System Prompt 中定义了严格的意图分类，LLM 每次回复时必须同时输出评估JSON。L1 分为4类（找客户/促成交/创意获客/非业务），L2 识别深层目标（资金安全、风险承受等）。

**2. 兜底机制**

非业务内容 → intentL1 标记为 `OFF_TOPIC` → `validBusiness=false` → 不计入有效对话 → 不更新业务员画像。兜底回复是固定模板，告诉用户能力范围。

**3. 上下文压缩**

每20条消息触发一次摘要压缩。LLM 把旧对话压缩为300字摘要，注入 System Prompt。滑动窗口只保留最近10条原始消息。这样即使聊了100轮，context window 也不会爆。

**4. 回复和评估分离**

AI 回复用 `===EVALUATION===` 分隔符分成两部分。前端左侧只显示对话部分，右侧面板显示评估数据。每条消息后评估自动更新。

**5. 业务员画像累积**

每次有效对话都会更新 `salesperson_profile` 表：记录展现的能力、改进建议、经验等级。这个数据随时间积累，后续可以用于团队能力分析。


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

### 

```
src/main/java/com/example/demo/
├── entity/
│   ├── Customer.java
│   └── CommunicationRecord.java
│   ├── AgentSession.java          ← 获客智能体对话对象及对话画像
│   ├── AgentMessage.java          ← 获客智能体对话记录
│   └── SalespersonProfile.java    ← 业务员画像 
├── repository/
│   ├── CustomerRepository.java
│   └── CommunicationRecordRepository.java
│   ├── AgentSessionRepository.java    ← 获客智能体 对话节
│   ├── AgentMessageRepository.java    ← 获客智能体 对话记录
│   └── SalespersonProfileRepository.java  ← 业务员画像
├── service/
│   ├── CustomerAnalysisService.java    ← 转化：客户画像+分级
│   ├── CustomerRadarService.java       ← 雷达+抵达度+联络节点 (NEW)
│   ├── IntentRecognitionService.java   ← 获客：意图识别
│   └── WebsiteAnalysisService.java     ← 网站分析
│   └── AcquisitionAgentService.java   ← 获客智能体逻辑 (核心)
├── controller/
│   └── TradeController.java            ← 客户信息（不含意图识别） (UPDATED)
src/main/resources/static/
└── trade-dashboard.html                ← 前端 (UPDATED)
└── agent-chat.html                    ← 获客智能体+业务员评估（3层意图识别+1 兜底）


```

### 

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
