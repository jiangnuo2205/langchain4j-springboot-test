package com.example.demo.repository;

import com.example.demo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByGradeOrderByUpdatedAtDesc(String grade);

    List<Customer> findAllByOrderByUpdatedAtDesc();

    List<Customer> findByGradeInOrderByUpdatedAtDesc(List<String> grades);

    List<Customer> findByCountryIgnoreCaseOrderByUpdatedAtDesc(String country);

    List<Customer> findByIndustryContainingIgnoreCaseOrderByUpdatedAtDesc(String industry);
}
