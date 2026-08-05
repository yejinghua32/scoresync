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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStreamingServiceTest {

    @TempDir
    Path tempDir;

    private VideoSourceRepository sourceRepo;
    private VideoAssetRepository assetRepo;
    private VideoLibraryService library;
    private MediaStreamingService streaming;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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
        streaming = new MediaStreamingService(assetRepo);
    }

    @Test
    void rangeRequestReturnsCorrectBytes(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        MediaStreamingService.StreamContent content = streaming.open(asset.id(), "bytes=2-5");

        assertThat(content.body().readAllBytes()).isEqualTo("2345".getBytes());
        assertThat(content.start()).isEqualTo(2L);
        assertThat(content.end()).isEqualTo(5L);
        assertThat(content.totalLength()).isEqualTo(10L);
        assertThat(content.length()).isEqualTo(4L);
    }

    @Test
    void noRangeReturnsFullContent(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source2");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test2.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        MediaStreamingService.StreamContent content = streaming.open(asset.id(), null);

        assertThat(content.body().readAllBytes()).isEqualTo("0123456789".getBytes());
        assertThat(content.start()).isEqualTo(0L);
        assertThat(content.end()).isEqualTo(9L);
        assertThat(content.totalLength()).isEqualTo(10L);
    }

    @Test
    void rangeBeyondFileSizeIsRejected(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source3");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test3.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        assertThatThrownBy(() -> streaming.open(asset.id(), "bytes=20-30"))
                .isInstanceOf(MediaUnavailableException.class);
    }

    @Test
    void openEndedRangeIsSupported(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_open_ended");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test_open.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        // 浏览器加载 <video> 元数据时会发送形如 "bytes=0-" 的 open-ended range 请求
        MediaStreamingService.StreamContent content = streaming.open(asset.id(), "bytes=0-");

        assertThat(content.body().readAllBytes()).isEqualTo("0123456789".getBytes());
        assertThat(content.start()).isEqualTo(0L);
        assertThat(content.end()).isEqualTo(9L);
        assertThat(content.totalLength()).isEqualTo(10L);
        assertThat(content.length()).isEqualTo(10L);
    }

    @Test
    void rangeEndBeyondFileSizeIsClamped(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source_clamp");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test_clamp.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        // 当请求的 end 超过文件大小时，应按 RFC 7233 截断到文件末尾，而非报错
        MediaStreamingService.StreamContent content = streaming.open(asset.id(), "bytes=5-100");

        assertThat(content.body().readAllBytes()).isEqualTo("56789".getBytes());
        assertThat(content.start()).isEqualTo(5L);
        assertThat(content.end()).isEqualTo(9L);
        assertThat(content.totalLength()).isEqualTo(10L);
        assertThat(content.length()).isEqualTo(5L);
    }

    @Test
    void multipleRangesAreRejected(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source4");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test4.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        assertThatThrownBy(() -> streaming.open(asset.id(), "bytes=0-3,5-7"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void deletedAssetThrowsMediaUnavailableException(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source5");
        Files.createDirectory(sourceDir);
        Path videoFile = sourceDir.resolve("test5.mp4");
        Files.writeString(videoFile, "0123456789");

        long sourceId = library.addSource(sourceDir.toString()).id();
        VideoAsset asset = library.scan(sourceId).get(0);

        Files.delete(videoFile);

        assertThatThrownBy(() -> streaming.open(asset.id(), null))
                .isInstanceOf(MediaUnavailableException.class);
    }
}
