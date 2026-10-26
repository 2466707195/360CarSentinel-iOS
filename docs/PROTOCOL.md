# 360 车载哨兵 · 两端接口协议 v1

手机（iOS）与车机（Android）在同一局域网内直连，**没有云端中转**。
车机是一台 HTTP 服务端，默认端口 `8710`；手机是纯客户端。

```
┌────────────┐   同一 WiFi / 车机热点    ┌──────────────────────┐
│  iOS 手机端  │ ───────────────────────► │  Android 车机端        │
│             │  HTTP REST + HLS 分片     │  Camera2 → H264 → TS  │
│  1~4 路播放  │ ◄─────────────────────── │  HTTP Server :8710     │
└────────────┘                          └──────────────────────┘
```

---

## 0. 发现

车机启动后通过 mDNS 广播：

| 项 | 值 |
| --- | --- |
| 服务类型 | `_carsentry._tcp` |
| 服务名 | 车机名称（如「车载哨兵」） |
| 端口 | `8710` |
| TXT | `api=/api/v1` `name=<车机名>` `proto=1` |

手机端用 `NetServiceBrowser` 搜索；拿不到时回退到手输 IP。
iOS 需在 Info.plist 里声明 `NSBonjourServices` 与 `NSLocalNetworkUsageDescription`，
否则**不弹权限框、静默失败**。

---

## 1. 通用约定

- 全部 `Content-Type: application/json; charset=utf-8`
- 字段名 **camelCase**（不用下划线）
- 时间戳统一为 **epoch 秒**（Int），不用 ISO8601，也不带时区
- 字节数 Int64
- 失败返回非 2xx + `{"error":"原因"}`；手机端只看状态码，不解析 error
- 无鉴权（局域网内网设备）；如需加，可在 Header 加 `X-Sentry-Token`，两端留了扩展位

---

## 2. `GET /api/v1/info` — 车机快照

手机每 3 秒轮询一次，这是唯一的保活与状态来源。

```json
{
  "deviceName": "车载哨兵",
  "model": "Android 车机 · 4 路环视",
  "firmware": "1.0.0",
  "uptimeSec": 7320,
  "parkingMode": true,
  "recording": true,
  "storage": {
    "totalBytes": 128000000000,
    "usedBytes": 84000000000,
    "freeBytes": 44000000000,
    "retentionDays": 3
  },
  "cameras": [
    {
      "id": "front",
      "name": "前视",
      "position": "front",
      "online": true,
      "resolution": "1920×1080",
      "fps": 25,
      "bitrateKbps": 4200,
      "hlsPath": "live/front.m3u8",
      "snapshotPath": "snap/front.jpg"
    }
  ],
  "unreadEvents": 3
}
```

`position` 取值：`front` / `rear` / `left` / `right` / `cabin`。

> `hlsPath` / `snapshotPath` 是**相对路径**，不是完整 URL。
> 手机端用当前 endpoint 拼成绝对地址。这样车机换 IP 不用重新拉一遍列表。

---

## 3. `GET /api/v1/recordings` — 录像列表

查询参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `from` | int | epoch 秒，闭区间 |
| `to` | int | epoch 秒，开区间 |
| `camera` | string | 可选，摄像头 id |
| `limit` | int | 可选，默认 300 |

响应：

```json
{
  "clips": [
    {
      "id": "front-1761400000",
      "cameraId": "front",
      "startAt": 1761400000,
      "durationSec": 60,
      "sizeBytes": 31500000,
      "source": "loop",
      "locked": false,
      "hlsPath": "rec/front-1761400000/index.m3u8",
      "thumbPath": "snap/front.jpg"
    }
  ]
}
```

`source`：`loop`（循环录制）/ `event`（事件触发）/ `manual`（手动）。
`locked: true` 表示被事件锁定，循环覆盖时跳过。

录像回放用 **单文件 HLS**（VOD playlist，`#EXT-X-ENDLIST`），也可以直接给 MP4 路径，
AVPlayer 都能播；推荐 HLS，切分与直播复用同一套代码。

---

## 4. `GET /api/v1/events` — 哨兵事件

| 参数 | 说明 |
| --- | --- |
| `since` | 可选，epoch 秒。只返回该时间之后的事件 |

```json
{
  "events": [
    {
      "id": "evt-1761400000-1",
      "kind": "collision",
      "level": 4,
      "at": 1761400000,
      "cameraId": "front",
      "note": "检测到纵向加速度突变 1.8g",
      "clipId": "ev-front-1761399980",
      "isRead": false
    }
  ]
}
```

`kind`：`collision` / `motion` / `intrusion` / `parking` / `system`
`level`：1 信息 · 2 成功 · 3 警告 · 4 严重（决定手机端的告警配色）

`clipId` 可空。手机端点事件时优先用它打开录像；为空则按时间就近匹配。

---

## 5. `POST /api/v1/control` — 控制指令

```json
{ "action": "start_record" }
{ "action": "stop_record" }
{ "action": "snapshot", "cameraId": "front" }
{ "action": "set_parking_mode", "enabled": true }
```

成功返回 `{"ok": true}`。

---

## 6. 媒体路径

| 路径 | 内容 |
| --- | --- |
| `GET /live/{cameraId}.m3u8` | 直播 playlist（滚动窗口，3~5 个分片） |
| `GET /live/{cameraId}/seg{n}.ts` | 直播分片，2 秒一个 |
| `GET /snap/{cameraId}.jpg` | 当前帧截图（JPEG） |
| `GET /rec/{clipId}/index.m3u8` | 录像 playlist（VOD，带 ENDLIST） |
| `GET /rec/{clipId}/seg{n}.ts` | 录像分片 |

### 直播延迟怎么控制

- 分片 2 秒 → 端到端 2~4 秒
- 想要 1 秒级，改成 1 秒分片 + 在 m3u8 里写 `#EXT-X-PART`（LL-HLS 部分分片）
- 手机端 `AVPlayerItem.preferredForwardBufferDuration = 1`，且
  `automaticallyWaitsToMinimizeStalling = false`，已经按低延迟调好

> 为什么不用 WebRTC：延迟确实更低，但要在 iOS 侧引入 GoogleWebRTC 依赖，
> 云端 CI 编译时间和失败率都会翻倍。作为「看一眼车周围」的监看场景，
> HLS 的 2 秒延迟够用，可靠性和可维护性收益更大。
> 真要换，只需替换 `Core/StreamPlayer.swift`，页面层不用动。

---

## 7. 错误与超时

| 情况 | 行为 |
| --- | --- |
| 连接超时 4 秒 | 手机端标记 `未连接`，停止轮询，保留上次数据 |
| 返回非 2xx | 手机端不重试，等下一次 3 秒轮询 |
| 拉流失败 | 该机位显示「拉流失败」，其他机位不受影响 |
| 断线重连 | 设置里开「自动重连」时，回到该 WiFi 后自动恢复 |

---

## 8. 安全边界

- 不加任何云端上报，画面只走局域网
- 车机侧建议只监听 `0.0.0.0:8710`，并确保该端口不进公网
- 手机端不在本地落任何视频文件（截图除外）
