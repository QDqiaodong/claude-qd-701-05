package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbWeighingItem;

public interface WbWeighingItemRepository extends JpaRepository<WbWeighingItem, Long> {

    List<WbWeighingItem> findAllByWeighingIdOrderByLineNoAsc(Long weighingId);
}
