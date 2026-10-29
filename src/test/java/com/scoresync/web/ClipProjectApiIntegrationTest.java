package com.scoresync.web;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipThumbnailSpec;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.ClipSegmentRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.service.ClipProjectService;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.service.render.ClipFrameExtractor;
import com.scoresync.service.render.ClipFrameIndexBuilder;
import com.scoresync.service.render.ClipFrameIndexService;
import com.scoresync.service.render.ClipThumbnailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClipProjectApiIntegrationTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ClipProjectRepository projects;
    private ClipSegmentRepository segments;
    private Path videoFile;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-api-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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

        VideoAssetRepository assets = new VideoAssetRepository(jdbc);
        projects = new ClipProjectRepository(jdbc);
        ClipSegmentRepository segments = new ClipSegmentRepository(jdbc);
        this.segments = segments;
        ClipProjectService service = new ClipProjectService(projects, segments,
                new ClipSourceValidator(assets));

        AppProperties properties = new AppProperties();
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());
        ClipFrameIndexService indexService = new ClipFrameIndexService(projects,
                new ClipSourceValidator(assets), new FakeIndexBuilder(), properties);
        ClipThumbnailService thumbnailService = new ClipThumbnailService(indexService,
                new ClipSourceValidator(assets), new FakeExtractor(), properties);

        videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());

        mockMvc = MockMvcBuilders.standaloneSetup(new ClipProjectController(service, indexService, thumbnailService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createClipProjectReturnsCreatedWithDefaults() throws Exception {
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第一集\",\"videoId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("第一集"))
                .andExpect(jsonPath("$.videoAssetId").value(1))
                .andExpect(jsonPath("$.largeStep").value(10))
                .andExpect(jsonPath("$.smallStep").value(5))
                .andExpect(jsonPath("$.frameIndexStatus").value("PENDING"))
                .andExpect(jsonPath("$.segments").isEmpty());
    }

    @Test
    void createClipProjectRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"videoId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"));
    }

    @Test
    void createClipProjectRejectsUnknownVideo() throws Exception {
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第一集\",\"videoId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listClipProjectsReturnsCreatedProject() throws Exception {
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第一集\",\"videoId\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/clip-projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("第一集"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listClipProjectsFiltersBySearch() throws Exception {
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第一集\",\"videoId\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第二集\",\"videoId\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/clip-projects").param("search", "第一"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("第一集"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listClipProjectsPaginates() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/clip-projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"第" + i + "集\",\"videoId\":1}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/clip-projects").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/clip-projects").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void getClipProjectReturnsStoredSegments() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);

        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].type").value("KEEP"))
                .andExpect(jsonPath("$.segments[0].startFrame").value(0))
                .andExpect(jsonPath("$.segments[0].endFrame").value(10));

        mockMvc.perform(get("/api/clip-projects/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFrames").value(300))
                .andExpect(jsonPath("$.segments.length()").value(1));
    }

    @Test
    void submitSegmentsRejectsInvalidType() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);

        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"OTHER\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("KEEP")));
    }

    @Test
    void submitSegmentsRejectsOutOfRange() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);

        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":9999}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("超出视频帧范围")));
    }

    @Test
    void submitSegmentsRejectsBeforeIndexReady() throws Exception {
        long id = createProject();

        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"));
    }

    @Test
    void updateClipProjectPersistsSteps() throws Exception {
        long id = createProject();

        mockMvc.perform(put("/api/clip-projects/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名\",\"largeStep\":20,\"smallStep\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名"))
                .andExpect(jsonPath("$.largeStep").value(20))
                .andExpect(jsonPath("$.smallStep").value(8));
    }

    @Test
    void deleteClipProjectReturnsNoContent() throws Exception {
        long id = createProject();

        mockMvc.perform(delete("/api/clip-projects/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/clip-projects/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUnknownClipProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/clip-projects/{id}", 404L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void frameIndexStatusTriggersBuildAndReportsFrameCount() throws Exception {
        long id = createProject();

        mockMvc.perform(get("/api/clip-projects/{id}/frame-index", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("BUILDING"), org.hamcrest.Matchers.is("READY"))));

        for (int i = 0; i < 100; i++) {
            String status = mockMvc.perform(get("/api/clip-projects/{id}/frame-index", id))
                    .andReturn().getResponse().getContentAsString();
            if (status.contains("READY")) {
                mockMvc.perform(get("/api/clip-projects/{id}/frame-index", id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("READY"))
                        .andExpect(jsonPath("$.totalFrames").value(4));
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("帧索引未在预期时间内建立完成");
    }

    @Test
    void thumbnailReturnsImageForValidFrame() throws Exception {
        long id = createProject();
        awaitIndexReady(id);

        mockMvc.perform(get("/api/clip-projects/{id}/frames/{frameNo}/thumbnail", id, 2))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
    }

    @Test
    void thumbnailAcceptsBackgroundPriority() throws Exception {
        long id = createProject();
        awaitIndexReady(id);

        mockMvc.perform(get("/api/clip-projects/{id}/frames/{frameNo}/thumbnail", id, 2)
                        .header("X-Thumbnail-Priority", "background"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
    }

    @Test
    void thumbnailAcceptsLargeSize() throws Exception {
        long id = createProject();
        awaitIndexReady(id);

        mockMvc.perform(get("/api/clip-projects/{id}/frames/{frameNo}/thumbnail", id, 2)
                        .param("size", "large"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
    }

@Test
    void thumbnailRejectsOutOfRangeFrame() throws Exception {
        long id = createProject();
        awaitIndexReady(id);

        mockMvc.perform(get("/api/clip-projects/{id}/frames/{frameNo}/thumbnail", id, 99))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("帧序号超出范围")));
    }

    @Test
    void deleteSegmentRemovesItFromProject() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);
        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isOk());
        long segmentId = segments.findByProjectId(id).get(0).id();

        mockMvc.perform(delete("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(0));
    }

    @Test
    void deleteUnknownSegmentReturnsNotFound() throws Exception {
        long id = createProject();

        mockMvc.perform(delete("/api/clip-projects/{id}/segments/{segmentId}", id, 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void patchSegmentSetsFeaturedState() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);
        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].featured").value(false));
        long segmentId = segments.findByProjectId(id).get(0).id();

        mockMvc.perform(patch("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featured\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].featured").value(true));
    }

    @Test
    void patchSegmentRejectsFeaturingDropSegment() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);
        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"DROP\",\"startFrame\":0,\"endFrame\":10}]}"))
                .andExpect(status().isOk());
        long segmentId = segments.findByProjectId(id).get(0).id();

        mockMvc.perform(patch("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featured\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("只有保留片段可以设为精选")));
    }

    @Test
    void patchSegmentRejectsUnknownSegment() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);

        mockMvc.perform(patch("/api/clip-projects/{id}/segments/{segmentId}", id, 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featured\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void putSegmentUpdatesBoundsAndKeepsFeaturedState() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);
        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":20}]}"))
                .andExpect(status().isOk());
        long segmentId = segments.findByProjectId(id).get(0).id();
        mockMvc.perform(patch("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featured\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startFrame\":5,\"endFrame\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].startFrame").value(5))
                .andExpect(jsonPath("$.segments[0].endFrame").value(25))
                .andExpect(jsonPath("$.segments[0].featured").value(true));
    }

    @Test
    void putSegmentRejectsOutOfRangeBounds() throws Exception {
        long id = createProject();
        projects.updateFrameIndex(id, ClipFrameIndexStatus.READY, 300);
        mockMvc.perform(post("/api/clip-projects/{id}/segments", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[{\"type\":\"KEEP\",\"startFrame\":0,\"endFrame\":20}]}"))
                .andExpect(status().isOk());
        long segmentId = segments.findByProjectId(id).get(0).id();

        mockMvc.perform(put("/api/clip-projects/{id}/segments/{segmentId}", id, segmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startFrame\":0,\"endFrame\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("片段超出视频帧范围")));
    }

    /**
     * 轮询直到帧索引建立完成
     */
    private void awaitIndexReady(long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/clip-projects/{id}/frame-index", id));
            if (projects.findById(id).frameIndexStatus() == ClipFrameIndexStatus.READY) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("帧索引未在预期时间内建立完成");
    }

    private long createProject() throws Exception {
        String body = mockMvc.perform(post("/api/clip-projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第一集\",\"videoId\":1}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = body.replaceAll(".*\"id\":(\\d+).*", "$1");
        return Long.parseLong(id);
    }

    /**
     * 假索引构建器：返回固定四帧
     */
    static class FakeIndexBuilder extends ClipFrameIndexBuilder {
        FakeIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            return new ClipFrameIndex(
                    new long[]{0L, 40_000L, 90_000L, 160_000L},
                    new long[]{0L, 0L, 0L, 40_000L},
                    0L);
        }
    }

    /**
     * 假抽帧器：写出占位内容
     */
    static class FakeExtractor extends ClipFrameExtractor {
        FakeExtractor() {
            super(new AppProperties());
        }

        @Override
        public void extract(Path source, double seekSeconds, Path output, ClipThumbnailSpec spec) {
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(output, "fake-thumbnail");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
