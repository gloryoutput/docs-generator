package io.github.gloryoutput.docsgenerator.domain.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Project 엔티티 CRUD 테스트
 *
 * @author Lodong
 * @since 1.0.0
 */
@DataJpaTest
class ProjectRepositoryTest {
    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @DisplayName("Project 생성 및 조회")
    void createAndFind() {
        // given
        Project project = Project.builder()
                .projectCode("PRJ001")
                .projectName("테스트 프로젝트")
                .build();
        // when
        Project saved = projectRepository.save(project);
        Optional<Project> found = projectRepository.findById(saved.getUuidProject());
        // then
        assertThat(found).isPresent();
        assertThat(found.get().getProjectCode()).isEqualTo("PRJ001");
        assertThat(found.get().getProjectName()).isEqualTo("테스트 프로젝트");
        assertThat(found.get().getActive()).isTrue();
        assertThat(found.get().getIsDeleted()).isFalse();
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Project projectCode로 조회")
    void findByProjectCode() {
        // given
        Project project = Project.builder()
                .projectCode("PRJ002")
                .projectName("코드 조회 테스트")
                .build();
        projectRepository.save(project);
        // when
        Optional<Project> found = projectRepository.findByProjectCode("PRJ002");
        // then
        assertThat(found).isPresent();
        assertThat(found.get().getProjectName()).isEqualTo("코드 조회 테스트");
    }

    @Test
    @DisplayName("Project 전체 조회")
    void findAll() {
        // given
        projectRepository.save(Project.builder().projectCode("A").projectName("프로젝트A").build());
        projectRepository.save(Project.builder().projectCode("B").projectName("프로젝트B").build());
        projectRepository.save(Project.builder().projectCode("C").projectName("프로젝트C").build());
        // when
        List<Project> all = projectRepository.findAll();
        // then
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("Project 삭제")
    void delete() {
        // given
        Project project = Project.builder()
                .projectCode("DEL001")
                .projectName("삭제 테스트")
                .build();
        Project saved = projectRepository.save(project);
        // when
        projectRepository.deleteById(saved.getUuidProject());
        Optional<Project> found = projectRepository.findById(saved.getUuidProject());
        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Project 논리 삭제 (softDelete)")
    void softDelete() {
        // given
        Project project = Project.builder()
                .projectCode("SOFT001")
                .projectName("논리삭제 테스트")
                .build();
        Project saved = projectRepository.save(project);
        // when
        saved.softDelete();
        projectRepository.save(saved);
        Optional<Project> found = projectRepository.findById(saved.getUuidProject());
        // then
        assertThat(found).isPresent();
        assertThat(found.get().getIsDeleted()).isTrue();
        assertThat(found.get().getDeletedAt()).isNotNull();
    }
}
