package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManifestJobSnapshotRepository extends JpaRepository<ManifestJobSnapshot, Long> {
    Optional<ManifestJobSnapshot> findByManifestId(Long manifestId);
}
