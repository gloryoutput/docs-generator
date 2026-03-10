package io.github.gloryoutput.docsgenerator.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 체크포인트 #2 검증: 프로젝트 생성, 레포지토리 등록/조회 API 테스트
 *
 * @author Lodong
 * @since 1.0.0
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectControllerTest {
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private String extractUuid(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return node.get("data").get("idProject").asText();
    }

    @Test
    @Order(1)
    @DisplayName("프로젝트 생성")
    void createProject() throws Exception {
        String body = """
                {
                    "projectCode": "TEST_PRJ",
                    "projectName": "테스트 프로젝트"
                }
                """;
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.projectCode").value("TEST_PRJ"))
                .andExpect(jsonPath("$.data.projectName").value("테스트 프로젝트"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.idProject").isNotEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("프로젝트 목록 조회")
    void getProjects() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(3)
    @DisplayName("레포지토리 3개 등록 및 조회")
    void addThreeRepositoriesAndQuery() throws Exception {
        // 프로젝트 생성
        String projectBody = """
                {
                    "projectCode": "REPO_TEST",
                    "projectName": "레포 등록 테스트"
                }
                """;
        MvcResult projectResult = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(projectBody))
                .andExpect(status().isOk())
                .andReturn();
        String projectId = extractUuid(projectResult);
        // 레포 3개 한 번에 등록
        String reposBody = """
                [
                    {"repositoryName":"api-server","repositoryUrl":"https://github.com/example/api-server.git","defaultBranch":"main","priorityOrder":1},
                    {"repositoryName":"admin-server","repositoryUrl":"https://github.com/example/admin-server.git","defaultBranch":"develop","priorityOrder":2},
                    {"repositoryName":"common-lib","repositoryUrl":"https://github.com/example/common-lib.git","defaultBranch":"main","priorityOrder":3}
                ]
                """;
        mockMvc.perform(post("/api/projects/" + projectId + "/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reposBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3));
        // 조회 확인
        mockMvc.perform(get("/api/projects/" + projectId + "/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @Order(4)
    @DisplayName("중복 프로젝트 코드 생성 시 실패")
    void duplicateProjectCode() throws Exception {
        String body = """
                {
                    "projectCode": "DUP_TEST",
                    "projectName": "중복 테스트"
                }
                """;
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        // 동일 코드로 재생성
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
