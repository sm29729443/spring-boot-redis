package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店鋪不存在");
        }
        // 返回
        return Result.ok(shop);
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
}
