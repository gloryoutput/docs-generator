package io.github.gloryoutput.docsgenerator.domain.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Project/ProjectCode 엔티티 CRUD 테스트
 *
 * @author Lodong
 * @since 1.0.0
 */
@DataJpaTest
class ProjectRepositoryTest {
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectCodeRepository projectCodeRepository;

    @Test
    @DisplayName("ProjectCode 생성 및 Project 생성/조회")
    void createAndFind() {
        // given
        ProjectCode code = projectCodeRepository.save(
                ProjectCode.builder().projectCode("PRJ001").build());
        Project project = Project.builder()
                .idProjectCode(code.getIdProjectCode())
                .projectName("테스트 프로젝트")
                .build();
        // when
        Project saved = projectRepository.save(project);
        Optional<Project> found = projectRepository.findById(saved.getIdProject());
        // then
        assertThat(found).isPresent();
        assertThat(found.get().getIdProjectCode()).isEqualTo(code.getIdProjectCode());
        assertThat(found.get().getProjectName()).isEqualTo("테스트 프로젝트");
        assertThat(found.get().getActive()).isTrue();
        assertThat(found.get().getIsDeleted()).isFalse();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("ProjectCode로 조회")
    void findByProjectCode() {
        // given
        ProjectCode code = projectCodeRepository.save(
                ProjectCode.builder().projectCode("PRJ002").build());
        projectRepository.save(Project.builder()
                .idProjectCode(code.getIdProjectCode())
                .projectName("코드 조회 테스트")
                .build());
        // when
        Optional<ProjectCode> foundCode = projectCodeRepository.findByProjectCode("PRJ002");
        Optional<Project> foundProject = projectRepository.findByIdProjectCode(foundCode.get().getIdProjectCode());
        // then
        assertThat(foundCode).isPresent();
        assertThat(foundProject).isPresent();
        assertThat(foundProject.get().getProjectName()).isEqualTo("코드 조회 테스트");
    }

    @Test
    @DisplayName("Project 전체 조회")
    void findAll() {
        // given
        ProjectCode codeA = projectCodeRepository.save(ProjectCode.builder().projectCode("A").build());
        ProjectCode codeB = projectCodeRepository.save(ProjectCode.builder().projectCode("B").build());
        ProjectCode codeC = projectCodeRepository.save(ProjectCode.builder().projectCode("C").build());
        projectRepository.save(Project.builder().idProjectCode(codeA.getIdProjectCode()).projectName("프로젝트A").build());
        projectRepository.save(Project.builder().idProjectCode(codeB.getIdProjectCode()).projectName("프로젝트B").build());
        projectRepository.save(Project.builder().idProjectCode(codeC.getIdProjectCode()).projectName("프로젝트C").build());
        // when
        List<Project> all = projectRepository.findAll();
        // then
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("Project 삭제")
    void delete() {
        // given
        ProjectCode code = projectCodeRepository.save(ProjectCode.builder().projectCode("DEL001").build());
        Project project = Project.builder()
                .idProjectCode(code.getIdProjectCode())
                .projectName("삭제 테스트")
                .build();
        Project saved = projectRepository.save(project);
        // when
        projectRepository.deleteById(saved.getIdProject());
        Optional<Project> found = projectRepository.findById(saved.getIdProject());
        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Project 논리 삭제 (softDelete)")
    void softDelete() {
        // given
        ProjectCode code = projectCodeRepository.save(ProjectCode.builder().projectCode("SOFT001").build());
        Project project = Project.builder()
                .idProjectCode(code.getIdProjectCode())
                .projectName("논리삭제 테스트")
                .build();
        Project saved = projectRepository.save(project);
        // when
        saved.softDelete();
        projectRepository.save(saved);
        Optional<Project> found = projectRepository.findById(saved.getIdProject());
        // then
        assertThat(found).isPresent();
        assertThat(found.get().getIsDeleted()).isTrue();
        assertThat(found.get().getDeletedAt()).isNotNull();
    }
}
