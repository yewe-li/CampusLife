package com.campuslife.auth;

/** Server-owned identity stored in a fixed-lifetime Redis session. */
public record UserPrincipal(long id, String nickname, String role) {
}
