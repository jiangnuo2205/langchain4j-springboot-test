package com.example.demo.repository;

import com.example.demo.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    List<AgentMessage> findBySessionIdOrderBySeqNumAsc(Long sessionId);
    List<AgentMessage> findBySessionIdAndSeqNumGreaterThanOrderBySeqNumAsc(Long sessionId, int seqNum);
}
