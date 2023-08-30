package com.zch.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zch.dto.Result;
import com.zch.entity.Shop;
import com.zch.mapper.ShopMapper;
import com.zch.service.IShopService;
import com.zch.utils.CacheClient;
import com.zch.utils.RedisData;
import com.zch.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.zch.utils.RedisConstants.*;

/**
 * @author Zch
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // huancunchauntou(id);
        // 使用工具类解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

        // 下面是使用互斥锁解决缓存击穿
        /*Shop shop = huancunjichaun(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }*/
        // 使用工具类中的互斥锁解决缓存击穿
        /*Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);*/

        // 下面是使用逻辑过期解决缓存穿透
        // Shop shop = huancunjichuan2(id);
        // 使用工具类中的逻辑过期解决缓存击穿
        /*Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                20L,
                TimeUnit.SECONDS);*/

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    // 定义两个方法，分别是获得锁和释放锁，使用redis的 setnx 实现
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void delLock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 这里使用线程池，因为逻辑过期中的缓存重建是另外新建一个线程进行重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 这个函数是解决缓存击穿，使用的逻辑过期
    public Shop huancunjichuan2(Long id) {
        // 1. 从redis中查
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 没有命中缓存，不存在就返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 3. 命中缓存，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1. 未过期，直接返回此店铺信息
            return shop;
        }
        // 5.2 已过期，需要缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if (isLock) {
            // 6.3 如果成功则开启一个线程进行重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    delLock(lockKey);
                }
            });
        }
        // 7. 不管是否重建都需要返回过期的商铺信息
        return shop;
    }

    // 这个函数是解决缓存击穿，使用的互斥锁
    public Shop huancunjichaun(Long id) {
        // 1. 从redis中查
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 有就返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 在这里也解决缓存穿透的问题，需要判断是否是缓存的空值（“”）
        // 如果是为空值，则直接报错
        if ("".equals(shopJson)) {
            return null;
        }
        // 3. 实现缓存重建
        // 3.1. 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 3.2. 判断是否获取成功
            if (! isLock) {
                // 3.3. 失败则休眠重试
                Thread.sleep(50);
                return huancunjichaun(id);
            }
            // 3.4. 成功根据id查询数据库
            shop = getById(id);
            // 4. 不存在 返回错误
            if (shop == null) {
                // 在这里解决缓存穿透的问题，需要将空值（“”）缓存到redis中
                // 并设置过期时间为 2m
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5. 写入redis，这里加入过期时间，超时剔除作为兜底方案
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 释放锁
            delLock(lockKey);
        }
        // 7. 返回
        return shop;
    }

    // 这个函数是解决缓存穿透
    public Shop huancunchauntou(Long id) {
        // 1. 从redis中查
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 有就返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 在这里也解决缓存穿透的问题，需要判断是否是缓存的空值（“”）
        // 如果是为空值，则直接报错
        if ("".equals(shopJson)) {
            return null;
        }
        // 3. 没有则从数据库中查
        Shop shop = getById(id);
        if (shop == null) {
            // 在这里解决缓存穿透的问题，需要将空值（“”）缓存到redis中
            // 并设置过期时间为 2m
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 4. 数据库中有的话，就写入缓存并返回
        // 5. 写入redis，这里加入过期时间，超时剔除作为兜底方案
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6. 返回
        return shop;
    }

    // 这个方法就是重建缓存
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional // 加上事务，保证更新数据库和删除缓存的原子性，我们的策略是先更新数据库再删除缓存
    public Result update(Shop shop) {
        // 1. shop的id不存在时报错
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 2. 更新数据库
        updateById(shop);
        // 3. 删除redis中的数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
