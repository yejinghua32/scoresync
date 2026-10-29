package com.scoresync.repository;

import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipSegment;
import com.scoresync.domain.ClipSegmentType;
import com.scoresync.support.DomainNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipProjectRepositoryIntegrationTest {

    private JdbcTemplate jdbc;
    private ClipProjectRepository projects;
    private ClipSegmentRepository segments;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-project-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE video_asset (
                  id INTEGER PRIMARY KEY, source_id INTEGER NULL,
                  absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
                  file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL,
                  origin_type TEXT NOT NULL CHECK (origin_type IN ('SCANNED','PICKED')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE clip_project (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id),
                  large_step INTEGER NOT NULL DEFAULT 10 CHECK (large_step > 0),
                  small_step INTEGER NOT NULL DEFAULT 5 CHECK (small_step > 0),
                  source_file_size INTEGER NOT NULL, source_modified_at INTEGER NOT NULL,
                  total_frames INTEGER NULL,
                  frame_index_status TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (frame_index_status IN ('PENDING','BUILDING','READY','FAILED')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE clip_segment (
                  id INTEGER PRIMARY KEY,
                  project_id INTEGER NOT NULL REFERENCES clip_project(id) ON DELETE CASCADE,
                  segment_type TEXT NOT NULL CHECK (segment_type IN ('KEEP','DROP')),
                  start_frame INTEGER NOT NULL CHECK (start_frame >= 0),
                  end_frame INTEGER NOT NULL CHECK (end_frame > start_frame),
                  featured INTEGER NOT NULL DEFAULT 0 CHECK (featured IN (0,1)),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX idx_clip_segment_project ON clip_segment(project_id, start_frame)");

        projects = new ClipProjectRepository(jdbc);
        segments = new ClipSegmentRepository(jdbc);
    }

    @Test
    void createsProjectWithDefaultStepsAndPendingIndex() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");

        long id = projects.insert("第一集", 1L, 10, 5, 1024L, 99L);
        ClipProject project = projects.findById(id);

        assertThat(project.name()).isEqualTo("第一集");
        assertThat(project.videoAssetId()).isEqualTo(1L);
        assertThat(project.largeStep()).isEqualTo(10);
        assertThat(project.smallStep()).isEqualTo(5);
        assertThat(project.sourceFileSize()).isEqualTo(1024L);
        assertThat(project.sourceModifiedAt()).isEqualTo(99L);
        assertThat(project.totalFrames()).isNull();
        assertThat(project.frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.PENDING);
    }

    @Test
    void updatesFrameIndexStatusAndTotalFrames() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        long id = projects.insert("第一集", 1L, 10, 5, 1024L, 99L);

        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);

        ClipProject project = projects.findById(id);
        assertThat(project.frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.READY);
        assertThat(project.totalFrames()).isEqualTo(300);
    }

    @Test
    void updatesConfigSteps() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        long id = projects.insert("第一集", 1L, 10, 5, 1024L, 99L);
        ClipProject project = projects.findById(id);

        projects.updateConfig(new ClipProject(project.id(), "改名", project.videoAssetId(), 20, 8,
                project.sourceFileSize(), project.sourceModifiedAt(), null,
                project.frameIndexStatus(), project.createdAt(), project.updatedAt()));

        ClipProject updated = projects.findById(id);
        assertThat(updated.name()).isEqualTo("改名");
        assertThat(updated.largeStep()).isEqualTo(20);
        assertThat(updated.smallStep()).isEqualTo(8);
    }

    @Test
    void findsSegmentsOrderedByStartFrame() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        long projectId = projects.insert("第一集", 1L, 10, 5, 1024L, 99L);

        segments.insert(projectId, ClipSegmentType.DROP, 100, 200);
        segments.insert(projectId, ClipSegmentType.KEEP, 0, 50);
        segments.insert(projectId, ClipSegmentType.KEEP, 60, 80);

        List<ClipSegment> found = segments.findByProjectId(projectId);

        assertThat(found).extracting(ClipSegment::startFrame).containsExactly(0, 60, 100);
        assertThat(found).extracting(ClipSegment::type)
                .containsExactly(ClipSegmentType.KEEP, ClipSegmentType.KEEP, ClipSegmentType.DROP);
        assertThat(found.get(0).endFrame()).isEqualTo(50);
    }

    @Test
    void storesFeaturedFlagOnSegments() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        long projectId = projects.insert("第一集", 1L, 10, 5, 1024L, 99L);

        segments.insert(projectId, ClipSegmentType.KEEP, 0, 50, true);
        segments.insert(projectId, ClipSegmentType.KEEP, 60, 80);

        List<ClipSegment> found = segments.findByProjectId(projectId);

        assertThat(found.get(0).featured()).isTrue();
        assertThat(found.get(1).featured()).isFalse();
    }

    @Test
    void countsProjectsByVideoAsset() {
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, '/v/a.mp4', 'a.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                + "modified_at, origin_type, created_at, updated_at) VALUES (2, NULL, '/v/b.mp4', 'b.mp4', "
                + "1024, 99, 'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");

        projects.insert("A", 1L, 10, 5, 1024L, 99L);
        projects.insert("B", 1L, 10, 5, 1024L, 99L);
        projects.insert("C", 2L, 10, 5, 1024L, 99L);

        assertThat(projects.countByVideoAssetId(1L)).isEqualTo(2);
        assertThat(projects.countByVideoAssetId(2L)).isEqualTo(1);
        assertThat(projects.count()).isEqualTo(3);
    }

    @Test
    void unknownProjectThrowsNotFound() {
        assertThatThrownBy(() -> projects.findById(999L))
                .isInstanceOf(DomainNotFoundException.class);
    }
}
