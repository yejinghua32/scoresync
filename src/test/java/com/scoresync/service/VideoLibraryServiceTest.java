package com.scoresync.service;

import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.repository.VideoSourceRepository;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.MediaUnavailableException;
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
        sourceRepo = new VideoSourceRepository(jdbc);
        assetRepo = new VideoAssetRepository(jdbc);
        library = new VideoLibraryService(sourceRepo, assetRepo);
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
