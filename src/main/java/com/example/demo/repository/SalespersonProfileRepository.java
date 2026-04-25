package com.example.demo.repository;

import com.example.demo.entity.SalespersonProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SalespersonProfileRepository extends JpaRepository<SalespersonProfile, Long> {
    Optional<SalespersonProfile> findBySalespersonId(String salespersonId);
}
