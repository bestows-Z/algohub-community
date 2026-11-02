package com.szpu.algohub.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.szpu.algohub.auth.AuthDtos.MeView;
import com.szpu.algohub.auth.AuthDtos.MembershipView;
import com.szpu.algohub.auth.AuthDtos.OauthBindingView;
import com.szpu.algohub.auth.AuthDtos.PrivacyRequest;
import com.szpu.algohub.auth.AuthDtos.PrivacyView;
import com.szpu.algohub.auth.AuthDtos.PublicProfileView;
import com.szpu.algohub.auth.AuthDtos.RegisterRequest;
import com.szpu.algohub.auth.AuthDtos.TokenView;
import com.szpu.algohub.auth.AuthDtos.UpdateProfileRequest;
import com.szpu.algohub.auth.identity.IdentityMapper;
import com.szpu.algohub.auth.identity.OauthBinding;
import com.szpu.algohub.auth.identity.UserPrivacy;
import com.szpu.algohub.admin.console.AdminAccess;
import com.szpu.algohub.admin.console.AdminPermission;
import com.szpu.algohub.admin.console.AdminUsers;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.common.Redaction;
import com.szpu.algohub.config.AppProperties;
import com.szpu.algohub.school.SchoolMember;
import com.szpu.algohub.school.SchoolService;
import com.szpu.algohub.school.CohortLeaderMapper;

