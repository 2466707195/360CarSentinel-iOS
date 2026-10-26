# 360 车载哨兵 · CarSentinel

把车内所有摄像头的画面实时推到手机上，随时看、随时回放。

- **手机端（iOS / SwiftUI）**：四路宫格实时看、单路全屏轮播、按小时回放录像、哨兵事件列表、设备管理；全局**透明玻璃**材质
- **车机端（Android / Kotlin + Compose）**：五种采集源（Camera2 / UVC / RTSP / 裸流 / 测试信号）→ 编码 → HLS 切片 → HTTP 服务
- **两种连法**：同一 WiFi 直连（无中转，画面不出局域网）；或车机主动外连**中继**，手机在任何网络下都能看
- **界面**：Aurora UI（手机与车机两端同一套设计 token）

```
360CarSentinel/
├─ project.yml                 XcodeGen 工程定义（.xcodeproj 不入库）
├─ .github/workflows/          云端构建：iOS → 未签名 ipa；Android → debug apk
├─ scripts/                    build / publish / inspect 脚本
├─ CarSentinel/                ← iOS 源码
│  ├─ App/                     入口、根视图、自绘底栏
│  ├─ Theme/                   Aurora 设计 token
│  ├─ UI/Common/               组件库（卡片/按钮/输入框/骨架）
│  ├─ UI/Components/           本 App 专用零件（合成画面、开关、胶囊、提示条）
│  ├─ Core/                    模型、API 客户端、状态中枢、发现、播放器
│  └─ Screens/                 实时 / 回放 / 事件 / 设备
├─ android/                    ← 车机端源码（Android Studio 打开）
├─ relay/                      ← 远程观看的中继服务（零依赖 Node，可 Docker）
└─ docs/PROTOCOL.md            两端接口协议
```

---

## 一、手机端（iOS）

### 装了怎么用

1. 车机端 App 启动，开热点（或与手机连同一 WiFi）
2. 手机打开「360 车载哨兵」→ 设备 → 连接车机
3. 自动扫到「车载哨兵」就点它；扫不到手输 `车机IP:8710`
4. 首次连接 iOS 会弹「本地网络」权限，**必须允许**，否则永远连不上

不在车旁边就走另一条路：连接弹窗切到「远程中继」，填中继地址 + 房间号 + 密钥
（见下面第三节）。两种模式在协议层完全一样，只是 baseURL 不同。

连不上时会自动落在**演示数据**上：四路合成画面、三天录像、八条事件，
用来验证布局与交互。UI 不会因为没连车机而变成空白。

### 透明玻璃材质

全 App 的按钮、底栏、操作条、输入框、筛选胶囊统一走 `UI/Common/AuroraGlass.swift` 一套修饰器：

| 层 | 作用 |
| --- | --- |
| `.ultraThinMaterial` | 系统级实时模糊，深浅色自适应。**不是** 自己调个半透明色 —— 那样只是「蒙了一层灰」 |
| 可选色染 | 表达语义：主按钮品牌色、危险按钮玫瑰色、选中态 |
| 顶部白色高光 | 制造玻璃厚度，缺了这层就只是一块半透明色块 |
| 双层描边（外亮内淡） | 边缘才「立」得起来，白底卡片上也看得出边界 |

硬规则：**不要**换成 iOS 26 的 `glassEffect` / `GlassEffectContainer`。
那会把最低 SDK 顶到 26，云端 CI 一旦跑旧镜像直接编不过。`Material` 是 iOS 15 就有的，
效果接近，兼容性安全得多。

### 四个页面

| 页面 | 内容 |
| --- | --- |
| **实时** | 连接状态 / 总码率 / 在线机位 / 存储剩余 + 2×2 宫格 + 录制开关 |
| **回放** | 近 7 天日期选项 + 机位筛选 + 当天汇总 + 按小时分组列录像 |
| **事件** | 按告警级别上色的卡片，点进去直接跳到对应录像 |
| **设备** | 连接管理、存储占用、录制设置、机位清单、关于 |

### 出 ipa（不需要 Mac）

```powershell
# 一条命令：建仓库 → 推代码 → 触发 Actions → 轮询 → 下载 ipa
powershell -File scripts\publish-github.ps1 -Token ghp_xxx

# 本地验包：BundleID / 版本 / 最低系统 / 是否 arm64 / 有无残留签名
powershell -File scripts\inspect-ipa.ps1
```

