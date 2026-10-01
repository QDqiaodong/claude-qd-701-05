package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbEvent;

public interface WbEventRepository extends JpaRepository<WbEvent, Long> {

    List<WbEvent> findAllByOrderByEventAtDescIdDesc();

    List<WbEvent> findAllByAircraftIdOrderByEventAtDescIdDesc(Long aircraftId);
}
