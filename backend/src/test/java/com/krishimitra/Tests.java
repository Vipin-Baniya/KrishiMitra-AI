package com.krishimitra.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krishimitra.dto.AuthDTOs;
import com.krishimitra.service.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@DisplayName("AuthController Tests")
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean  AuthService authService;

    @Test
    @DisplayName("POST /auth/register returns 201 with tokens on valid request")
    void register_valid_returns201() throws Exception {
        when(authService.register(any())).thenReturn(
                new AuthDTOs.TokenResponse("access.token", "refresh.token",
                        86400000L, UUID.randomUUID(), "Ramesh Kumar"));

        String body = json.writeValueAsString(new AuthDTOs.RegisterRequest(
                "9876543210", "Ramesh Kumar", null,
                "Password123", "Dewas", "Indore", "Madhya Pradesh",
                null, null, "hi"));

        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.farmerName").value("Ramesh Kumar"));
    }

    @Test
    @DisplayName("POST /auth/register returns 400 for invalid phone")
    void register_invalidPhone_returns400() throws Exception {
        String body = json.writeValueAsString(new AuthDTOs.RegisterRequest(
                "123", "Test", null, "pass123", null, "Indore", "MP",
                null, null, null));

        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }

    @Test
    @DisplayName("POST /auth/login returns 400 for missing fields")
    void login_missingFields_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}

// ─────────────────────────────────────────────────────────────
// SELL ADVISOR SERVICE UNIT TESTS
// ─────────────────────────────────────────────────────────────

package com.krishimitra.service;

import com.krishimitra.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SellAdvisorService Tests")
class SellAdvisorServiceTest {

    @Mock PriceService priceService;
    @Mock MandiRepository mandiRepo;
    @Mock SellRecommendationRepository recRepo;
    @Mock ProfitSimulationRepository simRepo;
    @Mock com.krishimitra.kafka.KafkaProducer kafkaProducer;

    @InjectMocks SellAdvisorService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private PriceForecastResponse buildForecast(String decision, int waitDays,
                                                  BigDecimal current, BigDecimal peak) {
        return new PriceForecastResponse(
                "Wheat", "Indore", current, "2025-03-21",
                List.of(1, 3, 7, 14, 21, 30),
                List.of(current.add(BigDecimal.valueOf(50)),
                        current.add(BigDecimal.valueOf(100)),
                        current.add(BigDecimal.valueOf(180)),
                        peak,
                        peak.subtract(BigDecimal.valueOf(50)),
                        peak.subtract(BigDecimal.valueOf(100))),
                List.of(current), List.of(peak),
                List.of(current), List.of(peak),
                decision, waitDays, 14, peak,
                peak.subtract(current).subtract(BigDecimal.valueOf(35)),
                0.82, null, null, false, 420L
        );
    }

    @Test
    @DisplayName("Returns WAIT_N_DAYS when peak price offers substantial gain")
    void getAdvice_waitDecision() {
        BigDecimal current = BigDecimal.valueOf(2180);
        BigDecimal peak    = BigDecimal.valueOf(2520);
        when(priceService.getForecast("Wheat", "Indore"))
                .thenReturn(buildForecast("WAIT_N_DAYS", 14, current, peak));

        SellAdviceResponse resp = service.getAdvice(
                UUID.randomUUID(),
                new SellAdviceRequest("Wheat", "Indore", 10.0, true));

        assertThat(resp.sellDecision()).isEqualTo("WAIT_N_DAYS");
        assertThat(resp.waitDays()).isEqualTo(14);
        assertThat(resp.peakPrice()).isEqualByComparingTo(peak);
        assertThat(resp.profitGainPerQtl()).isPositive();
        assertThat(resp.storageCost()).isPositive(); // storage cost applied
    }

    @Test
    @DisplayName("Returns SELL_NOW when forecast is flat or declining")
    void getAdvice_sellNow() {
        BigDecimal current = BigDecimal.valueOf(840);
        when(priceService.getForecast("Tomato", "Bhopal"))
                .thenReturn(buildForecast("SELL_NOW", 0, current, current));

        SellAdviceResponse resp = service.getAdvice(
                UUID.randomUUID(),
                new SellAdviceRequest("Tomato", "Bhopal", 2.0, false));

        assertThat(resp.sellDecision()).isEqualTo("SELL_NOW");
        assertThat(resp.waitDays()).isEqualTo(0);
    }

    @ParameterizedTest(name = "Storage cost for {0}: {1} ₹/qtl × {2} days = {3} ₹/qtl")
    @CsvSource({
            "Wheat,  2.5, 14, 35.0",
            "Tomato, 8.0,  7, 56.0",
            "Onion,  6.0, 10, 60.0",
    })
    @DisplayName("Correct storage cost is applied per commodity")
    void simulate_storageCostCorrect(String commodity, double rate, int days, double expectedCost) {
        BigDecimal current = BigDecimal.valueOf(2000);
        when(priceService.getForecast(commodity, "TestMandi"))
                .thenReturn(buildForecast("WAIT_N_DAYS", days, current,
                        current.add(BigDecimal.valueOf(300))));

        ProfitSimResponse resp = service.simulate(
                UUID.randomUUID(),
                new ProfitSimRequest(commodity, "TestMandi", 5.0, days));

        assertThat(resp.storageCost().doubleValue())
                .isCloseTo(expectedCost, within(1.0));
    }
}

// ─────────────────────────────────────────────────────────────
// INTEGRATION TEST with Testcontainers
// ─────────────────────────────────────────────────────────────

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
