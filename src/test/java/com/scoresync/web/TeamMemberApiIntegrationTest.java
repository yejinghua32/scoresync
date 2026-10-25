package com.scoresync.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scoresync.domain.TeamMember;
import com.scoresync.repository.TeamMemberRepository;
import com.scoresync.service.TeamMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 队员名单接口的手工装配集成测试
 */
class TeamMemberApiIntegrationTest {

    private MockMvc mockMvc;
    private TeamMemberRepository repository;
    private JdbcTemplate jdbc;
    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-team-member-api-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE team_member (
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL UNIQUE
                )""");

        repository = new TeamMemberRepository(jdbc);
        TeamMemberService service = new TeamMemberService(repository);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(new TeamMemberController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void ensureRegistersNewName() throws Exception {
        mockMvc.perform(post("/api/team-members/ensure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张决赛\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("张决赛"));

        assertThat(repository.findAll()).extracting(TeamMember::name).containsExactly("张决赛");
    }

    @Test
    void ensureKeepsExistingNameWithoutDuplicating() throws Exception {
        long existingId = repository.insert("张决赛");

        mockMvc.perform(post("/api/team-members/ensure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张决赛\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingId));

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void createRejectsDuplicateNameWithChineseMessage() throws Exception {
        repository.insert("张决赛");

        mockMvc.perform(post("/api/team-members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张决赛\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value("队员名称已存在"));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/team-members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value("队员名称不能为空"));
    }

    @Test
    void listReturnsRegisteredMembers() throws Exception {
        repository.insert("甲队");
        repository.insert("乙队");

        mockMvc.perform(get("/api/team-members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}