package interview.guide.common.aspect;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 限流 AOP 切面
 * 支持可重复注解，逐条执行独立的限流规则，任一规则不通过即拒绝
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    private static final String LUA_SCRIPT;
    private String luaScriptSha;
    private RScript rScript;

    /**
     * 静态初始化，加载限流 Lua 脚本
     */
    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载限流 Lua 脚本失败", e);
        }
    }

    /**
     * 实例初始化，加载限流 Lua 脚本到 Redis Redisson 客户端
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        rScript = redissonClient.getScript(StringCodec.INSTANCE);
        loadScript();
    }

    /**
     * 加载限流 Lua 脚本到 Redis Redisson 服务器，获取脚本 SHA1 值
     *
     * 将限流 Lua 脚本加载到 Redis 服务器端，并获取其 SHA1 校验和
     *
     * 后续通过 evalSha() 使用此 SHA1 引用脚本，避免重复传输脚本内容
     *
     * 核心机制：
     * 性能优化：脚本只需传输一次，后续通过 SHA1 引用执行，减少网络带宽消耗
     * 原子性保证：Lua 脚本在 Redis 中作为原子操作执行，保证限流逻辑的线程安全
     * 缓存机制：脚本缓存在 Redis 内存中，提升执行效率
     */
    private void loadScript() {
        this.luaScriptSha = rScript.scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 环绕通知：拦截带 @RateLimit 或 @RateLimit.Container 注解的方法
     */
    @Around("@annotation(interview.guide.common.annotation.RateLimit) || " +
            "@annotation(interview.guide.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        RateLimit[] rules = method.getAnnotationsByType(RateLimit.class);
        long nowMs = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        for (RateLimit rule : rules) {
            long intervalMs = calculateIntervalMs(rule.interval(), rule.timeUnit());
            String key = generateKey(className, methodName, rule.dimension());

            Long result = executeRateLimitScript(key, nowMs, requestId, intervalMs, rule.count());

            if (result == null || result == 0) {
                return handleRateLimitExceeded(joinPoint, rule, key);
            }
        }

        return joinPoint.proceed();
    }

    /**
     * 执行限流 Lua 脚本
     * @param key
     * @param nowMs
     * @param requestId
     * @param intervalMs
     * @param count
     * @return 0 表示被限流
     */
    private Long executeRateLimitScript(String key, long nowMs, String requestId, long intervalMs, double count) {
        List<Object> keysList = Collections.singletonList(key);
        Object[] args = {
                String.valueOf(nowMs),
                String.valueOf(1),
                String.valueOf(intervalMs),
                String.valueOf(count),
                requestId
        };

        try {
            Object resultObj = rScript.evalSha(
                    RScript.Mode.READ_WRITE,
                    luaScriptSha,
                    RScript.ReturnType.VALUE,
                    keysList,
                    args
            );
            return convertToLong(resultObj);
        } catch (org.redisson.client.RedisException e) {
            // Redis 重启后脚本缓存丢失，重新加载并重试
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                loadScript();
                Object resultObj = rScript.evalSha(
                        RScript.Mode.READ_WRITE,
                        luaScriptSha,
                        RScript.ReturnType.VALUE,
                        keysList,
                        args
                );
                return convertToLong(resultObj);
            }
            throw e;
        }
    }

    /**
     * 计算时间间隔的毫秒数
     * @param interval
     * @param unit
     * @return
     */
    private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> interval;
            case SECONDS -> interval * 1000;
            case MINUTES -> interval * 60 * 1000;
            case HOURS -> interval * 3600 * 1000;
            case DAYS -> interval * 86400 * 1000;
        };
    }

    private Long convertToLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("无法将字符串转换为Long: {}", obj);
                return null;
            }
        }
        log.warn("不支持的对象类型转换为Long: {}", obj != null ? obj.getClass().getName() : "null");
        return null;
    }

    /**
     * 生成限流键
     * @param className
     * @param methodName
     * @param dimension
     * @return
     */
    private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
        String hashTag = "{" + className + ":" + methodName + "}";
        String keyPrefix = "ratelimit:" + hashTag;

        return switch (dimension) {
            case GLOBAL -> keyPrefix + ":global";
            case IP -> keyPrefix + ":ip:" + getClientIp();
            case USER -> keyPrefix + ":user:" + getCurrentUserId();
        };
    }

    /**
     * 处理限流触发，优先执行降级方法，如果不存在则抛出异常
     * @param joinPoint
     * @param rateLimit
     * @param key
     * @return
     * @throws Throwable
     */
    private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String key)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();

        if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
            try {
                Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            methodName,
                            rateLimit.fallback());
                    if (fallbackMethod.getParameterCount() > 0) {
                        return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                    } else {
                        return fallbackMethod.invoke(joinPoint.getTarget());
                    }
                }
            } catch (Exception e) {
                log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
            }
        }

        log.debug("限流触发，拒绝请求: key={}, count={} per {} {}",
                key, rateLimit.count(), rateLimit.interval(), rateLimit.timeUnit());
        throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    }

    /**
     * 根据名称查找降级方法
     * @param joinPoint
     * @param fallbackName
     * @return
     */
    private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] parameterTypes = signature.getParameterTypes();

        try {
            // 尝试查找带参数的降级方法
            Method method = targetClass.getDeclaredMethod(fallbackName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            try {
                // 尝试查找无参的降级方法
                Method method = targetClass.getDeclaredMethod(fallbackName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ex) {
                log.warn("未找到降级方法: {}.{} (需无参或参数列表一致)",
                        targetClass.getSimpleName(), fallbackName);
                return null;
            }
        }
    }

    /**
     * 获取客户端 IP 地址
     * @return
     */
    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "unknown";
    }

    /**
     * 获取当前用户 ID
     * @return
     */
    private String getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "anonymous";
        }

        HttpServletRequest request = attributes.getRequest();

        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return userId.toString();
        }

        userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return userId.toString();
        }

        return "anonymous";
    }
}
