# 远程观看中继服务

让手机在**任何网络**下都能看到车机画面，而车机不需要公网 IP、不需要端口映射、不需要 root。

```
┌────────┐   WSS（车机主动外连）    ┌──────────┐   HTTPS    ┌────────┐
│  车机  │ ─────────────────────► │ 中继服务 │ ◄───────── │  手机  │
└────────┘     房间号 + 密钥       └──────────┘            └────────┘
```

车机把本地 8710 端口的 HTTP 服务「隧道化」到中继上，手机拿到的是一个普通 HTTPS 地址。
视频字节流原样透传，**中继不解码、不转码**，1 核 512MB 的小机器就能跑几十路。

## 为什么不用 RTMP / WebRTC 推流

| 方案 | 车机上行 | 服务器成本 | iOS 端改动 |
|---|---|---|---|
| RTMP 推流 | 需要编码器第二遍推流 | 要转协议 + 带宽 | 全部重写 |
| WebRTC | 要 SFU | 高 | 全部重写 |
| **HTTP over WS 隧道** | 只在有人看时才有流量 | 几乎为零 | **零改动** |

HLS 本来就是一串普通 HTTP 请求，隧道把它原样搬过去就行。

---

## 部署

### 方式 A：任意能跑 Node 的机器（推荐）

```bash
cd relay
PORT=8787 node server.js
```

然后用 Nginx / Caddy 反代，**必须支持 WebSocket**：

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
    proxy_buffering off;
}
```

Caddy 更省事：

```
relay.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

### 方式 B：Docker

```bash
docker build -t car-sentry-relay .
docker run -d --restart=always -p 8787:8787 --name relay car-sentry-relay
```

### 方式 C：免费 PaaS

Railway / Fly.io / Zeabur / Render 都能直接跑这个 `server.js`（零依赖，不用 `npm install`）。
部署完拿到域名即可。

> **不建议用 Cloudflare Workers**：Workers 的 WebSocket 有 30 秒空闲断开与时长限制，
> 长时间看视频会被掐。真要用得改写成 Durable Object，复杂度不值得。

---

## 车机端配置

1. 打开「车载哨兵」→ 远程观看 → 打开开关 → 设置中继
2. 填中继地址：`wss://relay.example.com`
3. 房间号 / 密钥会自动生成，点「复制房间号与密钥」

## 手机端配置

打开 App → 设备 → 连接车机 → **远程** 页签，填：

| 字段 | 值 |
|---|---|
| 中继地址 | `relay.example.com`（不要带 https:// 和末尾斜杠） |
| 房间号 | 车机上显示的那串 |
| 密钥 | 车机上显示的那串 |

保存后手机端所有请求都会走 `https://relay.example.com/v1/{房间号}/{密钥}/...`。

---

## 接口

| 路径 | 说明 |
|---|---|
| `WSS /relay?room=&key=&role=car` | 车机接入（WebSocket 升级） |
| `ANY /v1/{room}/{key}/{车机路径}` | 手机访问，路径原样转发给车机 |
| `GET /health` | 健康检查，返回在线车机数 |

鉴权就是「房间号 + 密钥」的组合：不知道密钥拿不到画面，密钥泄露了在车机上换一组即可。

## 隧道帧协议（车机 ↔ 中继）

二进制帧，第 1 字节是类型，第 2–5 字节是请求 id（大端）：

| 值 | 方向 | 含义 |
|---|---|---|
| `0x01` | 中继→车机 | 请求头（JSON：method / path / headers） |
| `0x02` | 中继→车机 | 请求体分片 |
| `0x03` | 中继→车机 | 请求体结束 |
| `0x04` | 车机→中继 | 响应头（JSON：status / headers） |
| `0x05` | 车机→中继 | 响应体分片 |
| `0x06` | 车机→中继 | 响应结束 |
| `0x07` | 车机→中继 | 出错（UTF-8 错误信息） |

实现分别在 `android/.../RelayTunnel.kt` 与 `relay/server.js`，
两边改一处必须同步另一处。

## 自测

仓库根目录的 `tools/relay_fake_car.js` 会起一个假车机 + 假手机，
一条命令验证整条链路（不需要真车机）：

```bash
node tools/relay_fake_car.js
# 输出「✅ 隧道打通」即正常
```