产出的 `build/CarSentinel-unsigned.ipa` 拖进全能签 / ESign，选证书重签 → 装机。

Token 权限：classic token 勾 `repo` + `workflow`；fine-grained 需要
Contents / Actions / Workflows 三项读写。

### 几个刻意的技术选择

| 选择 | 原因 |
| --- | --- |
| **不用 iOS 18 的 `Tab`、不用 iOS 26 的 `glassEffect`** | 用了就要 Xcode 16/26 的 SDK，CI 一旦跑在旧镜像上直接编不过。底栏改成自绘，视觉和 Android 端逐值一致，任何 Xcode ≥ 15.4 都能编 |
| **HLS 而不是 WebRTC** | AVPlayer 原生支持，零第三方依赖，CI 不会因为拉 pod 失败。1~2 秒延迟对「看一眼车周围」够用。要换只需改 `Core/StreamPlayer.swift` |
| **合成画面而不是黑屏** | 没连车机时四个黑窟窿看起来像 App 坏了。合成画面还能顺便验证 16:9 裁切、时间戳叠字、离线遮罩这些真机才暴露的排版问题 |
| **相对路径而不是绝对 URL** | 车机换 IP 时手机端不用重新拉一遍摄像头列表 |

---

## 二、车机端（Android）

### 出 apk（不需要 Android SDK）

推 `android/` 下的改动，GitHub Actions 自动编译；也可以去 Actions 页手动
「Run workflow」：

```
https://github.com/2466707195/360CarSentinel-iOS/actions/workflows/android-build.yml
```

产物在最近一次 run 的 Artifacts → `carsentry-apk`。这是 **debug 签名的 apk**
（APK Signing Block v2/v3，Android 7+ 可装），直接装车机即可；
要上应用市场得自己配 release keystore。

本地有 Android Studio 的话，打开 `android/` 目录直接 Run 也一样。

### 装到车机

1. apk 拷到 U 盘，插车机 → 文件管理器点开安装（车机需允许「未知来源」）
2. 或者 `adb install -r 车载哨兵-车机端-v1.0.0.apk`
3. 首次打开授予**相机**权限 —— 没有这个权限，画面一定出不来

### 开机自启

车机是「点火通电、熄火断电」的设备，没人会每次上车先点一下 App，所以默认开启。

| 环节 | 实现 |
| --- | --- |
| 权限 | `RECEIVE_BOOT_COMPLETED` |
| 广播 | `BootReceiver` 收 `BOOT_COMPLETED` + `QUICKBOOT_POWERON`（部分国产 ROM 的私有广播）+ `MY_PACKAGE_REPLACED`（App 升级后自动恢复，系统不会为此重发开机广播） |
| 开关 | App 界面「开机自启」卡片，状态存 SharedPreferences，**默认开** |
| 拉起的服务 | `startForegroundService(SentryService)`，返回 `START_STICKY`，被系统杀掉会自己回来 |

几个刻意的处理：

- **开机瞬间同步读设置** —— 用 SharedPreferences 而不是 DataStore，异步库在这里来不及
- **相机权限被撤销时不硬启服务**（起来也是「无画面」），改发一条可点开的通知
- **被 ROM 拒绝时降级**：`ForegroundServiceStartNotAllowedException` 会被 catch 住并发通知，
  而不是静默失败
- **界面上能看真实状态**：卡片里显示「开机广播：已注册 / 被系统禁用」，
  避免开关明明开着、其实组件被系统禁掉的错觉

> ⚠️ **有些车机会拦第三方 App 自启**，这属于系统级限制，App 没有权限绕过。
> 如果重启后没起来，去系统的「自启动管理 / 后台运行白名单」里把「车载哨兵」放行一次。
> 只能手动做，且不同车机路径不一样（通常在 设置 → 应用 → 自启动）。

### 它做了什么

