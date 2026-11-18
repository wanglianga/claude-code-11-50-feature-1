package com.portops.lakehouse.semantic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManifestJobSnapshotItemRepository extends JpaRepository<ManifestJobSnapshotItem, Long> {
    List<ManifestJobSnapshotItem> findBySnapshotIdOrderByContainerNoAscIdAsc(Long snapshotId);
}
