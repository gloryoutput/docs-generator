package io.github.gloryoutput.docsgenerator.domain.repositorymap;

import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCode;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCodeRepository;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProjectRepositoryMap 엔티티 CRUD 테스트
 *
 * @author Lodong
 * @since 1.0.0
 */
@DataJpaTest
class ProjectRepositoryMapRepositoryTest {
    @Autowired
    private ProjectRepositoryMapRepository repositoryMapRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectCodeRepository projectCodeRepository;
    private UUID idProject;

    @BeforeEach
    void setUp() {
        ProjectCode code = projectCodeRepository.save(
                ProjectCode.builder().projectCode("PRJ_MAP_TEST").build());
        Project project = Project.builder()
                .idProjectCode(code.getIdProjectCode())
                .projectName("매핑 테스트 프로젝트")
                .build();
        Project saved = projectRepository.save(project);
        this.idProject = saved.getIdProject();
    }

    @Test
    @DisplayName("레포지토리 매핑 생성 및 조회")
    void createAndFind() {
        // given
        ProjectRepositoryMap map = ProjectRepositoryMap.builder()
                .idProject(idProject)
                .repositoryName("api-server")
                .repositoryUrl("https://github.com/example/api-server.git")
                .targetBranch("main")
                .priorityOrder(1)
                .build();
        // when
        ProjectRepositoryMap saved = repositoryMapRepository.save(map);
        // then
        assertThat(saved.getIdProjectRepositoryMap()).isNotNull();
        assertThat(saved.getRepositoryName()).isEqualTo("api-server");
        assertThat(saved.getRepositoryUrl()).isEqualTo("https://github.com/example/api-server.git");
        assertThat(saved.getTargetBranch()).isEqualTo("main");
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("프로젝트에 여러 레포지토리 등록 후 조회")
    void multipleRepositories() {
        // given
        repositoryMapRepository.save(ProjectRepositoryMap.builder()
                .idProject(idProject)
                .repositoryName("api-server")
                .repositoryUrl("https://github.com/example/api-server.git")
                .targetBranch("main")
                .priorityOrder(1)
                .build());
        repositoryMapRepository.save(ProjectRepositoryMap.builder()
                .idProject(idProject)
                .repositoryName("admin-server")
                .repositoryUrl("https://github.com/example/admin-server.git")
                .targetBranch("develop")
                .priorityOrder(2)
                .build());
        repositoryMapRepository.save(ProjectRepositoryMap.builder()
                .idProject(idProject)
                .repositoryName("common-lib")
                .repositoryUrl("https://github.com/example/common-lib.git")
                .targetBranch("main")
                .priorityOrder(3)
                .build());
        // when
        List<ProjectRepositoryMap> maps = repositoryMapRepository.findByIdProjectAndIsDeletedFalse(idProject);
        // then
        assertThat(maps).hasSize(3);
        assertThat(maps).extracting(ProjectRepositoryMap::getRepositoryName)
                .containsExactlyInAnyOrder("api-server", "admin-server", "common-lib");
    }

    @Test
    @DisplayName("논리 삭제된 레포지토리는 조회에서 제외")
    void softDeletedExcluded() {
        // given
        ProjectRepositoryMap map = ProjectRepositoryMap.builder()
                .idProject(idProject)
                .repositoryName("deleted-repo")
                .repositoryUrl("https://github.com/example/deleted.git")
                .build();
        ProjectRepositoryMap saved = repositoryMapRepository.save(map);
        saved.softDelete();
        repositoryMapRepository.save(saved);
        // when
        List<ProjectRepositoryMap> maps = repositoryMapRepository.findByIdProjectAndIsDeletedFalse(idProject);
        // then
        assertThat(maps).isEmpty();
    }
}
