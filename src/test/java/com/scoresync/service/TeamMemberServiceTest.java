package com.scoresync.service;

import com.scoresync.domain.TeamMember;
import com.scoresync.repository.TeamMemberRepository;
import com.scoresync.support.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamMemberServiceTest {

    private TeamMemberRepository repository;
    private TeamMemberService service;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-team-member-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE team_member (
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL UNIQUE
                )""");
        repository = new TeamMemberRepository(jdbc);
        service = new TeamMemberService(repository);
    }

    @Test
    void createAddsMemberToList() {
        TeamMember created = service.create("张决赛");

        assertThat(created.name()).isEqualTo("张决赛");
        assertThat(service.getAll()).extracting(TeamMember::name).containsExactly("张决赛");
    }

    @Test
    void createTrimsSurroundingSpaces() {
        TeamMember created = service.create("  李四  ");

        assertThat(created.name()).isEqualTo("李四");
        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create("   "))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("队员名称不能为空");
    }

    @Test
    void createRejectsDuplicateName() {
        service.create("王五");

        assertThatThrownBy(() -> service.create("王五"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("队员名称已存在");

        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    void ensureExistsRegistersNewName() {
        TeamMember ensured = service.ensureExists("赵六");

        assertThat(ensured.name()).isEqualTo("赵六");
        assertThat(service.getAll()).extracting(TeamMember::name).containsExactly("赵六");
    }

    @Test
    void ensureExistsKeepsExistingMemberWithoutDuplicating() {
        TeamMember original = service.create("孙七");

        TeamMember ensured = service.ensureExists("孙七");

        assertThat(ensured.id()).isEqualTo(original.id());
        assertThat(service.getAll()).hasSize(1);
    }

    @Test
    void ensureExistsRejectsBlankName() {
        assertThatThrownBy(() -> service.ensureExists(""))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("队员名称不能为空");
    }
}