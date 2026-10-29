package com.scoresync.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaticPageIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void servesVideoLibraryPageAndEditorPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("video-count")))
                .andExpect(content().string(containsString("id=\"clip-project-list\"")))
                .andExpect(content().string(containsString("id=\"clip-project-form\"")))
                // 比分项目、视频库、剪辑项目各有独立面板
                .andExpect(content().string(containsString("data-view=\"projects\"")))
                .andExpect(content().string(containsString("data-view=\"videos\"")))
                .andExpect(content().string(containsString("data-view=\"clips\"")))
                .andExpect(content().string(containsString("id=\"view-clips\"")))
                .andExpect(content().string(containsString("id=\"clip-project-search\"")));
        mockMvc.perform(get("/editor.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"match-video\"")))
                .andExpect(content().string(containsString("id=\"event-list\"")));
    }

    @Test
    void servesClipEditorPage() throws Exception {
        mockMvc.perform(get("/clip-editor.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"clip-video\"")))
                .andExpect(content().string(containsString("id=\"clip-timeline\"")))
                .andExpect(content().string(containsString("id=\"clip-timeline-ruler\"")))
                .andExpect(content().string(containsString("id=\"clip-keep-timeline\"")))
                .andExpect(content().string(containsString("id=\"clip-keep-timeline-ruler\"")))
                // 当前帧缩略图条已取消，片段列表放在右侧
                .andExpect(content().string(not(containsString("id=\"clip-strip\""))))
                .andExpect(content().string(containsString("id=\"clip-segment-list\"")))
                // 保留与舍弃区间统一由时间轴表示，不再单独渲染条带
                .andExpect(content().string(not(containsString("id=\"clip-keep-band\""))))
                .andExpect(content().string(not(containsString("id=\"clip-drop-band\""))))
                .andExpect(content().string(containsString("id=\"clip-large-step\"")))
                .andExpect(content().string(containsString("id=\"clip-small-step\"")))
                // 导出范围可选全部保留或仅精选
                .andExpect(content().string(containsString("id=\"clip-export-mode\"")))
                .andExpect(content().string(containsString("value=\"FEATURED\"")))
                .andExpect(content().string(containsString("id=\"clip-export-hint\"")));
    }

    @Test
    void clipEditorPagePlacesSourceTimelineUnderVideo() throws Exception {
        String page = mockMvc.perform(get("/clip-editor.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 源时间轴整体在视频之后，保留帧时间轴在源时间轴之后
        int video = page.indexOf("id=\"clip-video\"");
        int sourceTimeline = page.indexOf("id=\"clip-timeline\"");
        int keepTimeline = page.indexOf("id=\"clip-keep-timeline\"");
        assertThat(video).isPositive();
        assertThat(sourceTimeline).isGreaterThan(video);
        assertThat(keepTimeline).isGreaterThan(sourceTimeline);
    }

    @Test
    void servesClipEditorScript() throws Exception {
        mockMvc.perform(get("/js/clip-editor.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/clip-projects")));
    }
}
