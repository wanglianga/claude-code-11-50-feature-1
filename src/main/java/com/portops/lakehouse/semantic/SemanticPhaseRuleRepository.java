package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SemanticPhaseRuleRepository extends JpaRepository<SemanticPhaseRule, Long> {
    Optional<SemanticPhaseRule> findByDeviceType(String deviceType);
}
