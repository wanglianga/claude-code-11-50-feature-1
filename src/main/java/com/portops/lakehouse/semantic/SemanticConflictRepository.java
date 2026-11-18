package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SemanticConflictRepository extends JpaRepository<SemanticConflict, Long> {

    List<SemanticConflict> findByContainerNoAndRecordStatusOrderByCreatedAtAsc(
            String containerNo, String recordStatus);

    List<SemanticConflict> findByRecordStatusOrderByContainerNoAscIdAsc(String recordStatus);

    List<SemanticConflict> findByRunId(Long runId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SemanticConflict c set c.recordStatus = 'SUPERSEDED', c.supersededByRun = :runSeq " +
            "where c.containerNo = :containerNo and c.recordStatus = 'ACTIVE'")
    int supersedeActive(@Param("containerNo") String containerNo, @Param("runSeq") Long runSeq);
}
