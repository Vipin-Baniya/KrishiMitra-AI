package com.krishimitra.security;

import com.krishimitra.repository.FarmerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class FarmerUserDetailsService implements UserDetailsService {

    private final FarmerRepository farmerRepository;

    @Override
    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        return farmerRepository.findByPhone(phone)
                .map(f -> new FarmerPrincipal(f.getId(), f.getPhone()))
                .orElseThrow(() -> new UsernameNotFoundException("Farmer not found: " + phone));
    }
}
