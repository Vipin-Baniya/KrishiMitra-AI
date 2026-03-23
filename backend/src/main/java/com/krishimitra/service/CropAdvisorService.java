package com.krishimitra.service;

import com.krishimitra.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CropAdvisorService {

    private final PriceService priceService;

    private static final Map<String, List<String>> SOIL_CROPS = Map.of(
            "black",     List.of("Soybean", "Cotton", "Wheat", "Gram"),
            "red",       List.of("Maize", "Sorghum", "Groundnut", "Tomato"),
            "alluvial",  List.of("Wheat", "Paddy", "Sugarcane", "Maize"),
            "sandy",     List.of("Groundnut", "Watermelon", "Bajra"),
            "loamy",     List.of("Wheat", "Maize", "Soybean", "Onion")
    );

    private static final Map<String, List<String>> SEASON_CROPS = Map.of(
            "rabi",   List.of("Wheat", "Gram", "Mustard", "Potato"),
            "kharif", List.of("Soybean", "Cotton", "Paddy", "Maize", "Onion"),
            "zaid",   List.of("Watermelon", "Cucumber", "Fodder", "Vegetables")
    );

    public CropRecommendationResponse recommend(UUID farmerId, CropRecommendationRequest req) {
        String soilKey   = req.soilType() != null  ? req.soilType().toLowerCase().split(" ")[0]  : "loamy";
        String seasonKey = req.season() != null     ? req.season().toLowerCase().split(" ")[0]    : "rabi";

        List<String> soilCrops   = SOIL_CROPS.getOrDefault(soilKey, SOIL_CROPS.get("loamy"));
        List<String> seasonCrops = SEASON_CROPS.getOrDefault(seasonKey, SEASON_CROPS.get("rabi"));

        // Intersection: crops that suit both soil and season
        List<String> candidates = soilCrops.stream()
                .filter(c -> seasonCrops.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(c)))
                .toList();
        if (candidates.isEmpty()) candidates = seasonCrops.subList(0, Math.min(3, seasonCrops.size()));

        List<CropRecommendationResponse.CropSuggestion> suggestions = candidates.stream()
                .map(crop -> buildSuggestion(crop, req.location(), soilKey, seasonKey))
                .sorted(Comparator.comparingInt(CropRecommendationResponse.CropSuggestion::matchScore).reversed())
                .limit(4)
                .toList();

        return new CropRecommendationResponse(req.location(), req.season(), suggestions);
    }

    private CropRecommendationResponse.CropSuggestion buildSuggestion(
            String crop, String location, String soil, String season) {

        Map<String, Integer> BASE_SCORES = Map.of(
                "Wheat", 92, "Soybean", 88, "Gram", 84, "Cotton", 80,
                "Maize", 78, "Onion", 76, "Tomato", 74, "Paddy", 82);
        int score = BASE_SCORES.getOrDefault(crop, 70);

        Map<String, String> PROFIT_RANGES = Map.of(
                "Wheat", "₹18,000–₹24,000/acre", "Soybean", "₹14,000–₹20,000/acre",
                "Gram",  "₹12,000–₹18,000/acre", "Cotton",  "₹22,000–₹35,000/acre",
                "Maize", "₹10,000–₹16,000/acre", "Onion",   "₹15,000–₹30,000/acre",
                "Tomato","₹20,000–₹50,000/acre", "Paddy",   "₹12,000–₹18,000/acre");

        Map<String, Integer> GROWTH_DAYS = Map.of(
                "Wheat", 120, "Soybean", 90, "Gram", 110, "Cotton", 180,
                "Maize", 90,  "Onion",   90, "Tomato", 75, "Paddy", 120);

        Map<String, String> ICONS = Map.of(
                "Wheat", "🌾", "Soybean", "🫘", "Gram", "🟤", "Cotton", "🌸",
                "Maize", "🌽", "Onion", "🧅", "Tomato", "🍅", "Paddy", "🌾");

        return new CropRecommendationResponse.CropSuggestion(
                crop,
                ICONS.getOrDefault(crop, "🌿"),
                score,
                PROFIT_RANGES.getOrDefault(crop, "₹10,000–₹20,000/acre"),
                score >= 85 ? "Low" : score >= 75 ? "Medium" : "High",
                GROWTH_DAYS.getOrDefault(crop, 100),
                "Indore, Ujjain",
                String.format("Ideal for %s soil in %s season. Strong mandi demand.", soil, season)
        );
    }
}
