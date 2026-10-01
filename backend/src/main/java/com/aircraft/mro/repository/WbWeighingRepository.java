package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbWeighing;

public interface WbWeighingRepository extends JpaRepository<WbWeighing, Long> {

    List<WbWeighing> findAllByAircraftIdOrderByWeighingDateDescIdDesc(Long aircraftId);

    List<WbWeighing> findAllByOrderByWeighingDateDescIdDesc();

    Optional<WbWeighing> findByWeighingNo(String weighingNo);
}
