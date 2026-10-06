package com.fitfam.api.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanLevelRepository extends JpaRepository<PlanLevel, UUID> {

	Optional<PlanLevel> findByPlanIdAndLevelNumber(UUID planId, int levelNumber);

	List<PlanLevel> findAllByOrderByLevelNumber();

}
