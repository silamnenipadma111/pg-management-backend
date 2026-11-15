package com.pgmanagement.security;

import com.pgmanagement.entity.Enums;

public record AuthUser(Long id, String email, String name, Enums.UserRole role) {
}
