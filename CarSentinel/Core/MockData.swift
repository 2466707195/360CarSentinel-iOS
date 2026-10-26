import Foundation

// =============================================================================
// 演示数据
//
// 手机还没连上车机时（或者车机端还没刷进去时），整 App 不能是一片空白。
// 这里生成一套「看起来像真的」的摄像头 / 录像 / 事件，字段结构和真实接口一致，
// 所以从演示切到真机不用改任何 UI 代码。
// =============================================================================

extension CameraSource {

    static let demoSet: [CameraSource] = [
        CameraSource(
            id: "front", name: "前视", position: .front, online: true,
            resolution: "1920×1080", fps: 25, bitrateKbps: 4_200,
            hlsPath: "live/front.m3u8", snapshotPath: "snap/front.jpg"
        ),
        CameraSource(
            id: "rear", name: "后视", position: .rear, online: true,
            resolution: "1920×1080", fps: 25, bitrateKbps: 3_900,
            hlsPath: "live/rear.m3u8", snapshotPath: "snap/rear.jpg"
        ),
        CameraSource(
            id: "left", name: "左视", position: .left, online: true,
            resolution: "1280×720", fps: 25, bitrateKbps: 2_100,
            hlsPath: "live/left.m3u8", snapshotPath: "snap/left.jpg"
        ),
        CameraSource(
            id: "right", name: "右视", position: .right, online: true,
            resolution: "1280×720", fps: 25, bitrateKbps: 2_100,
            hlsPath: "live/right.m3u8", snapshotPath: "snap/right.jpg"
        )
    ]
}

enum MockLibrary {

    // 不需要真随机；固定种子的 LCG 保证每次进 App 数据一致
    private struct Rng {
        var state: UInt64
        mutating func next() -> UInt64 {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return state >> 33
        }
        /// 0..<bound
        mutating func int(_ bound: Int) -> Int {
            guard bound > 0 else { return 0 }
            return Int(next() % UInt64(bound))
        }
    }

    static func clips(now: Date = Date(), days: Int = 3) -> [RecordingClip] {
        var rng = Rng(state: 0x5EED_1234)
        let calendar = Calendar.current
        let cameras = CameraSource.demoSet
        let nowEpoch = Int(now.timeIntervalSince1970)

        var result: [RecordingClip] = []

        for dayOffset in 0..<days {
            guard let dayStart = calendar.date(
                byAdding: .day, value: -dayOffset, to: now
            ).map({ calendar.startOfDay(for: $0) }) else { continue }

            let dayEpoch = Int(dayStart.timeIntervalSince1970)

            // 每天 6:00 – 23:00，每小时一段，四路都有
            for hour in 6..<23 {
                for camera in cameras {
                    let start = dayEpoch + hour * 3_600 + rng.int(20)
                    // 未来的时间段不生成（今天下午 3 点不该有晚上 8 点的录像）
                    guard start + 60 <= nowEpoch else { continue }

                    let duration = 60
                    let bitrate = camera.bitrateKbps
                    let size = Int64(bitrate * 1_000 / 8 * duration)

                    result.append(
                        RecordingClip(
                            id: "\(camera.id)-\(start)",
                            cameraId: camera.id,
                            startAt: start,
                            durationSec: duration,
                            sizeBytes: size,
                            source: .loop,
                            locked: false,
                            hlsPath: "rec/\(camera.id)-\(start)/index.m3u8",
                            thumbPath: "snap/\(camera.id).jpg"
                        )
                    )
                }
            }

            // 每天补两段「事件触发」的长录像（30 秒，带锁）
            for _ in 0..<2 {
                let camera = cameras[rng.int(cameras.count)]
                let hour = 12 + rng.int(9)
                let start = dayEpoch + hour * 3_600 + rng.int(600)
                guard start + 30 <= nowEpoch else { continue }
                result.append(
                    RecordingClip(
                        id: "ev-\(camera.id)-\(start)",
                        cameraId: camera.id,
                        startAt: start,
                        durationSec: 30,
                        sizeBytes: Int64(camera.bitrateKbps * 1_000 / 8 * 30),
                        source: .event,
                        locked: true,
                        hlsPath: "rec/ev-\(camera.id)-\(start)/index.m3u8",
                        thumbPath: "snap/\(camera.id).jpg"
                    )
                )
            }
        }

        return result.sorted { $0.startAt > $1.startAt }
    }

    static func events(now: Date = Date()) -> [SentinelEvent] {
        let nowEpoch = Int(now.timeIntervalSince1970)

        let seeds: [(EventKind, Int, String, Int, Bool)] = [
            (.collision, 6 * 60, "检测到纵向加速度突变 1.8g", 0, false),
            (.intrusion, 47 * 60, "车尾 2 米内检测到移动目标", 1, false),
            (.motion, 3 * 3_600 + 12 * 60, "驻车期间画面持续变化 12 秒", 2, false),
            (.parking, 9 * 3_600, "驻车状态下检测到侧向撞击", 1, true),
            (.motion, 26 * 3_600, "前视画面检测到行人经过", 3, true),
            (.system, 2 * 86_400, "存储卡剩余空间低于 15%，已开始循环覆盖", 0, true),
            (.intrusion, 2 * 86_400 + 5 * 3_600, "有人靠近车辆左侧 1.2 米", 2, true),
            (.collision, 3 * 86_400, "检测到轻微剐蹭，已锁定前后 30 秒", 1, true)
        ]

        let cameras = CameraSource.demoSet

        return seeds.enumerated().map { index, seed in
            let (kind, ago, note, cameraIndex, read) = seed
            let camera = cameras[min(cameraIndex, cameras.count - 1)]
            return SentinelEvent(
                id: "evt-\(index)",
                kind: kind,
                level: kind.defaultLevel,
                at: nowEpoch - ago,
                cameraId: camera.id,
                note: note,
                clipId: nil,
                isRead: read
            )
        }
    }

    /// 演示模式下的「实时码率」，随刷新轻微浮动，让界面看起来是活的。
    static func jitterRate(_ base: Int, tick: Int) -> Int {
        let table = [-180, 40, 210, -60, 130, -240, 90, 20, 160, -110]
        return max(200, base + table[tick % table.count])
    }
}
