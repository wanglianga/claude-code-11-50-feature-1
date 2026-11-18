package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SemanticCompileRunRepository extends JpaRepository<SemanticCompileRun, Long> {

    Optional<SemanticCompileRun> findTopByContainerNoOrderByRunSeqDesc(String containerNo);

    List<SemanticCompileRun> findByContainerNoOrderByRunSeqAsc(String containerNo);

    @Query("select distinct r.containerNo from SemanticCompileRun r")
    List<String> findAllContainerNos();
}
