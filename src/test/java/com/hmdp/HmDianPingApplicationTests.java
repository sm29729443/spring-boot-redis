package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;


    // 模擬提前添加熱點 key to redis
    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }


}
