package com.ongoing.workbench.util;

/**
 * 当前请求登录用户上下文（由 AuthInterceptor 写入/清理）。
 * 控制器层统一从这里取 userId，保证按用户过滤口径一致。
 */
public final class UserContext {

    private static final ThreadLocal<String> UID = new ThreadLocal<>();

    private UserContext() {}

    public static void set(String userId) { UID.set(userId); }

    /** 当前登录用户 id；未登录（不应发生，被拦截器挡住）时为 null */
    public static String currentUserId() { return UID.get(); }

    public static void clear() { UID.remove(); }
}
