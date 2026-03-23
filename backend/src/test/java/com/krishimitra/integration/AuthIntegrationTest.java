package com.krishimitra.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krishimitra.dto.AuthDTOs;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Integration Tests — Auth + Profile Flow")
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("krishimitra_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use H2 for Redis in tests
        registry.add("spring.cache.type", () -> "simple");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static String accessToken;

    @Test
    @Order(1)
    @DisplayName("Full registration + login + profile flow")
    void fullAuthFlow() throws Exception {
        // 1. Register
        String regBody = json.writeValueAsString(new AuthDTOs.RegisterRequest(
                "9988776655", "Integration Test Farmer", null,
                "SecurePass123", "Sanwer", "Indore", "Madhya Pradesh",
                22.7196, 75.8577, "hi"));

        String regResp = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn().getResponse().getContentAsString();

        accessToken = json.readTree(regResp).at("/data/accessToken").asText();

        // 2. Get profile
        mvc.perform(get("/api/v1/farmer/profile")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Integration Test Farmer"))
                .andExpect(jsonPath("$.data.phone").value("9988776655"));

        // 3. Duplicate registration should fail
        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(2)
    @DisplayName("Accessing protected endpoint without token returns 401")
    void protectedEndpoint_noToken_401() throws Exception {
        mvc.perform(get("/api/v1/farmer/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("Login with wrong password returns 401")
    void login_wrongPassword_401() throws Exception {
        String body = json.writeValueAsString(
                new AuthDTOs.LoginRequest("9988776655", "WrongPassword"));

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
