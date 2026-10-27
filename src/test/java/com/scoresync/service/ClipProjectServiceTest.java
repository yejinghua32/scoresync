package com.scoresync.service;

import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipSegment;
import com.scoresync.domain.ClipSegmentSpec;
import com.scoresync.domain.ClipSegmentType;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.ClipSegmentRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipProjectServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ClipProjectRepository projects;
    private ClipSegmentRepository segments;
    private ClipProjectService service;
    private Path videoFile;

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = Files.createTempFile("clip-service-db", ".db");
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
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");

        projects = new ClipProjectRepository(jdbc);
        segments = new ClipSegmentRepository(jdbc);
        service = new ClipProjectService(projects, segments, new ClipSourceValidator(
                new com.scoresync.repository.VideoAssetRepository(jdbc)));

        videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());
    }

    @Test
    void createsProjectWithSourceFingerprintAndDefaultSteps() throws IOException {
        var detail = service.create("第一集", 1L);

        ClipProject project = detail.project();
        assertThat(project.name()).isEqualTo("第一集");
        assertThat(project.largeStep()).isEqualTo(10);
        assertThat(project.smallStep()).isEqualTo(5);
        assertThat(project.sourceFileSize()).isEqualTo(Files.size(videoFile));
        assertThat(project.sourceModifiedAt())
                .isEqualTo(Files.getLastModifiedTime(videoFile).toMillis());
        assertThat(project.frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.PENDING);
        assertThat(project.totalFrames()).isNull();
        assertThat(detail.video().displayName()).isEqualTo("source.mp4");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create("  ", 1L))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("剪辑项目名称不能为空");
    }

    @Test
    void rejectsUnknownVideo() {
        assertThatThrownBy(() -> service.create("第一集", 42L))
                .isInstanceOf(com.scoresync.support.DomainNotFoundException.class);
    }

    @Test
    void rejectsMissingSourceFile() {
        jdbc.update("UPDATE video_asset SET absolute_path = ? WHERE id = 1",
                tempDir.resolve("missing.mp4").toString());

        assertThatThrownBy(() -> service.create("第一集", 1L))
                .isInstanceOf(com.scoresync.support.MediaUnavailableException.class);
    }

    @Test
    void updatesNameAndSteps() {
        long id = service.create("第一集", 1L).project().id();

        service.updateConfig(id, "改名", 20, 8);

        ClipProject project = projects.findById(id);
        assertThat(project.name()).isEqualTo("改名");
        assertThat(project.largeStep()).isEqualTo(20);
        assertThat(project.smallStep()).isEqualTo(8);
    }

    @Test
    void rejectsNonPositiveSteps() {
        long id = service.create("第一集", 1L).project().id();

        assertThatThrownBy(() -> service.updateConfig(id, null, 0, 5))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("大步长必须是正整数");
        assertThatThrownBy(() -> service.updateConfig(id, null, 10, -1))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("小步长必须是正整数");
    }

    @Test
    void commitsSegmentsOnlyAfterIndexIsReady() {
        long id = service.create("第一集", 1L).project().id();

        assertThatThrownBy(() -> service.commitSegments(id,
                List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("帧索引尚未建立完成");
    }

    @Test
    void commitsAndStoresSegments() {
        long id = readyProject(300);

        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 20, 30)));

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).extracting(ClipSegment::startFrame).containsExactly(0, 20);
        assertThat(stored).extracting(ClipSegment::type)
                .containsExactly(ClipSegmentType.KEEP, ClipSegmentType.DROP);
    }

    @Test
    void newSegmentSplitsOverlappingOldSegment() {
        long id = readyProject(300);
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 10, 50)));

        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 20, 30)));

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).hasSize(3);
        assertThat(stored.get(0).type()).isEqualTo(ClipSegmentType.KEEP);
        assertThat(stored.get(0).startFrame()).isEqualTo(10);
        assertThat(stored.get(0).endFrame()).isEqualTo(20);
        assertThat(stored.get(1).type()).isEqualTo(ClipSegmentType.DROP);
        assertThat(stored.get(1).startFrame()).isEqualTo(20);
        assertThat(stored.get(1).endFrame()).isEqualTo(30);
        assertThat(stored.get(2).type()).isEqualTo(ClipSegmentType.KEEP);
        assertThat(stored.get(2).startFrame()).isEqualTo(30);
        assertThat(stored.get(2).endFrame()).isEqualTo(50);
    }

    @Test
    void newSegmentFullyReplacesOldSegment() {
        long id = readyProject(300);
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 10, 50)));

        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 0, 100)));

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).type()).isEqualTo(ClipSegmentType.DROP);
        assertThat(stored.get(0).startFrame()).isEqualTo(0);
        assertThat(stored.get(0).endFrame()).isEqualTo(100);
    }

    @Test
    void adjacentSameTypeSegmentsAreMerged() {
        long id = readyProject(300);

        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 10, 20)));

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).startFrame()).isEqualTo(0);
        assertThat(stored.get(0).endFrame()).isEqualTo(20);
    }

    @Test
    void adjacentDifferentTypeSegmentsAreKeptSeparate() {
        long id = readyProject(300);

        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 10, 20)));

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).hasSize(2);
    }

    @Test
    void rejectsInvalidRanges() {
        long id = readyProject(300);

        assertThatThrownBy(() -> service.commitSegments(id,
                List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, -1, 10))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("起始帧不能为负数");
        assertThatThrownBy(() -> service.commitSegments(id,
                List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 30, 30))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("结束帧必须大于起始帧");
        assertThatThrownBy(() -> service.commitSegments(id,
                List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 30, 10))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("结束帧必须大于起始帧");
        assertThatThrownBy(() -> service.commitSegments(id,
                List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 301))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("片段超出视频帧范围");
    }

    @Test
    void rejectsEmptySubmission() {
        long id = readyProject(300);

        assertThatThrownBy(() -> service.commitSegments(id, List.of()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("没有需要提交的片段");
    }

    @Test
    void concurrentCommitsKeepBothSegments() throws Exception {
        long id = readyProject(300);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                await(start);
                service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
                return null;
            });
            executor.submit(() -> {
                await(start);
                service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 100, 110)));
                return null;
            });
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        List<ClipSegment> stored = segments.findByProjectId(id);
        assertThat(stored).extracting(ClipSegment::startFrame).containsExactlyInAnyOrder(0, 100);
    }

    @Test
    void deletesProjectAndItsSegments() {
        long id = readyProject(300);
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));

        service.delete(id);

        assertThat(segments.findByProjectId(id)).isEmpty();
        assertThat(projects.count()).isZero();
    }

    @Test
    void deletesSingleSegmentAndKeepsOthers() {
        long id = readyProject(300);
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 20, 30)));
        List<ClipSegment> stored = segments.findByProjectId(id);
        long dropId = stored.stream()
                .filter(segment -> segment.type() == ClipSegmentType.DROP)
                .findFirst()
                .orElseThrow()
                .id();

        service.deleteSegment(id, dropId);

        List<ClipSegment> remaining = segments.findByProjectId(id);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).type()).isEqualTo(ClipSegmentType.KEEP);
        assertThat(remaining.get(0).startFrame()).isZero();
    }

    @Test
    void rejectsDeletingSegmentOfAnotherProject() {
        long first = readyProject(300);
        long second = readyProject(300);
        service.commitSegments(first, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        long segmentId = segments.findByProjectId(first).get(0).id();

        assertThatThrownBy(() -> service.deleteSegment(second, segmentId))
                .isInstanceOf(DomainNotFoundException.class);
        assertThat(segments.findByProjectId(first)).hasSize(1);
    }

    @Test
    void rejectsDeletingUnknownSegment() {
        long id = readyProject(300);

        assertThatThrownBy(() -> service.deleteSegment(id, 999L))
                .isInstanceOf(DomainNotFoundException.class);
    }

    @Test
    void countsKeptFrames() {
        long id = readyProject(300);
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 0, 10)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.KEEP, 20, 35)));
        service.commitSegments(id, List.of(new ClipSegmentSpec(ClipSegmentType.DROP, 40, 50)));

        assertThat(service.keptFrameCount(id)).isEqualTo(25);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 创建一个帧索引已就绪的项目
     */
    private long readyProject(int totalFrames) {
        long id = service.create("第一集", 1L).project().id();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, totalFrames);
        return id;
    }
}
