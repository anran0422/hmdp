-- 1. 参数列表
-- 优惠券 ID
local voucherId = ARGV[1]
-- 用户 ID
local userId = ARGV[2]
-- 订单 ID
local orderId = ARGV[3]

-- 2. 数据 key
-- 优惠券库存 key
local stockKey = "seckill:stock:" .. voucherId
-- 订单 key（用户是否下单）
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足
local stock = redis.call('get', stockKey)
if(not stock or tonumber(stock) <= 0) then
    -- 库存不足，返回 1
    return 1
end

-- 3.2 判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 存在，说明重复下单 返回2
    return 2
end

-- 3.3 扣除库存
redis.call('incrby', stockKey, -1)
-- 3.4 逻辑下单，保存用户Id
redis.call('sadd', orderKey, userId)
-- 3.5 发送消息到队列中 XDD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*',
        'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0