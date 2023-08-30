package com.zch.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zch.dto.Result;
import com.zch.entity.VoucherOrder;
import com.zch.mapper.VoucherOrderMapper;
import com.zch.service.ISeckillVoucherService;
import com.zch.service.IVoucherOrderService;
import com.zch.utils.RedisIDWorker;
import com.zch.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Zch
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 加载seckill.lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 多线程 异步下单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 第一种，使用Java的阻塞队列实现异步
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }

        // 下面是第二种方法，未使用消息队列进行异步
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }

//        private void handleVoucherOrder(VoucherOrder voucherOrder) {
//            RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
//            // 获取锁，这里不给参数，即使用默认值
//            boolean isLock = lock.tryLock();
//            // 判断是否获取锁成功
//            if (!isLock) {
//                // 可以失败或者重试，这里选择失败，因为是一人一单
//                log.error("不允许重复下单！");
//                return;
//            }
//            try {
//                proxy.createVoucherOrder(voucherOrder);
//            } finally {
//                // 释放锁
//                lock.unlock();
//            }
//        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = iSeckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    // 下面是使用redis中的Stream实现消息队列完成异步
    @Override
    public Result seckillOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }


    // 下面将某些业务分离出来，放入redis中，并且数据库的业务使用异步执行
//    private IVoucherOrderService proxy;
//    @Override
//    public Result seckillOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        int r = result.intValue();
//        // 2.判断结果是否为0
//        if (r != 0) {
//            // 2.1.不为0 ，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.3.订单id
//        long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 2.4.用户id
//        voucherOrder.setUserId(userId);
//        // 2.5.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 3.返回订单id
//        return Result.ok(orderId);
//    }

    // 下面这是将所有业务放在同一个线程里面执行，串行执行
//    @Override
//    public Result seckillOrder(Long voucherId) {
//        // 1. 查询优惠券信息
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 3. 未开始则返回错误
//            return Result.fail("秒杀还未开始！");
//        }
//        // 4. 开始则判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 5. 不充足则返回错误
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//        /* （1）这是一人一单第一种方案，但是在集群模式下会出问题 *//*
//        // 这里这个intern是为了每次当字符串值相等时，引用都不变
//        // 在事务之前就获取锁，为了防止提交事务之前就释放了锁
//        *//*synchronized (userId.toString().intern()) {
//            // 这里得到当前代理对象，调用方法，防止事务失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }*/
//
//        /* （2）这是一人一单第二种方案，解决第一种方案的方法，使用redis实现分布式锁的第一个版本 */
//        // 创建锁对象
//        /*SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        // 获取锁
//        boolean isLock = lock.tryLock(10);
//        // 判断是否获取锁成功
//        if (! isLock) {
//            // 可以失败或者重试，这里选择失败，因为是一人一单
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁
//            lock.unLock();
//        }*/
//
//        /* （3）这是上一种方案的升级版，使用Redisson，提供更加丰富成熟的功能 */
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁，这里不给参数，即使用默认值
//        boolean isLock = lock.tryLock();
//        // 判断是否获取锁成功
//        if (! isLock) {
//            // 可以失败或者重试，这里选择失败，因为是一人一单
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }

    // 这个函数是从上边抽离出来的，主要是为了实现一人一单的功能
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5. 一人一单
//        Long userId = UserHolder.getUser().getId();
//        // 5.1 查询订单
//        int count = query().eq("user_id", userId)
//                .eq("voucher_id", voucherId)
//                .count();
//        // 判断该用户订单是否存在过
//        if (count > 0) {
//            // 用户购买过
//            return Result.fail("用户已经购买过一次了！");
//        }
//        // 6. 充足则扣减库存
//        // 这里采用加乐观锁的方法解决并发下超卖问题
//        // 乐观锁可以有两种实现：版本号或CAS，这里使用CAS
//        boolean isSuccess = iSeckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                // 这里加上CAS实现，只需要再判断库存是否大于0即可
//                .gt("stock", 0)
//                .update();
//        if (!isSuccess) {
//            return Result.fail("秒杀失败！");
//        }
//        // 7. 创建订单
//        long orderId = redisIDWorker.nextId("order");
//        VoucherOrder order = new VoucherOrder();
//        order.setId(orderId);
//        order.setVoucherId(voucherId);
//        // 当前用户id
//        order.setUserId(userId);
//        // 保存进数据库
//        save(order);
//        // 8. 返回订单号
//        return Result.ok(orderId);
//    }
}
