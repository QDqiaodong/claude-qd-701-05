package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbEnvelopeNode;

public interface WbEnvelopeNodeRepository extends JpaRepository<WbEnvelopeNode, Long> {

    List<WbEnvelopeNode> findAllByRuleRevisionIdOrderByBoundaryKindAscWeightKgAsc(Long ruleRevisionId);

    void deleteByRuleRevisionId(Long ruleRevisionId);
}
