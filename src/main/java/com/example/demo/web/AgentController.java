package com.example.demo.web;

import com.example.demo.entity.AgentMessage;
import com.example.demo.entity.AgentSession;
import com.example.demo.entity.SalespersonProfile;
import com.example.demo.repository.SalespersonProfileRepository;
import com.example.demo.service.AcquisitionAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AcquisitionAgentService agentService;
    private final SalespersonProfileRepository profileRepo;

    public AgentController(AcquisitionAgentService agentService,
                           SalespersonProfileRepository profileRepo) {
        this.agentService = agentService;
        this.profileRepo = profileRepo;
    }

    /**
     * 发送消息（核心对话接口）
     * POST /api/agent/chat
     * Body: { "sessionId": null, "salespersonId": "sp_001", "message": "..." }
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("sessionId") != null ?
                Long.valueOf(body.get("sessionId").toString()) : null;
        String spId = (String) body.getOrDefault("salespersonId", "default");
        String message = (String) body.getOrDefault("message", "");

        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        try {
            AcquisitionAgentService.AgentReply result = agentService.chat(sessionId, spId, message);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", result.sessionId());
            response.put("reply", result.reply());
            response.put("evaluation", result.evaluation());
            response.put("validBusiness", result.validBusiness());
            response.put("costMs", result.costMs());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("agent.chat failed err={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 获取会话列表 */
    @GetMapping("/sessions")
    public List<AgentSession> listSessions(
            @RequestParam(defaultValue = "default") String salespersonId) {
        return agentService.listSessions(salespersonId);
    }

    /** 获取会话历史消息 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<AgentMessage> getMessages(@PathVariable Long sessionId) {
        return agentService.getSessionMessages(sessionId);
    }

    /** 获取业务员画像 */
    @GetMapping("/profile/{salespersonId}")
    public ResponseEntity<?> getProfile(@PathVariable String salespersonId) {
        return profileRepo.findBySalespersonId(salespersonId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
