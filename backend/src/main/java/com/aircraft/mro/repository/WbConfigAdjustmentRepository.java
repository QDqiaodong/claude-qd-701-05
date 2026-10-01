package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbConfigAdjustment;

public interface WbConfigAdjustmentRepository extends JpaRepository<WbConfigAdjustment, Long> {

    List<WbConfigAdjustment> findAllByConfigVersionIdOrderByLineNoAsc(Long configVersionId);

    List<WbConfigAdjustment> findAllByConfigVersionIdInOrderByLineNoAsc(List<Long> versionIds);
}
