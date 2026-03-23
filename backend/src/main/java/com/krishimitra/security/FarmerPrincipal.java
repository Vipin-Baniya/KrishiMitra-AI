package com.krishimitra.security;

import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

public record FarmerPrincipal(UUID farmerId, String phone) implements UserDetails {
    @Override public String getUsername()  { return phone; }
    @Override public String getPassword()  { return ""; }
    @Override public boolean isEnabled()   { return true; }
    @Override public boolean isAccountNonExpired()   { return true; }
    @Override public boolean isAccountNonLocked()    { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
        return List.of(() -> "ROLE_FARMER");
    }
}
