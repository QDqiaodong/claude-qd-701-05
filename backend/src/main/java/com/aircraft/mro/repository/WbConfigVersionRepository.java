package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.WbConfigVersion;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface WbConfigVersionRepository extends JpaRepository<WbConfigVersion, Long> {

    List<WbConfigVersion> findAllByAircraftIdOrderByVersionNoAsc(Long aircraftId);

    Optional<WbConfigVersion> findByActiveAircraftId(Long aircraftId);

    long countByAircraftId(Long aircraftId);
    /** 派生时锁定本机整条链，保证"只能从当前版本派生"在并发下成立 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select v from WbConfigVersion v where v.aircraftId = :aircraftId")
    List<WbConfigVersion> findChainForUpdate(Long aircraftId);
}
