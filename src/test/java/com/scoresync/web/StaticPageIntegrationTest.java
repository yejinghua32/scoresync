package com.scoresync.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
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
                .andExpect(content().string(containsString("video-count")));
        mockMvc.perform(get("/editor.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"match-video\"")))
                .andExpect(content().string(containsString("id=\"event-list\"")));
    }
}
