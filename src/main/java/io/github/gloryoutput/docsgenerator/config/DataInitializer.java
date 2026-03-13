package io.github.gloryoutput.docsgenerator.config;

import io.github.gloryoutput.docsgenerator.domain.project.Project;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCode;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectCodeRepository;
import io.github.gloryoutput.docsgenerator.domain.project.ProjectRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMap;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.ProjectRepositoryMapRepository;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredential;
import io.github.gloryoutput.docsgenerator.domain.repositorymap.RepositoryCredentialRepository;
import io.github.gloryoutput.docsgenerator.enums.AuthType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * 애플리케이션 기동 시 초기 프로젝트 데이터를 생성하는 초기화 클래스
 *
 * <p>등록된 프로젝트가 없으면 기본 프로젝트와 레포지토리를 생성합니다.
 * 기존 credential이 있으면 동일한 인증 정보를 재사용합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {
    private final ProjectCodeRepository projectCodeRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryMapRepository repositoryMapRepository;
    private final RepositoryCredentialRepository credentialRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initDolbomProject();
    }

    private void initDolbomProject() {
        if (projectCodeRepository.findByProjectCode("dolbom").isPresent()) {
            log.info("dolbom 프로젝트가 이미 존재합니다 - 초기화 스킵");
            return;
        }
        // 프로젝트 코드 + 프로젝트 생성
        ProjectCode projectCode = projectCodeRepository.save(
                ProjectCode.builder().projectCode("dolbom").build());
        Project project = projectRepository.save(
                Project.builder()
                        .idProjectCode(projectCode.getIdProjectCode())
                        .projectName("dolbom")
                        .build());
        log.info("dolbom 프로젝트 생성 완료 (ID: {})", project.getIdProject());
        // 레포지토리 등록
        ProjectRepositoryMap elderlyRepo = repositoryMapRepository.save(
                ProjectRepositoryMap.builder()
                        .idProject(project.getIdProject())
                        .repositoryName("elderly_care_web")
                        .repositoryUrl("https://github.com/PackageWeb/elderly_care_web")
                        .targetBranch("main")
                        .priorityOrder(1)
                        .build());
        ProjectRepositoryMap careManagerRepo = repositoryMapRepository.save(
                ProjectRepositoryMap.builder()
                        .idProject(project.getIdProject())
                        .repositoryName("care_manager_web")
                        .repositoryUrl("https://github.com/PackageWeb/care_manager_web")
                        .targetBranch("main")
                        .priorityOrder(2)
                        .build());
        log.info("dolbom 레포지토리 2건 등록 완료");
        // 기존 credential 복사 (GHP 토큰 재사용)
        Optional<RepositoryCredential> existingCredential = findExistingCredential();
        if (existingCredential.isPresent()) {
            RepositoryCredential source = existingCredential.get();
            copyCredential(source, elderlyRepo.getIdProjectRepositoryMap());
            copyCredential(source, careManagerRepo.getIdProjectRepositoryMap());
            log.info("기존 GHP 토큰을 dolbom 레포지토리에 복사 완료");
        } else {
            log.warn("기존 credential이 없습니다 - API로 직접 설정해 주세요");
        }
    }

    /**
     * 기존 활성 credential 중 PERSONAL_ACCESS_TOKEN 타입을 찾습니다.
     */
    private Optional<RepositoryCredential> findExistingCredential() {
        List<RepositoryCredential> allCredentials = credentialRepository.findAll();
        return allCredentials.stream()
                .filter(c -> !c.getIsDeleted())
                .filter(c -> c.getAuthType() == AuthType.PERSONAL_ACCESS_TOKEN)
                .filter(c -> c.getEncryptedToken() != null)
                .findFirst();
    }

    /**
     * 기존 credential의 암호화된 토큰을 새 레포지토리에 복사합니다.
     */
    private void copyCredential(RepositoryCredential source, java.util.UUID idProjectRepositoryMap) {
        credentialRepository.save(
                RepositoryCredential.builder()
                        .idProjectRepositoryMap(idProjectRepositoryMap)
                        .authType(source.getAuthType())
                        .username(source.getUsername())
                        .encryptedToken(source.getEncryptedToken())
                        .build());
    }
}
