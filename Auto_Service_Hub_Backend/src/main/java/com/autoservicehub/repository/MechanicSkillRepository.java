package com.autoservicehub.repository;

import com.autoservicehub.entity.MechanicSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA repository for MechanicSkill. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface MechanicSkillRepository extends JpaRepository<MechanicSkill, Long>, JpaSpecificationExecutor<MechanicSkill> {
	List<MechanicSkill> findByMechanicIdOrderBySkillNameAsc(Long mechanicId);
	boolean existsByMechanicIdAndSkillNameIgnoreCase(Long mechanicId, String skillName);
	java.util.Optional<MechanicSkill> findByIdAndMechanicId(Long id, Long mechanicId);
}
