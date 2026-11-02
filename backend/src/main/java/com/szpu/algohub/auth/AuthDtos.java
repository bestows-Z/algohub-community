package com.szpu.algohub.auth;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "只能包含字母、数字、下划线、点和连字符")
            String username,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 32) String displayName,
            @Email(message = "邮箱格式不正确") String email,
            /** 学校邀请码，可留空，注册后再申请入校。 */
            String inviteCode,
            Integer enrollmentYear,
            String className,
            String studentNo) {
    }

    public record LoginRequest(@NotBlank @Size(max = 32) String username, @NotBlank @Size(max = 64) String password) {
    }

    public record ChangePasswordRequest(@Size(max = 64) String oldPassword,
                                        @NotBlank @Size(min = 8, max = 64) String newPassword) {
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 32) String displayName,
                                       @Email(message = "邮箱格式不正确") String email,
                                       @Size(max = 255) String bio,
                                       @Size(max = 512) String avatarUrl) {
    }

    public record PrivacyRequest(String profileVisibility, Boolean showOnRanking, Boolean showAccounts,
                                 Boolean showStudentNo, Boolean showEmail, Boolean publicActivity) {
        public PrivacyRequest(String visibility, Boolean ranking, Boolean accounts, Boolean studentNo, Boolean email) {
            this(visibility, ranking, accounts, studentNo, email, null);
        }
    }

    public record DeleteAccountRequest(String password, @NotBlank String confirmUsername, String actionToken) {
    }

    public record MembershipView(Long id, Long schoolId, String schoolName, String status,
                                 Integer enrollmentYear, String className, String memberRole) {
    }

    public record PrivacyView(String profileVisibility, boolean showOnRanking, boolean showAccounts,
                              boolean showStudentNo, boolean showEmail, boolean publicActivity) {
    }

    public record OauthBindingView(String provider, String login, String displayName, String profileUrl,
                                   String avatarUrl, Instant linkedAt) {
    }

    public record OnboardingRequest(Long schoolId, @Size(max=64) String inviteCode,
            @NotBlank @Size(max=32) String displayName,
            @jakarta.validation.constraints.NotNull Integer enrollmentYear,
            @NotBlank(message="请填写班级") @Size(max=64) String className, @NotBlank(message="请填写学号") @Size(max=32) String studentNo) {}

    public record AcademicRequest(@jakarta.validation.constraints.NotNull Integer enrollmentYear,
            @NotBlank(message="请填写班级") @Size(max=64) String className, @NotBlank(message="请填写学号") @Size(max=32) String studentNo) {}

    public record MeView(Long id, String username, String displayName, String email, String role,
                         String avatarUrl, String bio, boolean hasPassword, Instant lastLoginAt,
                         Instant createdAt, List<MembershipView> memberships, PrivacyView privacy,
                         List<OauthBindingView> bindings, boolean emailVerified, boolean onboardingCompleted,
                         Integer enrollmentYear, String className, String studentNo,
                         boolean profileCompletionRequired, boolean nicknameNeedsReview, List<java.util.Map<String,Object>> managedSchools,
                         List<java.util.Map<String,Object>> managedCohorts) {
    }

    /** 登录结果。刷新令牌通过 HttpOnly Cookie 下发，不出现在响应体里。 */
    public record TokenView(String accessToken, String tokenType, long expiresInSeconds,
                            Instant expiresAt, MeView user) {
    }

    /** 公开档案：按隐私设置裁剪后的用户信息。 */
    public record PublicProfileView(Long id, String displayName, String avatarUrl, String bio,
                                    Integer enrollmentYear, String className, String schoolName, String email,
                                    boolean visible, String visibilityNote) {
    }
}
