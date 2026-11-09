package com.otonomc3.core.mission.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<MissionEntity, Long> {

	List<MissionEntity> findAllByOrderByIdAsc();

}
