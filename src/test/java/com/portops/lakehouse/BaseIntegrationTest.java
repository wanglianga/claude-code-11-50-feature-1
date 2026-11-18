package com.portops.lakehouse;

import com.portops.lakehouse.common.EventType;
import com.portops.lakehouse.common.SourceSystem;
import com.portops.lakehouse.ingest.EventIngestRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("""
                TRUNCATE TABLE ingest_event, lake_commit, commit_conflict, voyage_booking,
                    voyage_manifest, manifest_item, revision_candidate, carrier_receipt,
                    recon_run, recon_diff, fee_adjustment,
                    semantic_compile_run, semantic_fact, semantic_conflict,
                    manifest_job_snapshot, manifest_job_snapshot_item
                RESTART IDENTITY CASCADE
                """);
    }

    protected EventIngestRequest ev(SourceSystem src, long seq, String container, String partition,
                                    EventType type, String bizTime, String voyage, String payload) {
        return new EventIngestRequest(src, seq, container, partition, type,
                OffsetDateTime.parse(bizTime), voyage, payload);
    }
}
