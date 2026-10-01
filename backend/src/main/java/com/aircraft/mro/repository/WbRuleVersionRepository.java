package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.WbRuleVersion;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface WbRuleVersionRepository extends JpaRepository<WbRuleVersion, Long> {

    List<WbRuleVersion> findAllByOrderByModelAscRevisionNoAsc();

    List<WbRuleVersion> findAllByModelOrderByRevisionNoAsc(String model);

    Optional<WbRuleVersion> findByModelAndRevisionNo(String model, String revisionNo);

    List<WbRuleVersion> findAllByStatusOrderByEffectiveDateDesc(String status);

    /** 发布新版本时对同机型全部版本加锁串行化，避免两版同时生效 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select r from WbRuleVersion r where r.model = :model")
    List<WbRuleVersion> findAllByModelForUpdate(String model);
}