| 文件 | 职责 |
| --- | --- |
| `VideoSource` / `VideoSink` | 采集源统一抽象：任何源只要吐出「Annex-B 访问单元 + 时间戳」，后面的切片 / HLS / HTTP 全部复用 |
| `Camera2Source` | 标准 Camera2，含 **Android 9+ 的外接 USB 摄像头**（`LENS_FACING_EXTERNAL`）。零拷贝：Camera → MediaCodec input Surface |
| `UvcSource` | 直接按 UVC 协议驱动摄像头（Camera2 里看不到时用）。手写描述符解析、PROBE→COMMIT 协商、等时传输、MJPEG 重组 |
| `RtspSource` | RTSP 拉流（TCP interleaved），自写 RTP 拆包（单 NAL / STAP-A / FU-A） |
| `RawH264Source` | 裸 H.264 Annex-B：`tcp://`、`udp://`、文件 / FIFO / 设备节点 |
| `TestPatternSource` | 彩条测试信号，**分层排障用** |
| `ChannelRunner` | 一路机位的执行器：源 + 切片器 + 1s→20s 指数退避重连 + 码率窗口统计 |
| `SourceRegistry` | 采集源发现、按机位持久化、首次自动分配 |
| `H264` | Annex-B 解析、AVCC↔Annex-B、增量流解析、访问单元组装 |
| `MediaEncoder` | CPU 侧 YUV 软编（`COLOR_FormatYUV420Flexible` + `getInputImage`）、MJPEG→ARGB 解码 |
| `HlsWriter` | 手写 MPEG-TS 复用（PAT/PMT/PES + MPEG-2 CRC32），**只在 IDR 帧切片**，滚动直播 playlist + 每 60 秒滚一个录像片段 |
| `HttpServer` | 手写 HTTP/1.1 服务（支持 Range），4 个接口 + m3u8 / ts |
| `RelayTunnel` | 远程观看：车机主动外连中继，把本地 HTTP 服务隧道化出去 |
| `NsdHelper` | mDNS 广播 `_carsentry._tcp`，手机端自动发现 |
| `SentryService` | 前台服务，锁屏 / 后台不停；每 5 分钟清理 3 天前的文件 |
| `BootReceiver` / `UsbAttachReceiver` | 开机自启、USB 摄像头插拔即重连 |
| `SentryPrefs` | 本地设置（自启、设备名、四路采集源、中继身份） |

### 五种采集源

装车时最大的问题是「cameraId 0 到底是前视还是后视」没法自动判断 ——
不同厂商的映射完全不同。所以首次启动会按 Camera2 顺序自动铺四路，
剩下的交给用户在「采集源」卡片里点一下改，改完落盘。

| 类型 | 适用设备 | 是否重编码 |
| --- | --- | --- |
| **Camera2** | 车机自带倒车/环视摄像头；Android 9+ 注册进系统的外接 UVC | 硬编（零拷贝） |
| **USB(UVC)** | 插上但**没**注册进 Camera2 的 USB 摄像头（后装安卓大屏最常见） | MJPEG 解码 → 软编 |
| **RTSP** | 360 环视盒子、4G 记录仪、车规 IPC | 不重编码，RTP 直接转 Annex-B |
| **裸流 H.264** | 厂商私有接口：TCP/UDP 端口推 Annex-B、或 `/data/vendor/...` 设备节点 | 不重编码 |
| **测试信号** | 排障用，不接任何摄像头 | 软编 |

> **排障顺序就用这个顺序**：先选「测试信号」。
> 手机能看到彩条 = 编码 / 切片 / HTTP / 手机端整条链路是通的，问题一定在采集源；
> 彩条也看不到 = 别去折腾摄像头，先查网络和服务。

同一台 UVC 摄像头如果**同时**出现在 Camera2 和 UVC 列表里，用 Camera2 ——
那是零拷贝路径，CPU 占用低一个数量级。

`HlsWriter`、`HttpServer`、`RelayTunnel` 跟采集源完全无关，换源时不用动。

---

## 三、远程观看（不在车旁边也能看）

### 为什么是「隧道」而不是推流到云

常见的做法是车机推 RTMP/WebRTC 到服务器，服务器再转给手机 —— 那要额外转码、
额外带宽，而车机的上行往往只有几 Mbps，四路一起推必炸。

这里的做法是把**本地 HTTP 服务整体隧道化**：

```
手机  https://relay.example.com/v1/{房间号}/{密钥}/live/front/index.m3u8
        │
        ▼
中继  relay/server.js  ──WebSocket（车机主动外连，穿透 NAT）──  车机
                                                                 │
                                              127.0.0.1:8710 ◄───┘
```

车机主动往外连，所以**不需要公网 IP、不需要端口映射、不需要 root**。
手机拿到的是一个普通 https 地址，AVPlayer 一行代码都没改。

