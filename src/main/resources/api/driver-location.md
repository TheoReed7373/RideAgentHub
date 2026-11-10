# 司机位置与附近订单

以下接口均需登录司机账号（`DRIVER`），司机 ID 从登录态获取。

## 上线与位置心跳

`POST /order/driver/location`

请求体：

```json
{"lng":120.0,"lat":30.0}
```

经度范围 `[-180, 180]`，纬度范围 `[-85.05112878, 85.05112878]`，两个字段均必填。
建议上线后每 20 秒上报一次；距最后心跳满 60 秒即视为离线。
返回 `Result<Boolean>`，`data=true` 表示写入成功，`false` 表示缓存写入失败，应重试。

## 主动下线

`POST /order/driver/offline`

无请求体。返回 `Result<Boolean>`，`data=true` 表示已清理位置和心跳。
接单请求携带坐标时仅刷新仍在线的司机，不会把已下线司机重新上线。

## 查询在线状态与位置

`GET /order/driver/location`

返回 `Result<DriverLocationVO>`：

```json
{"code":200,"message":"success","data":{"online":true,"lng":120.0,"lat":30.0},"total":0}
```

以项目统一 `Result` 封装为准；离线时 `online=false`，坐标为空。
位置服务不可用时返回业务错误码 `503`，不会误报离线。

## 司机附近工单池

`GET /order/driver/pool/page?page=1&size=10`

页码和页大小必须为正整数。返回 `Result<PageResult<RideOrderVO>>`。

- 在线司机以当前位置查询三公里内待接单订单，按距离升序返回，最多取最近 200 个候选。
- `total` 是本次候选中有效订单数，最大 200，不代表全城订单总数。
- `distanceKm` 为司机到订单起点的直线距离，单位公里，保留两位小数。
- 离线司机返回空页。
- Redis 不可用、索引重建争用或候选在回表期间发生变化时，整页降级为数据库按创建时间、订单 ID 倒序查询；此时不限制三公里与 200 条，`distanceKm` 为空。

索引按事务提交后的订单变更统一失效，下次查询完整重建，避免冷启动时单笔新增遮蔽存量订单。
版本与锁令牌校验阻止并发旧快照发布；数据与就绪标记原子发布，固定两小时到期。
这种策略优先保证完整性，订单频繁变更时会增加全池重建开销。
附近查询使用 `GEORADIUS`，兼容 Redis 5 及以上版本。
