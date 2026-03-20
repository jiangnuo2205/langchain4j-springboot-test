import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class RagController {
    private final RagService ragService;
    private static final Logger logger = LoggerFactory.getLogger(RagController.class);

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/api/rag/stats")
    public StatsResponse getRagStats() {
        logger.info("/api/rag/stats called");
        return ragService.getStats();
    }
}