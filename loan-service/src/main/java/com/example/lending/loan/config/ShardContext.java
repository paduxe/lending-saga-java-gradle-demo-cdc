package com.example.lending.loan.config;

// Chứa thông tin Shard hiện tại cho mỗi luồng (ThreadLocal)
public class ShardContext {
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    public static void setCurrentShard(String shard) {
        CONTEXT.set(shard);
    }

    public static String getCurrentShard() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
