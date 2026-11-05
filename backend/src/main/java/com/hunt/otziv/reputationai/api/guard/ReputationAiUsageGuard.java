package com.hunt.otziv.reputationai.api.guard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Cost and capacity guard for paid AI calls (single application instance, in memory).
 * <ul>
 *   <li>a per-user daily limit of paid AI operations stops a runaway loop or script from
 *       burning the provider budget; the default is far above normal manual use;</li>
 *   <li>synchronous heavy endpoints (they hold a Tomcat thread for minutes) share a small
 *       concurrency limit; async jobs are bounded by their own executor queue.</li>
 * </ul>
 */
public class ReputationAiUsageGuard implements HandlerInterceptor {

    private static final String PERMIT_ATTRIBUTE = ReputationAiUsageGuard.class.getName() + ".permit";
    private static final Set<String> SYNCHRONOUS_HEAVY_SUFFIXES = Set.of("/research", "/deep-research", "/content-pack");

    private final int dailyLimitPerUser;
    private final Semaphore synchronousHeavyCalls;
    private final ZoneId zone;
    private final ConcurrentHashMap<String, AtomicInteger> usage = new ConcurrentHashMap<>();
    private volatile LocalDate usageDay;

    public ReputationAiUsageGuard(int dailyLimitPerUser, int maxConcurrentSynchronousHeavyCalls, ZoneId zone) {
        this.dailyLimitPerUser = Math.max(1, dailyLimitPerUser);
        this.synchronousHeavyCalls = new Semaphore(Math.max(1, maxConcurrentSynchronousHeavyCalls));
        this.zone = zone;
        this.usageDay = LocalDate.now(zone);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !isPaidOperation(request)) {
            return true;
        }
        countUsage();
        if (isSynchronousHeavy(request)) {
            if (!synchronousHeavyCalls.tryAcquire()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Сейчас выполняется несколько AI-анализов. Повторите через минуту или используйте фоновый запуск.");
            }
            request.setAttribute(PERMIT_ATTRIBUTE, Boolean.TRUE);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(PERMIT_ATTRIBUTE))) {
            request.removeAttribute(PERMIT_ATTRIBUTE);
            synchronousHeavyCalls.release();
        }
    }

    private void countUsage() {
        LocalDate today = LocalDate.now(zone);
        if (!today.equals(usageDay)) {
            synchronized (this) {
                if (!today.equals(usageDay)) {
                    usage.clear();
                    usageDay = today;
                }
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String user = authentication == null ? "anonymous" : authentication.getName();
        int used = usage.computeIfAbsent(user, ignored -> new AtomicInteger()).incrementAndGet();
        if (used > dailyLimitPerUser) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Достигнут дневной лимит AI-операций для вашей учётной записи. Лимит обновится завтра.");
        }
    }

    private static boolean isPaidOperation(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && !path.contains("/api/ai/reputation/status") && !path.contains("/api/ai/reputation/prompts");
    }

    private static boolean isSynchronousHeavy(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.contains("/api/ai/reputation/companies/")) {
            return false;
        }
        return SYNCHRONOUS_HEAVY_SUFFIXES.stream().anyMatch(path::endsWith);
    }
}
