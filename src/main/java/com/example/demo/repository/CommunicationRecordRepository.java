package com.example.demo.repository;

import com.example.demo.entity.CommunicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommunicationRecordRepository extends JpaRepository<CommunicationRecord, Long> {

    List<CommunicationRecord> findByCustomerIdOrderByCommunicatedAtDesc(Long customerId);
}
