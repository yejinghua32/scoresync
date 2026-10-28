package com.scoresync.service;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.domain.VideoAsset;
import com.scoresync.domain.VideoBulkDeleteResult;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.repository.VideoSourceRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.MediaUnavailableException;
import com.scoresync.support.VideoInUseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoLibraryServiceTest {

    @TempDir
    Path tempDir;

    private VideoSourceRepository sourceRepo;
    private VideoAssetRepository assetRepo;
    private MatchProjectRepository projectRepo;
    private VideoLibraryService library;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE video_source (
                  id INTEGER PRIMARY KEY,
                  root_path TEXT NOT NULL UNIQUE,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE video_asset (
                  id INTEGER PRIMARY KEY,
                  source_id INTEGER NULL REFERENCES video_source(id),
                  absolute_path TEXT NOT NULL UNIQUE,
                  display_name TEXT NOT NULL,
                  file_size INTEGER NOT NULL,
                  modified_at INTEGER NOT NULL,
                  origin_type TEXT NOT NULL CHECK (origin_type IN ('SCANNED','PICKED')),
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE match_project (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id), duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
                  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK (target_wins >= 1),
                  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC',
                  first_server TEXT NOT NULL DEFAULT 'A' CHECK (first_server IN ('A','B')),
                  show_project_name INTEGER NOT NULL DEFAULT 0,
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
        sourceRepo = new VideoSourceRepository(jdbc);
        assetRepo = new VideoAssetRepository(jdbc);
        projectRepo = new MatchProjectRepository(jdbc);
        library = new VideoLibraryService(sourceRepo, assetRepo, projectRepo, new ClipProjectRepository(jdbc));
    }

    @Test
    void scanOnlyRegistersMp4AndWebm(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectory(sourceDir);
        Files.createFile(sourceDir.resolve("match.mp4"));
        Files.writeString(sourceDir.resolve("notes.txt"), "notes");
        Files.createDirectories(sourceDir.resolve("nested"));
        Files.createFile(sourceDir.resolve("nested").resolve("clip.webm"));

        long sourceId = library.addSource(sourceDir.toString()).id();
        List<VideoAsset> assets = library.scan(sourceId);

        assertThat(assets).extracting(VideoAsset::displayName)
                .containsExactlyInAnyOrder("match.mp4", "clip.webm");
    }

    @Test
    void duplicateScanDoesNotProduceDuplicateAssets(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source2");
        Files.createDirectory(sourceDir);
        Files.createFile(sourceDir.resolve("match.mp4"));

        long sourceId = library.addSource(sourceDir.toString()).id();
        library.scan(sourceId);
        List<VideoAsset> secondScan = library.scan(sourceId);

        assertThat(secondScan).hasSize(1);
    }

    @Test
    void readingDeletedAssetThrowsMediaUnavailableException(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source3");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("match.mp4");
        Files.createFile(videoFile);

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        Files.delete(videoFile);

        assertThatThrownBy(() -> library.readAsset(asset.id()))
                .isInstanceOf(MediaUnavailableException.class);
    }

    @Test
    void removeAssetDropsRegistrationButKeepsFileOnDisk(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_remove");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("match.mp4");
        Files.createFile(videoFile);

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        library.removeAsset(asset.id());

        assertThat(assetRepo.findAll()).isEmpty();
        assertThat(videoFile).exists();
    }

    @Test
    void removeAllAssetsDropsEveryUnusedRegistration(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_remove_all");
        Files.createDirectory(sourceDir);
        Path first = sourceDir.resolve("a.mp4");
        Path second = sourceDir.resolve("b.mp4");
        Files.createFile(first);
        Files.createFile(second);

        long sourceId = library.addSource(sourceDir.toString()).id();
        library.scan(sourceId);
        assertThat(assetRepo.findAll()).hasSize(2);

        VideoBulkDeleteResult result = library.removeAllAssets();

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
        assertThat(assetRepo.findAll()).isEmpty();
        // 只移除登记信息，磁盘文件保持不变
        assertThat(first).exists();
        assertThat(second).exists();
    }

    @Test
    void removeAllAssetsKeepsVideosStillUsedByProject(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_remove_all_used");
        Files.createDirectory(sourceDir);
        Files.createFile(sourceDir.resolve("used.mp4"));
        Files.createFile(sourceDir.resolve("free.mp4"));

        long sourceId = library.addSource(sourceDir.toString()).id();
        List<VideoAsset> assets = library.scan(sourceId);
        VideoAsset used = assets.stream()
                .filter(asset -> "used.mp4".equals(asset.displayName()))
                .findFirst()
                .orElseThrow();
        projectRepo.insert("决赛", used.id(), 60_000L, "甲", "乙", 2,
                ScoreboardTemplate.CLASSIC, PlayerSide.A);

        VideoBulkDeleteResult result = library.removeAllAssets();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly("used.mp4");
        assertThat(assetRepo.findAll()).extracting(VideoAsset::displayName).containsExactly("used.mp4");
    }

    @Test
    void removeAssetRejectsVideoStillUsedByProject(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_in_use");
        Files.createDirectory(sourceDir);
        Files.createFile(sourceDir.resolve("match.mp4"));

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);
        projectRepo.insert("决赛", asset.id(), 60_000L, "甲", "乙", 2,
                ScoreboardTemplate.CLASSIC, PlayerSide.A);

        assertThatThrownBy(() -> library.removeAsset(asset.id()))
                .isInstanceOf(VideoInUseException.class)
                .hasMessageContaining("该视频正被 1 个项目使用，无法移除");

        assertThat(assetRepo.findAll()).hasSize(1);
    }

    @Test
    void removeAssetRejectsVideoStillUsedByClipProject(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_clip_in_use");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("match.mp4");
        Files.createFile(videoFile);

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);
        new ClipProjectRepository(jdbc).insert("剪辑", asset.id(), 10, 5,
                Files.size(videoFile), Files.getLastModifiedTime(videoFile).toMillis());

        assertThatThrownBy(() -> library.removeAsset(asset.id()))
                .isInstanceOf(VideoInUseException.class)
                .hasMessageContaining("该视频正被 1 个项目使用，无法移除");

        assertThat(assetRepo.findAll()).hasSize(1);
    }

    @Test
    void removeAssetRejectsUnknownAsset() {
        assertThatThrownBy(() -> library.removeAsset(9_999L))
                .isInstanceOf(DomainNotFoundException.class);
    }

    @Test
    void addSourceRejectsNonExistentPath() {
        assertThatThrownBy(() -> library.addSource("C:/non/existent/path"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("视频目录不可读取");
    }

    @Test
    void addSourceRejectsNonDirectory() throws IOException {
        Path file = tempDir.resolve("notadir");
        Files.createFile(file);
        assertThatThrownBy(() -> library.addSource(file.toString()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("视频目录不可读取");
    }

    @Test
    void scannedAssetPathMustBeWithinSourceRoot(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source4");
        Files.createDirectory(sourceDir);
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectory(outsideDir);
        Files.createFile(outsideDir.resolve("evil.mp4"));

        library.addSource(sourceDir.toString());

        assertThatThrownBy(() -> library.registerPicked(outsideDir.resolve("evil.mp4")))
                .isInstanceOf(DomainValidationException.class);
    }
}
