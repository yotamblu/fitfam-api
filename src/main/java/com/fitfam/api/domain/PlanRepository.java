package com.fitfam.api.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

	List<Plan> findAllByOrderBySortOrder();

	List<Plan> findBySlugIn(Collection<String> slugs);

}
