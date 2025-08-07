package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * ClassName: CacheClients
 * Package: com.hmdp.utils
 */
@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /*
    方法1: 將任意JAVA Object 序列化成 JSON 並儲存在 redis string type key中
    並可以設置 TTL 過期時間
     */
    public <T> void set(String key, T value, long time, TimeUnit timeUnit) {
        String jsonValue = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, jsonValue, time, timeUnit);
    }

    /*
    方法2:將任意JAVA Object 序列化成 JSON 並儲存在 redis string type key中
    並可以設置邏輯過期時間，用以處理緩存擊穿問題
     */
    public <T> void setWithLogicalExpireTime(String key, T value, long logicalExpireTime, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        LocalDateTime expirationTime = LocalDateTime.now().plusSeconds(timeUnit.toSeconds(logicalExpireTime));
        redisData.setExpireTime(expirationTime);
        redisData.setData(value);
        String jsonValue = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().setIfAbsent(key, jsonValue);
    }

    /*
    方法3:根據指定的key查詢redis，並反序列化為指定類型，利用緩存空值的方式解決緩存穿透的問題
     */
    public <T, ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> clazz, Function<ID, T> dbFallBack,
                                          long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 1. 從 redis 查詢數據
        String jsonValue = stringRedisTemplate.opsForValue().get(key);
        // 2. 判斷是否存在
        if (StrUtil.isNotBlank(jsonValue)) {
            // 3. 存在直接返回
            return JSONUtil.toBean(jsonValue, clazz);
        }
        // 判斷是否為空值，解決緩存穿透
        if (jsonValue != null) {
            return null;
        }
        // 4. 不存在，則去資料庫查詢，因為查不同的資料庫肯定是不同方法，這種行為上的動態，就能用lambda
        T t = dbFallBack.apply(id);
        // 5. 判斷是否存在於資料庫
        if (t == null) {
            // 6. 不存在，將空值寫進 redis 以解決緩存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 7. 存在則寫入redis並返回
        set(key, t, time, timeUnit);
        return t;

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*
    方法4:根據指定的key查詢緩存，並反序列化為指定類型，利用緩存過期解決緩存擊穿問題
     */
    public <T, ID> T queryWithLogicalExpire(String keyPrefix, ID id, Class<T> clazz, Function<ID, T> dbFallBack
            , long logicalExpireTime, TimeUnit timeUnit) {
        // 1. 從 redis 查詢數據
        String key = keyPrefix + id;
        String jsonValue = stringRedisTemplate.opsForValue().get(key);
        // 2. 判斷是否存在
        if (StrUtil.isBlank(jsonValue)) {
            // 3. 未命中，返回 null
            return null;
        }
        // 4. 命中，反序列化 json
        RedisData redisData = JSONUtil.toBean(jsonValue, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        T bean = JSONUtil.toBean(jsonObject, clazz);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判斷是否邏輯過期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未過期，返回商品訊息
            return bean;
        }
        // 5.2 過期，需要緩存重建
        // 6.1 嘗試獲取互斥鎖
        boolean isLock = tryLock(key);
        // 6.2 判斷是否獲取成功
        if (isLock) {
            // 6.3 獲取成功，開啟獨立線程進行緩存重建
            /**
             * 6.3.1 獲取成功後，再次檢查 redis 中數據是否過期
             因為可能發生 thread 1 在緩存重建時，thread 2 正在判斷是否邏輯過期，
             而當 thread 緩存重建完釋放鎖後，thread 2 則可能剛好要嘗試獲取鎖，此時就會獲取到，但緩存卻重建完了
             因為獲取鎖後，需要再次判斷 redis 中的數據是否過期
             */
            String shopJsonCheck = stringRedisTemplate.opsForValue().get(key);
            RedisData redisDataCheck = JSONUtil.toBean(shopJsonCheck, RedisData.class);
            JSONObject jsonObjectCheck = (JSONObject) redisDataCheck.getData();
            T bean1 = JSONUtil.toBean(jsonObjectCheck, clazz);
            LocalDateTime expireTimeCheck = redisDataCheck.getExpireTime();
            //判斷是否邏輯過期
            if (expireTimeCheck.isAfter(LocalDateTime.now())) {
                //未過期，返回商品訊息
                return bean1;
            }
            //緩存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建緩存
                    T t = dbFallBack.apply(id);
                    setWithLogicalExpireTime(key, t, logicalExpireTime, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 重建完畢後釋放鎖
                    unlock(LOCK_SHOP_KEY + id);
                }
            });


        }
        // 6.4 獲取失敗，返回過期商品訊息
        return bean;
    }

    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
