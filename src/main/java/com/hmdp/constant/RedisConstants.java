package com.hmdp.constant;

public class RedisConstants {

    /**
     * 登录相关
     */
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;


    /**
     * 店铺缓存
     */
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final Long CACHE_NULL_TTL = 2L;

    /**
     * 店铺互斥锁
     */
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /**
     * 秒杀库存 key
     */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /**
     * Blog 喜欢 key
     */
    public static final String BLOG_LIKED_KEY = "blog:liked:";

    /**
     * 共同关注 key
     */
    public static final String COMMOIN_FOLLOW_KEY = "common:follow:";

    /**
     * 关注推送
     */
    public static final String FEED_KEY = "feed:";

    /**
     * 附近店铺
     */
    public static final String SHOP_GEO_KEY = "shop:geo:";

    /**
     * 用户签到
     */
    public static final String USER_SIGN_KEY = "sign:";
}
