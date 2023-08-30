package com.zch.utils;

/**
 * 这个接口用于Redis实现分布式锁
 * @author Zch
 * @date 2023/8/26
 **/
public interface ILock {

    boolean tryLock(long timeoutSec);

    void unLock();

}
