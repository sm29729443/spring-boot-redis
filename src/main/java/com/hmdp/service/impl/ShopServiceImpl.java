package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 緩存穿透
        // Shop shop = queryWithPassThrough(id);
        // 互斥鎖解決緩存擊穿
        // Shop shop = queryWithMutex(id);
        // 邏輯過期時間解決緩存擊穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店鋪不存在");
        }
        // 返回
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 封裝邏輯過期的程式碼
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 從 redis 查詢數據
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判斷是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 未命中，返回 null
            return null;
        }
        // 4. 命中，反序列化 json
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判斷是否邏輯過期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未過期，返回商品訊息
            return shop;
        }
        // 5.2 過期，需要緩存重建
        // 6.1 嘗試獲取互斥鎖
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        // 6.2 判斷是否獲取成功
        if (isLock) {
            // 6.3 獲取成功，開啟獨立線程進行緩存重建
            /**
             * 6.3.1 獲取成功後，再次檢查 redis 中數據是否過期
             因為可能發生 thread 1 在緩存重建時，thread 2 正在判斷是否邏輯過期，
             而當 thread 緩存重建完釋放鎖後，thread 2 則可能剛好要嘗試獲取鎖，此時就會獲取到，但緩存卻重建完了
             因為獲取鎖後，需要再次判斷 redis 中的數據是否過期
             */
//            String shopJsonCheck = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//            RedisData redisDataCheck = JSONUtil.toBean(shopJsonCheck, RedisData.class);
//            JSONObject jsonObjectCheck = (JSONObject) redisDataCheck.getData();
//            Shop shopCheck = JSONUtil.toBean(jsonObjectCheck, Shop.class);
//            LocalDateTime expireTimeCheck = redisDataCheck.getExpireTime();
//            //判斷是否邏輯過期
//            if (expireTimeCheck.isAfter(LocalDateTime.now())) {
//                //未過期，返回商品訊息
//                return shopCheck;
//            }
            //緩存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建緩存
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 重建完畢後釋放鎖
                    unlock(LOCK_SHOP_KEY + id);
                }
            });


        }
        // 6.4 獲取失敗，返回過期商品訊息
        return shop;
    }

    // 互斥鎖解決緩存擊穿程式碼
    public Shop queryWithMutex(Long id) {
        // 1. 從 redis 查詢數據
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判斷是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中，返回商品訊息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 因為 (6.) 已經改成解決緩存穿透了，所以這邊要判斷 redis 拿到的是否為 ""
        if (shopJson != null) {
            return null;
        }
        // 4. 實現緩存重建
        // 4.1 嘗試獲取互斥鎖
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判斷是否獲取到互斥鎖
            if (!isLock) {
                // 4.3 失敗則休眠並重試
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            // 4.4 成功，根據id查詢資料庫
            shop = getById(id);
            // 模擬緩存重建的延遲
            Thread.sleep(200);
            // 5. 判斷是否存在
            if (shop == null) {
                // 6. 不存在，將 null 寫入 redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 釋放互斥鎖
            unlock(lockKey);
        }
        // 8. 返回
        return shop;
    }

    // 封裝緩存穿透的程式碼
    public Shop queryWithPassThrough(Long id) {
        // 1. 從 redis 查詢數據
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判斷是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中，返回商品訊息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 因為 (6.) 已經改成解決緩存穿透了，所以這邊要判斷 redis 拿到的是否為 ""
        if (shopJson != null) {
            return null;
        }
        // 4. 未命中，去資料庫查詢
        Shop shop = getById(id);
        // 5. 判斷是否存在
        if (shop == null) {
            // 6. 不存在，將 null 寫入 redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 7. 存在，寫入 redis 並返回數據
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("商鋪 id 不得為空");
        }
        // 1. 更新資料庫
        updateById(shop);
        // 2. 刪除緩存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查詢店鋪數據
        Shop shop = getById(id);
        // 模擬緩存重建的時間
        Thread.sleep(8000);
        // 2. 封裝邏輯過期時間
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 寫入 redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
