import Foundation

// =============================================================================
// 360 车载哨兵 · 领域模型
//
// 这些结构体和车机端 `/api/v1/*` 的 JSON 一一对应，字段名全部 camelCase。
// 协议细节见 docs/PROTOCOL.md。
// =============================================================================

// MARK: - 摄像头

/// 摄像头在车身上的位置。车机端用这个字段决定画面朝向与角标文案。
enum CameraPosition: String, Codable, CaseIterable, Hashable {
    case front
    case rear
    case left
    case right
    case cabin

    var label: String {
        switch self {
        case .front: return "前视"
        case .rear: return "后视"
        case .left: return "左视"
        case .right: return "右视"
        case .cabin: return "车内"
        }
    }

    /// 宫格角标用的单字，小屏也放得下。
    var shortLabel: String {
        switch self {
        case .front: return "前"
        case .rear: return "后"
        case .left: return "左"
        case .right: return "右"
        case .cabin: return "内"
        }
    }

    var icon: String {
        switch self {
        case .front: return "arrow.up"
        case .rear: return "arrow.down"
        case .left: return "arrow.left"
        case .right: return "arrow.right"
        case .cabin: return "person.crop.circle"
        }
    }
}

struct CameraSource: Identifiable, Codable, Hashable {
    var id: String
    var name: String
    var position: CameraPosition
    var online: Bool
    var resolution: String
    var fps: Int
    var bitrateKbps: Int

    /// 相对路径，例如 `live/front.m3u8`。用相对路径而不是绝对 URL，
    /// 这样车机换了 IP 也不用重新拉一遍摄像头列表。
    var hlsPath: String?
    var snapshotPath: String?

    var canPlay: Bool { online && (hlsPath?.isEmpty == false) }

    var bitrateText: String {
        if bitrateKbps >= 1000 {
            return String(format: "%.1f Mbps", Double(bitrateKbps) / 1000)
        }
        return "\(bitrateKbps) kbps"
    }
}

// MARK: - 存储

struct StorageInfo: Codable, Hashable {
    var totalBytes: Int64
    var usedBytes: Int64
    var freeBytes: Int64
    /// 循环录制能保留多少天
    var retentionDays: Int

    static let empty = StorageInfo(totalBytes: 0, usedBytes: 0, freeBytes: 0, retentionDays: 0)

    var usedRatio: Double {
        guard totalBytes > 0 else { return 0 }
        return min(1, max(0, Double(usedBytes) / Double(totalBytes)))
    }

    var usedText: String { Self.bytes(usedBytes) }
    var totalText: String { Self.bytes(totalBytes) }
    var freeText: String { Self.bytes(freeBytes) }

    static func bytes(_ value: Int64) -> String {
        if value <= 0 { return "0 MB" }
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useGB, .useMB]
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false
        formatter.includesUnit = true
        return formatter.string(fromByteCount: value)
    }
}

// MARK: - 车机快照（GET /api/v1/info）

struct DeviceSnapshot: Codable, Hashable {
    var deviceName: String
    var model: String
    var firmware: String
    var uptimeSec: Int
    var parkingMode: Bool
    var recording: Bool
    var storage: StorageInfo
    var cameras: [CameraSource]
    var unreadEvents: Int

    var uptimeText: String {
        let hours = uptimeSec / 3600
        let minutes = (uptimeSec % 3600) / 60
        if hours > 0 { return "\(hours) 小时 \(minutes) 分" }
        return "\(minutes) 分钟"
    }

    static let demo = DeviceSnapshot(
        deviceName: "车载哨兵",
        model: "Android 车机 · 4 路环视",
        firmware: "1.0.0",
        uptimeSec: 7_320,
        parkingMode: true,
        recording: true,
        storage: StorageInfo(
            totalBytes: 128 * 1_000_000_000,
            usedBytes: 84 * 1_000_000_000,
            freeBytes: 44 * 1_000_000_000,
            retentionDays: 3
        ),
        cameras: CameraSource.demoSet,
        unreadEvents: 3
    )
}

// MARK: - 录像片段

enum ClipSource: String, Codable {
    case loop       // 循环录制
    case event      // 事件触发
    case manual     // 手动录制

    var label: String {
        switch self {
        case .loop: return "循环"
        case .event: return "事件"
        case .manual: return "手动"
        }
    }
}

struct RecordingClip: Identifiable, Codable, Hashable {
    var id: String
    var cameraId: String
    var startAt: Int
    var durationSec: Int
    var sizeBytes: Int64
    var source: ClipSource
    var locked: Bool
    var hlsPath: String?
    var thumbPath: String?