/**
 * 身份与会话。登录返回短时效访问令牌（JWT），刷新令牌保存在 Redis 并通过 HttpOnly Cookie 下发。
 * 所有与身份有关的变更都会写入 auth_event 审计表；审计内容不含密码与令牌。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserMapper userMapper;
    private final IdentityMapper identityMapper;
    private final PasswordEncoder passwordEncoder;
    private final SchoolService schoolService;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final AppProperties properties;
    private final AuthRateLimit rateLimit;
    private final AdminUsers adminUsers;
    private final AdminAccess adminAccess;
    private final CohortLeaderMapper cohortLeaders;

    public AuthService(AppUserMapper userMapper, IdentityMapper identityMapper,
                       PasswordEncoder passwordEncoder, SchoolService schoolService,
                       JwtService jwtService, TokenStore tokenStore, AppProperties properties, AuthRateLimit rateLimit,
                       AdminUsers adminUsers, AdminAccess adminAccess, CohortLeaderMapper cohortLeaders) {
        this.cohortLeaders = cohortLeaders;
        this.rateLimit = rateLimit;
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.passwordEncoder = passwordEncoder;
        this.schoolService = schoolService;
        this.jwtService = jwtService;
        this.tokenStore = tokenStore;
        this.properties = properties;
        this.adminUsers = adminUsers;
        this.adminAccess = adminAccess;
    }

    // ---------- 注册与登录 ----------

    @Transactional
    public AppUser register(RegisterRequest request, String ip, String userAgent) {
        if (userMapper.findByUsername(request.username()) != null) {
            throw ApiException.conflict("用户名已被占用");
        }
        AppUser user = new AppUser();
        user.setUsername(request.username().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(StringUtils.hasText(request.email()) ? request.email().trim() : null);
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        identityMapper.ensurePrivacy(user.getId());

        if (StringUtils.hasText(request.inviteCode())) {
            schoolService.applyByInviteCode(user.getId(), request.inviteCode(),
                    request.enrollmentYear(), request.className(), request.studentNo());
        }
        audit(user.getId(), user.getUsername(), "REGISTER", ip, userAgent, null);
        return user;
    }

    /**
     * 账号密码登录。失败计数保存在 Redis，连续失败超过阈值后临时锁定，
     * 且无论用户名是否存在都返回同样的提示，避免用错误信息枚举账号。
     */
    public SessionResult login(String username, String rawPassword, String ip, String userAgent) {
        return issueSession(authenticatePassword(username, rawPassword, ip, userAgent), ip, userAgent, "LOGIN_SUCCESS");
    }

    /** 历史密码仅对管理员有效，学生保留的哈希不再代表一种登录方式。 */
    public static boolean passwordEnabled(AppUser user) {
        return user != null && "ADMIN".equals(user.getRole()) && user.getPasswordHash() != null;
    }

    /** 校验管理员密码并限流，不创建或挤占设备会话。 */
    public AppUser authenticatePassword(String username, String rawPassword, String ip, String userAgent) {
        username = username.strip().toLowerCase(java.util.Locale.ROOT);
        rateLimit.check("login:account", username, 20, 60);
        long failures = tokenStore.loginFailures(username);
        if (failures >= properties.getSecurity().getMaxLoginFailures()) {
            audit(null, username, "LOGIN_LOCKED", ip, userAgent, "失败次数 " + failures);
            throw new ApiException(ErrorKind.FORBIDDEN,
                    "登录失败次数过多，请 " + properties.getSecurity().getLoginLockMinutes() + " 分钟后再试");
        }
        AppUser user = userMapper.findByUsername(username);
        if (!passwordEnabled(user)
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            long count = tokenStore.recordLoginFailure(username);
            audit(user == null ? null : user.getId(), username, "LOGIN_FAILED", ip, userAgent,
                    "累计失败 " + count);
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new ApiException(ErrorKind.FORBIDDEN, "账号已被停用，请联系管理员");
        }
        tokenStore.clearLoginFailures(username);
        return user;
    }

    /** 一次会话签发的结果：访问令牌（返回体）与刷新令牌（只写 HttpOnly Cookie）。 */
    public record SessionResult(TokenView view, String refreshToken) {
    }

    /** 供第三方登录复用：直接为已确认身份的用户签发会话。 */
    /** 登录方式变了、所有设备都已退出后，给正在操作的这台设备重新签发，不用重新登录 */
    public SessionResult reissueAfterIdentityChange(Long userId, String ip, String userAgent) {
        return issueSession(userMapper.findById(userId), ip, userAgent, "SESSION_REISSUED");
    }

    public SessionResult issueSession(AppUser user, String ip, String userAgent, String event) {
        userMapper.touchLastLogin(user.getId());
        identityMapper.ensurePrivacy(user.getId());
        // 按库里的记录签发：刚注册的账号在内存对象里还没有「令牌生效时间」等数据库默认值，
        // 直接拿它签发，第一个访问令牌会被判为旧版本而返回 401（前端要刷新一次才恢复）
        AppUser stored = userMapper.findById(user.getId());
        // 登录过程中身份变了（别处改了密码、退出了所有设备）就不签发
        if (stored == null || !"ACTIVE".equals(stored.getStatus()) || (user.getTokensValidFrom() != null
                && !IdentitySecurity.version(user).equals(IdentitySecurity.version(stored)))) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "身份已改变，请重新登录");
        }
        user = stored;
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);
        TokenStore.RefreshToken refresh = tokenStore.issueRefreshToken(user.getId(), userAgent, IdentitySecurity.version(user));
        audit(user.getId(), user.getUsername(), event, ip, userAgent, null);
        // 刷新令牌值只交给控制层写入 HttpOnly Cookie，不进入响应体与日志
        return new SessionResult(new TokenView(access.token(), "Bearer",
                jwtService.accessTokenTtl().getSeconds(), access.expiresAt(), me(user.getId())),
                refresh.value());
    }

    /** 刷新并轮换；刚丢失响应的浏览器可在短期内恢复同一个后继。 */
    public SessionResult refresh(String refreshToken, String ip, String userAgent) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "没有刷新令牌，请重新登录");
        }
        TokenStore.RefreshIdentity identity = tokenStore.refreshIdentity(refreshToken, userAgent);
        if (identity == null) {
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "刷新令牌无效或已过期，请重新登录");
        }
        Long userId = identity.userId();
        AppUser user = userMapper.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            tokenStore.revokeRefreshToken(refreshToken);
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "账号不可用");
        }
        // 刷新令牌签发后改过密码或登录方式：作废
        if (!identity.identityVersion().equals(IdentitySecurity.version(user))) {
            tokenStore.revokeRefreshToken(refreshToken);
            throw new ApiException(ErrorKind.UNAUTHENTICATED, "登录身份已改变，请重新登录");
        }
        // 所有可能失败的数据库读取、签名与审计先完成，不能先消费 Cookie 再因档案读取失败而丢失会话。
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);
        TokenView view = new TokenView(access.token(), "Bearer",
                jwtService.accessTokenTtl().getSeconds(), access.expiresAt(), me(user.getId()));
        audit(user.getId(), user.getUsername(), "TOKEN_REFRESH", ip, userAgent, null);
        TokenStore.RefreshToken rotated = tokenStore.rotate(refreshToken, userAgent);
        return new SessionResult(view, rotated.value());
    }

    /** 登出当前设备：作废该刷新令牌，并把当前访问令牌拉黑到其过期时间。 */
    public void logout(LoginUser current, String refreshToken, String ip, String userAgent) {
        if (StringUtils.hasText(refreshToken)) {
            tokenStore.revokeRefreshToken(refreshToken);
        }
        if (current != null && current.getTokenId() != null && current.getTokenExpiresAt() != null) {
            tokenStore.blacklistAccessToken(current.getTokenId(), current.getTokenExpiresAt());
            audit(current.getId(), current.getUsername(), "LOGOUT", ip, userAgent, null);
        }
    }

    /** 退出所有设备：清空刷新令牌并让已签发的访问令牌立即失效。 */
    @Transactional
    public int logoutAll(Long userId, String ip, String userAgent) {
        // 和改密码、换登录方式、敏感操作用同一把行锁，排队执行
        userMapper.lockIdentity(userId);
        userMapper.invalidateIssuedTokens(userId);
        int revoked = tokenStore.revokeAllSessions(userId);
        invalidateEmailChallenges(userId);
        AppUser user = userMapper.findById(userId);
        audit(userId, user == null ? null : user.getUsername(), "LOGOUT_ALL", ip, userAgent,
                "撤销会话 " + revoked + " 个");
        return revoked;
    }

    public List<TokenStore.SessionInfo> sessions(Long userId, String currentRefreshToken) {
        return tokenStore.listSessions(userId, currentRefreshToken);
    }

    public List<Map<String, Object>> authEvents(Long userId, int limit) {
        return identityMapper.recentEvents(userId, limit);
    }

    // ---------- 个人资料与隐私 ----------

    public MeView me(Long userId) {
        AppUser user = userMapper.findById(userId);
        if (user == null) {
            throw ApiException.notFound("用户不存在");
        }
        var memberRecords = schoolService.myMemberships(userId);
        var academic = AcademicProfile.of(user, memberRecords);
        List<MembershipView> memberships = memberRecords.stream()
                .map(AuthService::toView)
                .toList();
        List<OauthBindingView> bindings = identityMapper.findBindingsByUser(userId).stream()
                .map(b -> new OauthBindingView(b.getProvider(), b.getLogin(), b.getDisplayName(),
                        b.getProfileUrl(), b.getAvatarUrl(), b.getLinkedAt()))
                .toList();
        return new MeView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole(), user.getAvatarUrl(), user.getBio(), passwordEnabled(user),
                user.getLastLoginAt(), user.getCreatedAt(), memberships, toView(privacy(userId)), bindings, userMapper.verifiedEmailCount(userId)>0, user.isOnboardingCompleted(),
                academic.enrollmentYear(), academic.className(), academic.studentNo(),
                "STUDENT".equals(user.getRole()) && user.isOnboardingCompleted() && !academic.complete(), user.isNicknameNeedsReview(), userMapper.managedSchools(userId), cohortLeaders.scopes(userId));
    }

    private static MembershipView toView(SchoolMember m) {
        return new MembershipView(m.getId(), m.getSchoolId(), m.getSchoolName(), m.getStatus(),
                m.getEnrollmentYear(), m.getClassName(), m.getMemberRole());
    }

    private static PrivacyView toView(UserPrivacy privacy) {
        return new PrivacyView(privacy.getProfileVisibility(), privacy.isShowOnRanking(),
                privacy.isShowAccounts(), privacy.isShowStudentNo(), privacy.isShowEmail(), privacy.isPublicActivity());
    }

    public UserPrivacy privacy(Long userId) {
        UserPrivacy privacy = identityMapper.findPrivacy(userId);
        if (privacy == null) {
            identityMapper.ensurePrivacy(userId);
            privacy = identityMapper.findPrivacy(userId);
        }
        if (privacy == null) {
            privacy = new UserPrivacy();
            privacy.setUserId(userId);
        }
        return privacy;
    }

    @Transactional
    public PrivacyView updatePrivacy(Long userId, PrivacyRequest request, String ip, String userAgent) {
        UserPrivacy privacy = privacy(userId);
        if (StringUtils.hasText(request.profileVisibility())) {
            String visibility = request.profileVisibility().trim().toUpperCase();
            if (!List.of("PUBLIC", "SCHOOL", "PRIVATE").contains(visibility)) {
                throw ApiException.validation("未知的可见范围：" + request.profileVisibility());
            }
            privacy.setProfileVisibility(visibility);
        }
        if (request.showOnRanking() != null) {
            privacy.setShowOnRanking(request.showOnRanking());
        }
        if (request.showAccounts() != null) {
            privacy.setShowAccounts(request.showAccounts());
        }
        if (request.showStudentNo() != null) {
            privacy.setShowStudentNo(request.showStudentNo());
        }
        if (request.showEmail() != null) {
            privacy.setShowEmail(request.showEmail());
        }
        if (request.publicActivity() != null) privacy.setPublicActivity(request.publicActivity());
        privacy.setUserId(userId);
        identityMapper.savePrivacy(privacy);
        audit(userId, null, "PRIVACY_UPDATED", ip, userAgent, "可见范围=" + privacy.getProfileVisibility());
        return toView(privacy);
    }

    @Transactional
    public MeView updateProfile(Long userId, UpdateProfileRequest request, String ip, String userAgent) {
        AppUser user = userMapper.findById(userId);
        if (user == null) {
            throw ApiException.notFound("用户不存在");
        }
        if (!request.displayName().trim().equals(user.getDisplayName())) user.setNicknameNeedsReview(false);
        user.setDisplayName(request.displayName().trim());
        user.setEmail(StringUtils.hasText(request.email()) ? request.email().trim() : null);
        user.setBio(StringUtils.hasText(request.bio()) ? request.bio().trim() : null);
        // 头像不在这里改：只能从已验证的 OJ 账号里选（/api/me/avatar），request.avatarUrl 只为兼容旧前端而保留、不再使用
        userMapper.updateDetail(user);
        audit(userId, user.getUsername(), "PROFILE_UPDATED", ip, userAgent, null);
        return me(userId);
    }

    /**
     * 修改密码。改完立即让所有已签发的令牌失效并清空其他设备的会话，
     * 避免旧登录态在改密后继续可用。
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword,
                               String ip, String userAgent) {
        // /api/me/password 不经过 /api/auth 的入口限流；先限制尝试，再获取身份锁和执行 BCrypt。
        rateLimit.check("password-change:user", userId.toString(), 10, 300);
        rateLimit.check("password-change:ip", ip == null ? "" : ip, 120, 60);
        AppUser user = userMapper.lockIdentity(userId);
        if (user == null) {
            throw ApiException.notFound("用户不存在");
        }
        if (!"ADMIN".equals(user.getRole())) throw ApiException.forbidden("普通用户请使用邮箱验证码或 GitHub 登录");
        if (user.getPasswordHash() == null) {
            throw ApiException.validation("首次设置密码请通过已验证邮箱获取验证码");
        }
        if (user.getPasswordHash() != null) {
            if (!StringUtils.hasText(oldPassword)
                    || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
                throw ApiException.validation("原密码不正确");
            }
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        invalidateEmailChallenges(userId);
        tokenStore.revokeAllSessions(userId);
        userMapper.invalidateIssuedTokens(userId);
        audit(userId, user.getUsername(), "PASSWORD_CHANGED", ip, userAgent, "已下线所有设备");
    }

    /** 与密码更新处于同一事务，撤销此前签发的邮箱身份操作。 */
    public void invalidateEmailChallenges(Long userId) {
        identityMapper.deleteResetChallenges(userId);
        identityMapper.deleteLoginChallenges(userId);
        identityMapper.deleteBindingChallenges(userId);
    }

    /** 按隐私设置裁剪后的公开档案。不可见时返回占位信息，而不是直接暴露数据。 */
    /**
     * 「谁能看谁的档案」这一条规则只写在这里，个人档案与统计接口共用，
     * 免得一个接口拦住了、另一个接口把同样的数据原样返回。
     *
     * PUBLIC 任何登录用户可见；SCHOOL 仅与对方同一所学校、且都已通过的成员可见（其他学校的成员看不到）；
     * PRIVATE 只有本人（与管理员）可见。学校身份只看服务端的成员记录。
     */
    public boolean profileVisibleTo(Long targetUserId, Long viewerId, boolean viewerIsAdmin) {
        if (targetUserId.equals(viewerId) || viewerIsAdmin) {
            return true;
        }
        return switch (privacy(targetUserId).getProfileVisibility()) {
            case "PUBLIC" -> true;
            case "SCHOOL" -> schoolService.sameSchool(viewerId, targetUserId);
            default -> false;
        };
    }

    /** 用户不存在与档案不对你公开返回同一个 404，不泄露昵称，也不让人借此探测用户 ID 是否存在。 */
    public static final String PROFILE_NOT_FOUND = "用户不存在或档案未对你公开";

    /** 公共读接口的管理视角需有用户管理权限；仅有其他模块的管理员遵循公开隐私规则。 */
    public boolean canReadPrivateProfiles(Long viewerId) {
        if (viewerId == null) return false;
        AdminAccess.Grant grant = adminAccess.of(viewerId);
        return grant.active() && grant.has(AdminPermission.USERS);
    }

    public AppUser requireVisibleProfile(Long targetUserId, Long viewerId, boolean viewerIsAdmin) {
        AppUser user = userMapper.findById(targetUserId);
        if (user == null || !profileVisibleTo(targetUserId, viewerId, viewerIsAdmin)) {
            throw ApiException.notFound(PROFILE_NOT_FOUND);
        }
        return user;
    }

    /** 档案读取可使用本校管理授权；关注和好友消息继续遵循普通可见性规则。 */
    public AppUser requireReadableProfile(Long targetUserId, Long viewerId, boolean viewerIsAdmin) {
        boolean schoolAccess = !viewerIsAdmin && viewerId != null && !targetUserId.equals(viewerId)
                && userMapper.managesStudentProfile(viewerId, targetUserId);
        Map<String,Object> cohortScope = !viewerIsAdmin && !schoolAccess && viewerId != null && !targetUserId.equals(viewerId)
                ? cohortLeaders.profileScope(viewerId, targetUserId) : null;
        AppUser user = requireVisibleProfile(targetUserId, viewerId, viewerIsAdmin || schoolAccess || cohortScope != null);
        if (schoolAccess && !"PUBLIC".equals(privacy(targetUserId).getProfileVisibility())) {
            audit(viewerId, null, "SCHOOL_PRIVATE_PROFILE_VIEW", null, null, "targetUserId=" + targetUserId);
        }
        if (cohortScope != null && !"PUBLIC".equals(privacy(targetUserId).getProfileVisibility())) {
            audit(viewerId, null, "COHORT_PRIVATE_PROFILE_VIEW", null, null, "targetUserId=" + targetUserId
                    + ",schoolId=" + cohortScope.get("schoolId") + ",year=" + cohortScope.get("enrollmentYear"));
        }
        return user;
    }

    public PublicProfileView publicProfile(Long targetUserId, Long viewerId, boolean viewerIsAdmin) {
        AppUser user = requireReadableProfile(targetUserId, viewerId, viewerIsAdmin);
        UserPrivacy privacy = privacy(targetUserId);
        // 管理员看了不公开的档案：留痕
        if (viewerIsAdmin && !targetUserId.equals(viewerId) && !"PUBLIC".equals(privacy.getProfileVisibility())) {
            audit(viewerId, null, "ADMIN_PRIVATE_PROFILE_VIEW", null, null, "targetUserId=" + targetUserId);
        }
        SchoolMember membership = schoolService.myMemberships(targetUserId).stream()
                .filter(m -> "APPROVED".equals(m.getStatus()))
                .findFirst()
                .orElse(null);
        boolean showEmail = targetUserId.equals(viewerId) || viewerIsAdmin || privacy.isShowEmail();
        String avatarUrl = targetUserId.equals(viewerId) || privacy.isShowAccounts() ? user.getAvatarUrl() : null;
        return new PublicProfileView(targetUserId, user.getDisplayName(), avatarUrl, user.getBio(),
                membership == null ? user.getEnrollmentYear() : membership.getEnrollmentYear(),
                membership == null ? user.getClassName() : membership.getClassName(),
                membership == null ? null : membership.getSchoolName(),
                showEmail ? user.getEmail() : null,
                true, null);
    }

    // ---------- 账号注销 ----------

    /**
     * 注销账号：删除本人数据（级联删除绑定账号、提交、统计、凭据与导入记录）。
     * 不能把最后一个能管理管理员的账号删掉，避免系统无人可分配权限。
     */
    @Transactional
    public void deleteAccount(Long userId, String password, String confirmUsername,
                              String ip, String userAgent) {
        deleteAccountChecked(userId, password, confirmUsername, ip, userAgent, false);
    }

    /** 已经用 SecurityActionService 确认过本人：不再要密码 */
    @Transactional
    public void deleteVerifiedAccount(Long userId, String confirmUsername, String ip, String userAgent) {
        deleteAccountChecked(userId, null, confirmUsername, ip, userAgent, true);
    }

    /** 与管理端一致：先按 id 锁全部管理员，再锁本人；消费确认令牌也必须遵守此顺序。 */
    @Transactional
    public AppUser lockAccountDeletion(Long userId) {
        adminUsers.lockAdmins();
        return userMapper.lockIdentity(userId);
    }

    private void deleteAccountChecked(Long userId, String password, String confirmUsername, String ip,
                                      String userAgent, boolean confirmed) {
        AppUser user = lockAccountDeletion(userId);
        if (user == null) {
            throw ApiException.notFound("用户不存在");
        }
        if (!user.getUsername().equals(confirmUsername)) {
            throw ApiException.validation("请输入正确的用户名以确认注销");
        }
        if (!confirmed && (!passwordEnabled(user) || !StringUtils.hasText(password)
                || !passwordEncoder.matches(password, user.getPasswordHash()))) {
            throw ApiException.validation("密码不正确");
        }
        if ("ADMIN".equals(user.getRole()) && userMapper.countActiveAdmins() <= 1) {
            throw ApiException.conflict("这是最后一个管理员账号，不能注销");
        }
        if (adminAccess.of(userId).keeper() && !adminAccess.anotherKeeper(userId)) {
            throw ApiException.conflict("这是最后一个拥有管理员与权限管理能力的账号，不能注销");
        }
        tokenStore.revokeAllSessions(userId);
        audit(userId, user.getUsername(), "ACCOUNT_DELETED", ip, userAgent, null);
        userMapper.delete(userId);
        log.warn("用户 {} 已注销并删除本机数据", user.getUsername());
    }

    /** 导出本人数据，用于自查与备份；不含密码、令牌与平台凭据。 */
    public Map<String, Object> exportMyData(Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", Instant.now().toString());
        data.put("profile", me(userId));
        // 认证记录里的说明与 User-Agent 来自请求，导出前同样去掉可能夹带的凭据
        data.put("authEvents", identityMapper.recentEvents(userId, 200).stream().map(event -> {
            Map<String, Object> copy = new LinkedHashMap<>(event);
            copy.computeIfPresent("detail", (k, v) -> Redaction.redact(v.toString()));
            copy.computeIfPresent("userAgent", (k, v) -> Redaction.redact(v.toString()));
            return copy;
        }).toList());
        data.put("note", "导出内容不含密码、访问令牌与平台登录态；平台凭据仅以加密形式保存在本机，不可导出");
        return data;
    }

    public Duration refreshTokenTtl() {
        return tokenStore.refreshTokenTtl();
    }

    public void audit(Long userId, String username, String event, String ip, String userAgent, String detail) {
        identityMapper.insertEvent(userId, username, event, ip,
                userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())), detail);
    }
}
