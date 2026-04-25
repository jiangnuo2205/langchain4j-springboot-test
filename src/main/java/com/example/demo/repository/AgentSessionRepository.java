// === AgentSessionRepository.java ===
package com.example.demo.repository;

import com.example.demo.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    List<AgentSession> findBySalespersonIdOrderByUpdatedAtDesc(String salespersonId);
}