    var startDate: Date { Date(timeIntervalSince1970: TimeInterval(startAt)) }
    var endDate: Date { Date(timeIntervalSince1970: TimeInterval(startAt + durationSec)) }

    var timeRangeText: String {
        "\(Clock.hhmmss(startAt)) – \(Clock.hhmmss(startAt + durationSec))"
    }

    var durationText: String {
        let minutes = durationSec / 60
        let seconds = durationSec % 60
        if minutes > 0 { return "\(minutes) 分 \(seconds) 秒" }
        return "\(seconds) 秒"
    }

    var sizeText: String { StorageInfo.bytes(sizeBytes) }
}

// MARK: - 哨兵事件

enum EventKind: String, Codable, CaseIterable {
    case collision   // 碰撞
    case motion      // 移动侦测
    case intrusion   // 有人接近
    case parking     // 驻车被撞
    case system      // 系统

    var label: String {
        switch self {
        case .collision: return "碰撞"
        case .motion: return "移动侦测"
        case .intrusion: return "有人接近"
        case .parking: return "驻车异动"
        case .system: return "系统"
        }
    }

    var icon: String {
        switch self {
        case .collision: return "bolt.car"
        case .motion: return "figure.walk"
        case .intrusion: return "eye"
        case .parking: return "parkingsign"
        case .system: return "gearshape"
        }
    }

    /// 默认告警级别：1 信息 2 成功 3 警告 4 严重
    var defaultLevel: Int {
        switch self {
        case .collision: return 4
        case .parking: return 4
        case .intrusion: return 3
        case .motion: return 3
        case .system: return 1
        }
    }
}

struct SentinelEvent: Identifiable, Codable, Hashable {
    var id: String
    var kind: EventKind
    var level: Int
    var at: Int
    var cameraId: String
    var note: String
    var clipId: String?
    var isRead: Bool

    var date: Date { Date(timeIntervalSince1970: TimeInterval(at)) }
}

// MARK: - 连接

/// 车机地址。两种连法：
///
///   · `.lan`    —— 局域网直连（手机和车机同一个 WiFi / 车机热点）
///   · `.remote` —— 走中继（车机主动外连中继，手机在任何网络下都能看）
///
/// 两种模式在协议层完全一样，都是「HTTP + 相对路径」，区别只在 baseURL：
/// 局域网的 baseURL 是 `http://192.168.43.1:8710`，
/// 远程的 baseURL 是 `https://relay.example.com/v1/{房间号}/{密钥}`。
/// 播放器（AVPlayer）拿到的永远是一个普通 http(s) 地址，所以播放逻辑零改动。
struct DeviceEndpoint: Codable, Hashable, Identifiable {

    enum Kind: String, Codable, Hashable {
        case lan
        case remote

        var label: String {
            self == .remote ? "远程" : "局域网"
        }
    }

    var kind: Kind
    var scheme: String
    var host: String
    var port: Int
    /// 中继模式的路径前缀，例如 `v1/abc123/def456`（不带首尾斜杠）
    var pathPrefix: String
    var name: String

    init(
        kind: Kind = .lan,
        scheme: String = "http",
        host: String,
        port: Int,
        pathPrefix: String = "",
        name: String
    ) {
        self.kind = kind
        self.scheme = scheme
        self.host = host
        self.port = port
        self.pathPrefix = pathPrefix
        self.name = name
    }

    // 旧版本存下来的 JSON 没有 kind / scheme / pathPrefix，
    // 直接合成解码会失败 → 用户「升级后连接记录没了」。
    // 这里全部用 decodeIfPresent + 默认值兜住。
    private enum CodingKeys: String, CodingKey {
        case kind, scheme, host, port, pathPrefix, name
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        kind = try container.decodeIfPresent(Kind.self, forKey: .kind) ?? .lan
        scheme = try container.decodeIfPresent(String.self, forKey: .scheme) ?? "http"
        host = try container.decode(String.self, forKey: .host)
        port = try container.decode(Int.self, forKey: .port)
        pathPrefix = try container.decodeIfPresent(String.self, forKey: .pathPrefix) ?? ""
        name = try container.decode(String.self, forKey: .name)
    }

    var isRemote: Bool { kind == .remote }

    var id: String { baseURLString }

    /// 拼出完整的 base URL（不带末尾斜杠）。
    /// 80/443 端口不写进 URL —— 写上去会让某些 CDN / 中继的证书校验对不上。
    var baseURLString: String {
        var result = "\(scheme)://\(host)"
        let isDefaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        if port > 0 && !isDefaultPort {
            result += ":\(port)"
        }
        let trimmed = pathPrefix.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !trimmed.isEmpty {
            result += "/" + trimmed
        }
        return result
    }

    var baseURL: URL? { URL(string: baseURLString) }

