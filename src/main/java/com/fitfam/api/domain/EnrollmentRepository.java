package com.fitfam.api.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

	@Query("""
			select e from Enrollment e
			join fetch e.plan p
			join fetch e.currentLevel
			where e.user.id in :userIds
			order by p.sortOrder
			""")
	List<Enrollment> findAllForUsers(@Param("userIds") Collection<UUID> userIds);

}
