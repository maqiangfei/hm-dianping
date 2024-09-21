---
--- Created by maffy.
--- DateTime: 2024/9/11 下午7:31
---
-- 用户id
local userId = ARGV[1]
-- 秒杀券id
local voucherId = ARGV[2]
-- 订单id
--local orderId = ARGV[3]

-- 用户集合key
local usersKey = "seckill:order:" .. voucherId
-- 库存key
local stockKey = "seckill:stock:" .. voucherId

if (tonumber(redis.call("get", stockKey)) < 1) then
    -- 库存不足
    return 1
end

if (redis.call("sismember", usersKey, userId) == 1) then
    -- 用户下过单
    return 2
end

-- 扣库存
redis.call("incrby", stockKey, -1)
-- 保存用户
redis.call("sadd", usersKey, userId)
-- 将订单信息发送进消息队列，xadd stream.orders * k1 v1 k2 v2 ...
--redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0