    var display: String {
        isRemote ? "远程 · \(name)" : "\(host):\(port)"
    }

    static let defaultPort = 8710

    /// 只在没有历史记录时用得上：大部分国产车机的热点网关是 192.168.43.1。
    static let suggested = DeviceEndpoint(host: "192.168.43.1", port: defaultPort, name: "车载热点")

    /// 由「中继地址 + 房间号 + 密钥」造一个远程端点。
    /// 用户可能填 `https://relay.example.com`，也可能填 `relay.example.com` 或带路径的地址。
    static func remote(relayURL: String, room: String, key: String, name: String = "远程中继") -> DeviceEndpoint? {
        var text = relayURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }

        if !text.lowercased().hasPrefix("http://") && !text.lowercased().hasPrefix("https://") {
            text = "https://" + text
        }
        while text.hasSuffix("/") { text.removeLast() }
        guard let url = URL(string: text), let host = url.host else { return nil }

        let scheme = url.scheme?.lowercased() ?? "https"
        let port = url.port ?? (scheme == "https" ? 443 : 80)

        // 用户可能自己把 /v1/xx/yy 写进去了，避免重复
        var prefix = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let cleanRoom = room.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanKey = key.trimmingCharacters(in: .whitespacesAndNewlines)

        if prefix.isEmpty {
            prefix = "v1/\(cleanRoom)/\(cleanKey)"
        }

        return DeviceEndpoint(
            kind: .remote,
            scheme: scheme,
            host: host,
            port: port,
            pathPrefix: prefix,
            name: name
        )
    }
}

enum LinkState: Equatable {
    case mock
    case connecting
    case online
    case failed(String)

    var title: String {
        switch self {
        case .mock: return "演示数据"
        case .connecting: return "连接中"
        case .online: return "已连接"
        case .failed: return "未连接"
        }
    }

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }

    var isMock: Bool {
        if case .mock = self { return true }
        return false
    }
}

// MARK: - 本地设置

struct SentrySettings: Equatable, Codable {
    enum Sensitivity: String, CaseIterable, Codable {
        case low, medium, high

        var label: String {
            switch self {
            case .low: return "低"
            case .medium: return "中"
            case .high: return "高"
            }
        }
    }

    enum StreamQuality: String, CaseIterable, Codable {
        case smooth, balanced, sharp

        var label: String {
            switch self {
            case .smooth: return "流畅"
            case .balanced: return "均衡"
            case .sharp: return "清晰"
            }
        }

        var detail: String {
            switch self {
            case .smooth: return "720p · 省流量"
            case .balanced: return "1080p · 推荐"
            case .sharp: return "1080p · 高码率"
            }
        }
    }

    var parkingMode: Bool = true
    var loopOverwrite: Bool = true
    var autoReconnect: Bool = true
    var sensitivity: Sensitivity = .medium
    var streamQuality: StreamQuality = .balanced
}

// MARK: - 时间格式

/// 全局统一的轻量时间格式。DateFormatter 建一次复用，别在循环里 new。
enum Clock {

    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = format
        return f
    }

    private static let hms = formatter("HH:mm:ss")
    private static let hm = formatter("HH:mm")
    private static let mdhm = formatter("MM-dd HH:mm")
    private static let mdFmt = formatter("MM月dd日")
    private static let weekdayFmt = formatter("EEE")

    static func hhmmss(_ epoch: Int) -> String {
        hms.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
    }

    static func hhmm(_ epoch: Int) -> String {
        hm.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
    }

    static func monthDayHourMinute(_ epoch: Int) -> String {
        mdhm.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
    }

    static func monthDay(_ date: Date) -> String {
        mdFmt.string(from: date)
    }

    static func weekday(_ date: Date) -> String {
        weekdayFmt.string(from: date)
    }

    static func hourLabel(_ epoch: Int) -> String {
        let calendar = Calendar.current
        let hour = calendar.component(.hour, from: Date(timeIntervalSince1970: TimeInterval(epoch)))
        return String(format: "%02d:00", hour)
    }

    /// 「3 分钟前」这种相对时间，超过一天就退回日期。
    static func relative(_ epoch: Int) -> String {
        let now = Int(Date().timeIntervalSince1970)
        let delta = now - epoch
        if delta < 60 { return "刚刚" }
        if delta < 3_600 { return "\(delta / 60) 分钟前" }
        if delta < 86_400 { return "\(delta / 3_600) 小时前" }
        if delta < 172_800 { return "昨天 \(hhmm(epoch))" }
        return monthDayHourMinute(epoch)
    }

    static func dayKey(_ epoch: Int) -> String {
        let f = formatter("yyyy-MM-dd")
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(epoch)))
    }
}
