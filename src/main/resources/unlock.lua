---
--- Created by maffy.
--- DateTime: 2024/9/11 下午4:50
---
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call("del", KEYS[1])
end
return 0

