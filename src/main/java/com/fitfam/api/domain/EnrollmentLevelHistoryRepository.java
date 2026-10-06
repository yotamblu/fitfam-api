package com.fitfam.api.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentLevelHistoryRepository extends JpaRepository<EnrollmentLevelHistory, UUID> {

}
