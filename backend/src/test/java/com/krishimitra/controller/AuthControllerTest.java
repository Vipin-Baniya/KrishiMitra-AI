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
