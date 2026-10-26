# 360 车载哨兵 · CarSentinel

把车内所有摄像头的画面实时推到手机上，随时看、随时回放。

- **手机端（iOS / SwiftUI）**：四路宫格实时看、单路全屏轮播、按小时回放录像、哨兵事件列表、设备管理
- **车机端（Android / Kotlin + Compose）**：Camera2 采集 → 硬件编码 → HLS 切片 → 局域网 HTTP 服务
- **链路**：同一 WiFi 下直连，**没有云端中转**，画面不出局域网
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
└─ docs/PROTOCOL.md            两端接口协议
```

---

## 一、手机端（iOS）

### 装了怎么用

1. 车机端 App 启动，开热点（或与手机连同一 WiFi）
2. 手机打开「360 车载哨兵」→ 设备 → 连接车机
3. 自动扫到「车载哨兵」就点它；扫不到手输 `车机IP:8710`
4. 首次连接 iOS 会弹「本地网络」权限，**必须允许**，否则永远连不上

连不上时会自动落在**演示数据**上：四路合成画面、三天录像、八条事件，
用来验证布局与交互。UI 不会因为没连车机而变成空白。

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

- `CameraPipeline` —— Camera2 打开摄像头，输出到 MediaCodec 的 input Surface，
  硬编 H.264（默认 720p25，四路同时编码）
- `HlsWriter` —— 手写 MPEG-TS 复用（PAT/PMT/PES + MPEG-2 CRC32），
  **只在 IDR 帧切片**，维护滚动直播 playlist，每 60 秒滚一个录像片段
- `HttpServer` —— 手写 HTTP/1.1 服务（支持 Range），提供 4 个接口 + m3u8 / ts
- `NsdHelper` —— mDNS 广播 `_carsentry._tcp`，手机端自动发现
- `SentryService` —— 前台服务，锁屏 / 后台不停；每 5 分钟清理 3 天前的文件
- `BootReceiver` —— 开机自启，见上
- `SentryPrefs` —— 本地设置（自启开关、设备名）


### 摄像头来源的坑

Camera2 只能看到系统注册的摄像头。**如果你的车机装的是 USB(UVC) 摄像头
或者 360 环视盒子，`cameraIdList` 里往往没有它们**，此时要换采集源：

- UVC 摄像头 → 用 `UsbManager` + `UsbCamera` 类库，把 YUV 帧喂给 MediaCodec
  （`COLOR_FormatYUV420Flexible`，不再用 input Surface）
- 环视盒子 → 一般已经在车机里跑着，直接拉它的 RTSP 流，用 `MediaCodec` 解码再编码，
  或者干脆透传（`HttpServer` 加一个 `/rtsp` 反代）

`HlsWriter` 和 `HttpServer` 跟采集源无关，换源时不用动。

---

## 三、协议

`docs/PROTOCOL.md`。四个接口，秒级看完：

```
GET  /api/v1/info                      车机快照（手机每 3 秒轮询）
GET  /api/v1/recordings?from&to&camera 录像列表
GET  /api/v1/events?since              哨兵事件
POST /api/v1/control                   录制开关 / 驻车模式 / 截图
GET  /live/{cam}/index.m3u8            直播
GET  /rec/{clipId}/index.m3u8          录像回放
```

---

## 四、交付前检查

- [ ] `docs/PROTOCOL.md` 的字段名两端一致（camelCase、epoch 秒）
- [ ] 手机端「设备」页能连上、能断开、能记住上次地址
- [ ] 四路同时拉流时手机不烫、不卡（真不行就把 `streamQuality` 降到 720p）
- [ ] iPhone SE 尺寸下宫格不塌版、长设备名不溢出
- [ ] `.ts` 分片必须从 IDR 开始（否则花屏）—— 这是 HLS 最容易漏的一条
- [ ] 车机防火墙没拦 8710
- [ ] **重启车机一次，确认手机端不点任何按钮就能看到画面**（验证开机自启）
- [ ] 车机系统设置里把 App 加入自启动白名单，否则上面那条一定失败
