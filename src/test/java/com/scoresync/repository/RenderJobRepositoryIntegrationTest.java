package com.scoresync.repository;

import com.scoresync.domain.RenderJob;
import com.scoresync.domain.RenderJobStatus;
import com.scoresync.domain.ScoreboardTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RenderJobRepositoryIntegrationTest {

    private JdbcTemplate jdbc;
    private RenderJobRepository repository;
    private long matchId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-render-job-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE match_project (" +
                "  id INTEGER PRIMARY KEY, name TEXT NOT NULL," +
                "  video_asset_id INTEGER NOT NULL, duration_ms INTEGER NOT NULL," +
                "  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL," +
                "  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC'," +
                "  first_server TEXT NOT NULL DEFAULT 'A' CHECK (first_server IN ('A','B'))," +
                "  created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE render_job (" +
                "  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL," +
                "  template_code TEXT NOT NULL, status TEXT NOT NULL," +
                "  progress_percent INTEGER NOT NULL DEFAULT 0," +
                "  output_path TEXT NULL, failure_message TEXT NULL," +
                "  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL" +
                ")");

        jdbc.update("INSERT INTO match_project (id, name, video_asset_id, duration_ms, player_a, player_b, target_wins, scoreboard_template, first_server, created_at, updated_at) VALUES (1, '测试', 1, 5000, '甲', '乙', 2, 'CLASSIC', 'A', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        matchId = 1L;

        repository = new RenderJobRepository(jdbc);
    }

    @Test
    void insertCreatesQueuedJobWithZeroProgress() {
        long id = repository.insert(matchId, ScoreboardTemplate.CLASSIC);
        RenderJob job = repository.findById(id);

        assertThat(job.status()).isEqualTo(RenderJobStatus.QUEUED);
        assertThat(job.progressPercent()).isZero();
        assertThat(job.matchId()).isEqualTo(matchId);
        assertThat(job.templateCode()).isEqualTo(ScoreboardTemplate.CLASSIC);
        assertThat(job.outputPath()).isNull();
        assertThat(job.failureMessage()).isNull();
    }

    @Test
    void findActiveReturnsQueuedOrRunningJob() {
        long id = repository.insert(matchId, ScoreboardTemplate.CLASSIC);

        Optional<RenderJob> active = repository.findActive();
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(id);
    }

    @Test
    void findActiveReturnsEmptyWhenAllJobsFinished() {
        long id = repository.insert(matchId, ScoreboardTemplate.CLASSIC);
        RenderJob job = repository.findById(id);
        repository.update(new RenderJob(id, job.matchId(), job.templateCode(),
                RenderJobStatus.SUCCEEDED, 100, "/out/x.mp4", null,
                job.createdAt(), job.startedAt(), job.finishedAt()));

        assertThat(repository.findActive()).isEmpty();
    }

    @Test
    void updateChangesStatusProgressAndOutput() {
        long id = repository.insert(matchId, ScoreboardTemplate.CLASSIC);
        RenderJob job = repository.findById(id);

        repository.update(new RenderJob(id, job.matchId(), job.templateCode(),
                RenderJobStatus.RUNNING, 50, null, null,
                job.createdAt(), job.createdAt(), null));
        RenderJob running = repository.findById(id);

        assertThat(running.status()).isEqualTo(RenderJobStatus.RUNNING);
        assertThat(running.progressPercent()).isEqualTo(50);
    }

    @Test
    void findByStatusReturnsMatchingJobs() {
        long first = repository.insert(matchId, ScoreboardTemplate.CLASSIC);
        RenderJob job = repository.findById(first);
        repository.update(new RenderJob(first, job.matchId(), job.templateCode(),
                RenderJobStatus.SUCCEEDED, 100, "/out/a.mp4", null,
                job.createdAt(), job.createdAt(), job.createdAt()));

        repository.insert(matchId, ScoreboardTemplate.MODERN);

        assertThat(repository.findByStatus(RenderJobStatus.SUCCEEDED)).hasSize(1);
        assertThat(repository.findByStatus(RenderJobStatus.QUEUED)).hasSize(1);
    }
}
