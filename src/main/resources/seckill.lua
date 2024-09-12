---
--- Created by maffy.
--- DateTime: 2024/9/11 下午7:31
---
-- 用户id
local userId = ARGV[1]
-- 秒杀券id
local voucherId = ARGV[2]

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

return 0