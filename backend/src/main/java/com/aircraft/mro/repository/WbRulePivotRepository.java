package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbRulePivot;

public interface WbRulePivotRepository extends JpaRepository<WbRulePivot, Long> {

    List<WbRulePivot> findAllByRuleRevisionIdOrderByIdAsc(Long ruleRevisionId);

    void deleteByRuleRevisionId(Long ruleRevisionId);
}