### 部署中继（三选一）

```bash
# 1. 直接用 Node 跑（最省事，需要 Node 18+）
cd relay && node server.js            # 默认监听 8787

# 2. Docker
docker build -t carsentry-relay relay/
docker run -d -p 8787:8787 --name relay carsentry-relay

# 3. 丢到任意支持 Node 的 PaaS（Railway / Fly / Zeabur / 轻量云）
#    启动命令填 node relay/server.js，端口读 $PORT
```

生产环境建议前面挂 Nginx / Caddy 做 TLS（中继自己不做证书）。
`relay/README.md` 里有反代配置和帧协议说明。

### 两端怎么配

1. **车机端** → 「远程观看」卡片 → 打开开关 → 「设置中继」
   填 `wss://relay.example.com`（或 `relay.example.com`，会自动补 `wss://`）
   → 保存并连接。卡片上会显示**房间号**和**密钥**，点「复制」带走
2. **手机端** → 设备 → 连接车机 → 切到「远程中继」→ 填同样的三项 → 连接

> 房间号 + 密钥相当于门锁：房间号 6 位随机、密钥 24 位随机，首次进配置页自动生成并落盘，
> 泄露了点「换一组」重新生成即可。

延迟比局域网多一跳，大约 +100~300ms（取决于中继机房到车的距离）。
视频是原样透传，不经过二次转码，画质与局域网完全一致。

---

## 四、协议

`docs/PROTOCOL.md`。四个接口，秒级看完：

```
GET  /api/v1/info                      车机快照（手机每 3 秒轮询）
GET  /api/v1/recordings?from&to&camera 录像列表
GET  /api/v1/events?since              哨兵事件
POST /api/v1/control                   录制开关 / 驻车模式 / 截图
GET  /live/{cam}/index.m3u8            直播
GET  /rec/{clipId}/index.m3u8          录像回放
```

远程模式下，上面所有路径前面统一加一段前缀，**其余一字不变**：

```
https://{中继}/v1/{房间号}/{密钥}/api/v1/info
https://{中继}/v1/{房间号}/{密钥}/live/front/index.m3u8
```

中继只透传 `content-type` / `content-range` / `accept-ranges` / `cache-control`
这几个头，并补 `Access-Control-Allow-Origin: *`，视频字节流原样搬运。

---

## 五、交付前检查

- [ ] `docs/PROTOCOL.md` 的字段名两端一致（camelCase、epoch 秒）
- [ ] 手机端「设备」页能连上、能断开、能记住上次地址（含远程中继那条）
- [ ] 四路同时拉流时手机不烫、不卡（真不行就把 `streamQuality` 降到 720p）
- [ ] iPhone SE 尺寸下宫格不塌版、长设备名不溢出
- [ ] `.ts` 分片必须从 IDR 开始（否则花屏）—— 这是 HLS 最容易漏的一条
- [ ] 车机防火墙没拦 8710
- [ ] **重启车机一次，确认手机端不点任何按钮就能看到画面**（验证开机自启）
- [ ] 车机系统设置里把 App 加入自启动白名单，否则上面那条一定失败

### 装车调试：先跑测试信号

摄像头接不进来时，按这个顺序走，别跳步：

1. 「采集源」四路全选**测试信号** → 手机能看到彩条，说明编码 / 切片 / HTTP / 手机端都没问题
2. 测试信号正常 → 逐路换回真实摄像头，换到哪一路坏了就是哪一路的源有问题
3. Camera2 里没有你的摄像头 → 换「USB(UVC)」，**插着摄像头重进采集源页**重新扫描
4. 360 环视盒子 / 后装记录仪 → 用 RTSP，填盒子文档里的 `rtsp://` 地址
5. 厂商只给了私有接口 → 用「裸流」，填 `tcp://IP:端口`、`udp://IP:端口` 或设备节点路径
6. 全都试过还不行 → 说明摄像头走的是厂商闭源 SDK，需要厂商提供取流方式，
   这也是唯一 App 层解决不了的情况

> UVC 这条路要读设备描述符、自己谈 PROBE/COMMIT、处理等时传输 ——
> 各家摄像头的描述符完整度差别很大。如果 UVC 一直没画面，
> 优先确认这台车机是否已经把它注册进 Camera2（有的话直接用 Camera2，更省 CPU 也更稳）。
