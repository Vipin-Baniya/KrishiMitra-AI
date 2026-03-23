package com.krishimitra.service;

import com.krishimitra.dto.*;
import com.krishimitra.repository.MandiRepository;
import com.krishimitra.repository.ProfitSimulationRepository;
import com.krishimitra.repository.SellRecommendationRepository;
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
