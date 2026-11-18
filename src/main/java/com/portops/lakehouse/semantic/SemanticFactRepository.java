package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SemanticFactRepository extends JpaRepository<SemanticFact, Long> {

    List<SemanticFact> findByContainerNoAndRecordStatusOrderByStartBizTimeAscIdAsc(
            String containerNo, String recordStatus);

    List<SemanticFact> findByContainerNoOrderByVersionAscIdAsc(String containerNo);

    List<SemanticFact> findByRecordStatusAndChargeableTrueOrderByContainerNoAscIdAsc(String recordStatus);

    List<SemanticFact> findByRunId(Long runId);

    /** 版本更替：旧事实只能被标记 SUPERSEDED（触发器校验内容列不变）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SemanticFact f set f.recordStatus = 'SUPERSEDED', f.supersededByRun = :runSeq " +
            "where f.containerNo = :containerNo and f.recordStatus = 'ACTIVE'")
    int supersedeActive(@Param("containerNo") String containerNo, @Param("runSeq") Long runSeq);
}
