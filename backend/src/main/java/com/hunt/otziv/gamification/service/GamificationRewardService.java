package com.hunt.otziv.gamification.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimStatusRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardSettings;
import com.hunt.otziv.gamification.dto.GamificationTokenGrantRequest;
import com.hunt.otziv.gamification.dto.GamificationWalletResponse;
import com.hunt.otziv.gamification.model.GamificationReward;
import com.hunt.otziv.gamification.model.GamificationRewardClaim;
import com.hunt.otziv.gamification.model.GamificationTokenLedger;
import com.hunt.otziv.gamification.repository.GamificationRewardClaimRepository;
import com.hunt.otziv.gamification.repository.GamificationRewardRepository;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.gamification.repository.GamificationTokenLedgerRepository;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.service.UserService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GamificationRewardService {

    private static final Set<String> TYPES = Set.of("VIRTUAL", "MATERIAL", "PRIVILEGE", "CERTIFICATE");
    private static final Set<String> ACTIVE_CLAIMS = Set.of("REQUESTED", "APPROVED", "FULFILLED");
    private static final Set<String> ADMIN_STATUSES = Set.of("APPROVED", "FULFILLED", "REJECTED", "CANCELLED");

    private final GamificationRewardRepository rewardRepository;
    private final GamificationRewardClaimRepository claimRepository;
    private final GamificationTokenLedgerRepository tokenRepository;
    private final GamificationScoreLedgerRepository scoreRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final AppSettingService appSettingService;
    private final S3UploadService s3UploadService;

    @Transactional(readOnly = true)
    public List<GamificationRewardResponse> adminRewards() {
        return rewardRepository.findAllByOrderBySortOrderAscTitleAsc().stream()
                .map(reward -> response(reward, null))
                .toList();
    }

    @Transactional
    public GamificationRewardResponse save(Long rewardId, GamificationRewardRequest request) {
        if (request == null) throw badRequest("Данные награды не переданы");
        GamificationReward reward = rewardId == null
                ? new GamificationReward()
                : rewardRepository.findById(rewardId).orElseThrow(() -> notFound("Награда не найдена"));
        String code = normalizeCode(request.code());
        String title = text(request.title(), 160);
        if (code.isBlank()) throw badRequest("Укажите код награды");
        if (title == null || title.isBlank()) throw badRequest("Укажите название награды");
        rewardRepository.findByCode(code)
                .filter(found -> reward.getId() == null || !found.getId().equals(reward.getId()))
                .ifPresent(found -> { throw badRequest("Награда с таким кодом уже существует"); });

        String type = normalizeType(request.rewardType());
        reward.setCode(code);
        reward.setTitle(title);
        reward.setDescription(text(request.description(), 1000));
        reward.setRewardType(type);
        reward.setIcon(text(request.icon(), 80));
        reward.setImageUrl(text(request.imageUrl(), 600));
        reward.setTokenCost(nonNegative(request.tokenCost()));
        reward.setRequiredLevel(Math.max(1, value(request.requiredLevel(), 1)));
        reward.setStockQuantity(request.stockQuantity() == null ? null : Math.max(0, request.stockQuantity()));
        reward.setActive(Boolean.TRUE.equals(request.active()));
        reward.setSortOrder(value(request.sortOrder(), 0));
        return response(rewardRepository.save(reward), null);
    }

    @Transactional
    public GamificationRewardResponse uploadImage(Long rewardId, MultipartFile file) {
        GamificationReward reward = rewardRepository.findById(rewardId)
                .orElseThrow(() -> notFound("Награда не найдена"));
        String imageUrl = s3UploadService.uploadFile(file, "gamification/rewards", reward.getImageUrl(), reward.getId());
        reward.setImageUrl(imageUrl);
        return response(rewardRepository.save(reward), null);
    }

    @Transactional(readOnly = true)
    public List<GamificationRewardResponse> catalog(Principal principal) {
        if (!rewardsEnabled()) return List.of();
        User user = currentUser(principal);
        Wallet wallet = wallet(user.getId(), false);
        return rewardRepository.findByActiveTrueOrderBySortOrderAscTitleAsc().stream()
                .map(reward -> response(reward, wallet))
                .toList();
    }

    @Transactional
    public GamificationWalletResponse wallet(Principal principal) {
        User user = currentUser(principal);
        Wallet wallet = wallet(user.getId(), true);
        return wallet.response();
    }

    @Transactional
    public GamificationRewardClaimResponse claim(Long rewardId, GamificationRewardClaimRequest request, Principal principal) {
        if (!rewardsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Каталог наград пока выключен");
        User user = currentUser(principal);
        userRepository.lockById(user.getId()).orElseThrow(() -> notFound("Пользователь не найден"));
        GamificationReward reward = rewardRepository.findForUpdate(rewardId)
                .orElseThrow(() -> notFound("Награда не найдена"));
        if (!reward.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Награда недоступна");
        Wallet wallet = wallet(user.getId(), true);
        String lockedReason = lockedReason(reward, wallet);
        if (lockedReason != null) throw new ResponseStatusException(HttpStatus.CONFLICT, lockedReason);
        if (claimRepository.existsByUserIdAndRewardIdAndStatusIn(user.getId(), reward.getId(), Set.of("REQUESTED", "APPROVED"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заявка на эту награду уже рассматривается");
        }

        GamificationRewardClaim claim = new GamificationRewardClaim();
        claim.setReward(reward);
        claim.setUserId(user.getId());
        claim.setTokenCost(reward.getTokenCost());
        claim.setComment(text(request == null ? null : request.comment(), 1000));
        claim = claimRepository.save(claim);
        if (reward.getTokenCost() > 0) {
            addTokens(user.getId(), -reward.getTokenCost(), "REWARD_CLAIM", "Заявка на награду «" + reward.getTitle() + "»", "claim:" + claim.getId() + ":debit");
        }
        return claimResponse(claim);
    }

    @Transactional(readOnly = true)
    public List<GamificationRewardClaimResponse> myClaims(Principal principal) {
        return claimRepository.findByUserIdOrderByRequestedAtDesc(currentUser(principal).getId()).stream()
                .map(this::claimResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GamificationRewardClaimResponse> adminClaims() {
        return claimRepository.findAllByOrderByRequestedAtDesc().stream().map(this::claimResponse).toList();
    }

    @Transactional
    public GamificationRewardClaimResponse updateClaim(Long claimId, GamificationRewardClaimStatusRequest request) {
        GamificationRewardClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> notFound("Заявка не найдена"));
        String next = request == null ? "" : upper(request.status());
        if (!ADMIN_STATUSES.contains(next)) throw badRequest("Некорректный статус заявки");
        String previous = claim.getStatus();
        if (!previous.equals(next) && !allowedTransition(previous, next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя изменить статус заявки с " + previous + " на " + next);
        }
        if (("REJECTED".equals(next) || "CANCELLED".equals(next))
                && !("REJECTED".equals(previous) || "CANCELLED".equals(previous))
                && claim.getTokenCost() > 0) {
            addTokens(claim.getUserId(), claim.getTokenCost(), "REWARD_REFUND", "Возврат за награду «" + claim.getReward().getTitle() + "»", "claim:" + claim.getId() + ":refund");
        }
        claim.setStatus(next);
        claim.setAdminComment(text(request == null ? null : request.adminComment(), 1000));
        claim.setFulfilledAt("FULFILLED".equals(next) ? LocalDateTime.now() : null);
        return claimResponse(claimRepository.save(claim));
    }

    @Transactional
    public GamificationWalletResponse grantTokens(GamificationTokenGrantRequest request) {
        if (request == null || request.userId() == null || request.amount() == null || request.amount() == 0) {
            throw badRequest("Укажите пользователя и ненулевое количество жетонов");
        }
        userRepository.findById(request.userId()).orElseThrow(() -> notFound("Пользователь не найден"));
        addTokens(request.userId(), request.amount(), "ADMIN_GRANT", text(request.description(), 500),
                "admin:" + request.userId() + ":" + System.nanoTime());
        return wallet(request.userId(), false).response();
    }

    @Transactional(readOnly = true)
    public GamificationRewardSettings settings() {
        return new GamificationRewardSettings(
                rewardsEnabled(),
                appSettingService.getBoolean("manager.gamification.competition-enabled", false),
                Math.max(100, appSettingService.getInt("manager.gamification.level-xp", 500)),
                Math.max(2, appSettingService.getInt("manager.gamification.token-level-step", 5)),
                appSettingService.getBoolean("manager.sla.enabled", false),
                bounded(appSettingService.getInt("manager.sla.control-target-hours", 14), 1, 24),
                bounded(appSettingService.getInt("manager.sla.day-target-percent", 90), 1, 100),
                positive(appSettingService.getInt("manager.sla.target.message-minutes", 30)),
                positive(appSettingService.getInt("manager.sla.hard.message-minutes", 480)),
                positive(appSettingService.getInt("manager.sla.target.lead-minutes", 60)),
                positive(appSettingService.getInt("manager.sla.hard.lead-minutes", 480)),
                positive(appSettingService.getInt("manager.sla.target.risk-minutes", 30)),
                positive(appSettingService.getInt("manager.sla.hard.risk-minutes", 240)),
                positive(appSettingService.getInt("manager.sla.target.default-minutes", 120)),
                positive(appSettingService.getInt("manager.sla.hard.default-minutes", 720))
        );
    }

    @Transactional
    public GamificationRewardSettings updateSettings(GamificationRewardSettings request) {
        if (request == null) throw badRequest("Настройки не переданы");
        appSettingService.setBoolean("manager.gamification.rewards-enabled", request.rewardsEnabled());
        appSettingService.setBoolean("manager.gamification.competition-enabled", request.competitionEnabled());
        appSettingService.setInt("manager.gamification.level-xp", Math.max(100, request.levelXp()));
        appSettingService.setInt("manager.gamification.token-level-step", Math.max(2, request.tokenLevelStep()));
        appSettingService.setBoolean("manager.sla.enabled", request.slaEnabled());
        appSettingService.setInt("manager.sla.control-target-hours", bounded(request.controlTargetHours(), 1, 24));
        appSettingService.setInt("manager.sla.day-target-percent", bounded(request.dayTargetPercent(), 1, 100));
        saveSla("message", request.messageTargetMinutes(), request.messageHardMinutes());
        saveSla("lead", request.leadTargetMinutes(), request.leadHardMinutes());
        saveSla("risk", request.riskTargetMinutes(), request.riskHardMinutes());
        saveSla("default", request.defaultTargetMinutes(), request.defaultHardMinutes());
        return settings();
    }

    private void saveSla(String type, int target, int hard) {
        int safeTarget = positive(target);
        appSettingService.setInt("manager.sla.target." + type + "-minutes", safeTarget);
        appSettingService.setInt("manager.sla.hard." + type + "-minutes", Math.max(safeTarget, positive(hard)));
    }

    private Wallet wallet(Long userId, boolean syncLevelTokens) {
        long xp = number(scoreRepository.lifetimePointsForActor(userId));
        int levelXp = Math.max(100, appSettingService.getInt("manager.gamification.level-xp", 500));
        int level = (int) Math.min(Integer.MAX_VALUE, xp / levelXp + 1);
        int step = Math.max(2, appSettingService.getInt("manager.gamification.token-level-step", 5));
        if (syncLevelTokens) {
            for (int milestone = step; milestone <= level; milestone += step) {
                addTokens(userId, 1, "LEVEL_REWARD", "Жетон за уровень " + milestone, "level:" + userId + ":" + milestone);
            }
        }
        long rawBalance = tokenRepository.balance(userId);
        int balance = (int) Math.max(0, Math.min(Integer.MAX_VALUE, rawBalance));
        int nextTokenLevel = ((Math.max(1, level) + step - 1) / step) * step;
        if (nextTokenLevel <= level) nextTokenLevel += step;
        return new Wallet(xp, level, balance, nextTokenLevel);
    }

    private void addTokens(Long userId, int amount, String reason, String description, String uniqueKey) {
        if (tokenRepository.existsByUniqueEntryKey(uniqueKey)) return;
        GamificationTokenLedger entry = new GamificationTokenLedger();
        entry.setUserId(userId);
        entry.setAmount(amount);
        entry.setReasonCode(reason);
        entry.setDescription(description);
        entry.setUniqueEntryKey(uniqueKey);
        try {
            tokenRepository.save(entry);
        } catch (DataIntegrityViolationException ignored) {
            // Идемпотентная выдача: параллельный запрос уже создал запись.
        }
    }

    private GamificationRewardResponse response(GamificationReward reward, Wallet wallet) {
        String reason = wallet == null ? null : lockedReason(reward, wallet);
        return new GamificationRewardResponse(
                reward.getId(), reward.getCode(), reward.getTitle(), reward.getDescription(), reward.getRewardType(),
                reward.getIcon(), reward.getImageUrl(), reward.getTokenCost(), reward.getRequiredLevel(),
                availableStock(reward), reward.isActive(), reward.getSortOrder(), wallet != null && reason == null,
                reason, reward.getUpdatedAt()
        );
    }

    private String lockedReason(GamificationReward reward, Wallet wallet) {
        if (!reward.isActive()) return "Награда выключена";
        if (wallet.level() < reward.getRequiredLevel()) return "Доступно с уровня " + reward.getRequiredLevel();
        if (wallet.tokens() < reward.getTokenCost()) return "Недостаточно жетонов";
        Integer stock = availableStock(reward);
        if (stock != null && stock <= 0) return "Награды закончились";
        return null;
    }

    private Integer availableStock(GamificationReward reward) {
        if (reward.getStockQuantity() == null) return null;
        long reserved = claimRepository.countReserved(reward.getId(), ACTIVE_CLAIMS);
        return (int) Math.max(0, reward.getStockQuantity() - reserved);
    }

    private GamificationRewardClaimResponse claimResponse(GamificationRewardClaim claim) {
        User user = userRepository.findById(claim.getUserId()).orElse(null);
        String userName = user == null ? "ID " + claim.getUserId() : user.getFio() == null || user.getFio().isBlank()
                ? user.getUsername() : user.getFio();
        return new GamificationRewardClaimResponse(
                claim.getId(), claim.getReward().getId(), claim.getReward().getTitle(), claim.getReward().getImageUrl(),
                claim.getUserId(), userName, claim.getStatus(), claim.getTokenCost(), claim.getComment(),
                claim.getAdminComment(), claim.getRequestedAt(), claim.getUpdatedAt(), claim.getFulfilledAt()
        );
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private boolean rewardsEnabled() {
        return appSettingService.getBoolean("manager.gamification.rewards-enabled", false);
    }

    private boolean allowedTransition(String previous, String next) {
        if ("REQUESTED".equals(previous)) return Set.of("APPROVED", "REJECTED", "CANCELLED").contains(next);
        if ("APPROVED".equals(previous)) return Set.of("FULFILLED", "REJECTED", "CANCELLED").contains(next);
        return false;
    }

    private String normalizeCode(String value) {
        return upper(value).replaceAll("[^A-Z0-9_-]", "_").replaceAll("_+", "_");
    }

    private String normalizeType(String value) {
        String type = upper(value);
        return TYPES.contains(type) ? type : "VIRTUAL";
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(String value, int max) {
        if (value == null) return null;
        String result = value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private int nonNegative(Integer value) { return Math.max(0, value(value, 0)); }
    private int positive(int value) { return Math.max(1, value); }
    private int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private int value(Integer value, int fallback) { return value == null ? fallback : value; }
    private long number(Long value) { return value == null ? 0L : value; }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record Wallet(long xp, int level, int tokens, int nextTokenLevel) {
        private GamificationWalletResponse response() { return new GamificationWalletResponse(xp, level, tokens, nextTokenLevel); }
    }
}
