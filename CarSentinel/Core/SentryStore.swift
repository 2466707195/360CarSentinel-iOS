import Combine
import Foundation

// =============================================================================
// 状态中枢
//
// 一个 ObservableObject 管完所有页面要的数据：连接状态、摄像头、录像、事件、设置。
// 页面只读它、只调它的方法，不自己发网络请求。
//
// 三种运行态：
//   · .mock      —— 没连车机，用 MockLibrary 的演示数据把界面填满
//   · .online    —— 连上了，每 3 秒轮询 info，数据全部来自车机
//   · .failed    —— 连过但断了，保留上一次的数据，顶部提示可重试
// =============================================================================

struct ClipGroup: Identifiable {
    let id: String
    let title: String
    let clips: [RecordingClip]
}

struct DaySummary {
    var clipCount: Int
    var durationSec: Int
    var sizeBytes: Int64

    var durationText: String {
        let hours = durationSec / 3_600
        let minutes = (durationSec % 3_600) / 60
        if hours > 0 { return "\(hours) 小时 \(minutes) 分" }
        return "\(minutes) 分钟"
    }

    var sizeText: String { StorageInfo.bytes(sizeBytes) }
}

@MainActor
final class SentryStore: ObservableObject {

    // MARK: - 连接

    @Published private(set) var link: LinkState = .mock
    @Published private(set) var endpoint: DeviceEndpoint?

    // MARK: - 数据

    @Published private(set) var device: DeviceSnapshot = .demo
    @Published private(set) var cameras: [CameraSource] = CameraSource.demoSet
    @Published private(set) var recordings: [RecordingClip] = []
    @Published private(set) var events: [SentinelEvent] = []

    // MARK: - 界面状态

    @Published var selectedDay: Date = Calendar.current.startOfDay(for: Date())
    @Published var cameraFilter: String?
    @Published var eventFilter: EventKind?
    @Published private(set) var isRefreshing = false
    @Published var banner: String?

    /// 演示模式下的实时总码率（真机模式下由 device.cameras 汇总）
    @Published private(set) var liveBitrateKbps: Int = 0

    // MARK: - 设置（改了就落盘）

    @Published var settings: SentrySettings = SentrySettings() {
        didSet { persistSettings() }
    }

    // MARK: - 内部

    private var api: SentryAPI?
    private var pollTask: Task<Void, Never>?
    private var tick = 0

    private let defaults = UserDefaults.standard
    private enum Keys {
        static let endpoint = "carsentinel.endpoint"
        static let settings = "carsentinel.settings"
    }

    // MARK: - 生命周期

    /// App 启动时调一次：有历史地址就试着连，连不上自动落到演示数据。
    func bootstrap() async {
        loadSettings()

        if let saved = loadEndpoint() {
            endpoint = saved
            await connect(to: saved, silent: true)
            if link.isOnline { return }
        }

        enterMockMode()
    }

    func enterMockMode() {
        stopPolling()
        api = nil
        link = .mock
        device = .demo
        cameras = CameraSource.demoSet
        recordings = MockLibrary.clips()
        events = MockLibrary.events()
        liveBitrateKbps = cameras.reduce(0) { $0 + $1.bitrateKbps }
        settings.parkingMode = device.parkingMode
    }

    // MARK: - 连接 / 断开

    func connect(to newEndpoint: DeviceEndpoint, silent: Bool = false) async {
        stopPolling()
        link = .connecting

        let client = SentryAPI(endpoint: newEndpoint)
        do {
            let snapshot = try await client.info()
            api = client
            endpoint = newEndpoint
            device = snapshot
            cameras = snapshot.cameras
            liveBitrateKbps = snapshot.cameras.reduce(0) { $0 + $1.bitrateKbps }
            link = .online
            persistEndpoint(newEndpoint)
            if !silent {
                banner = "已连接 \(snapshot.deviceName)"
            }
            await reloadLibrary()
            if settings.autoReconnect { startPolling() }
        } catch {
            let reason = (error as? LocalizedError)?.errorDescription ?? "连不上车机"
            if silent {
                // 静默重连失败不打扰用户，直接落回演示数据
                enterMockMode()
            } else {
                link = .failed(reason)
                banner = reason
            }
        }
    }

    func disconnect() {
        stopPolling()
        api = nil
        endpoint = nil
        defaults.removeObject(forKey: Keys.endpoint)
        enterMockMode()
        banner = "已断开，切回演示数据"
    }

    // MARK: - 刷新

    func refresh(silent: Bool = false) async {
        tick += 1

        guard link.isOnline, let api else {
            // 演示模式：只让码率跳动，制造"在跑"的感觉
            liveBitrateKbps = MockLibrary.jitterRate(
                cameras.reduce(0) { $0 + $1.bitrateKbps },
                tick: tick
            )
            return
        }

        if !silent { isRefreshing = true }
        defer { if !silent { isRefreshing = false } }

        do {
            let snapshot = try await api.info()
            device = snapshot
            cameras = snapshot.cameras
            liveBitrateKbps = snapshot.cameras.reduce(0) { $0 + $1.bitrateKbps }
            if case .failed = link {
                link = .online
                banner = "已重新连上车机"
            } else {
                link = .online
            }
        } catch {
            link = .failed("连接中断")
            stopPolling()
            return
        }

        await reloadLibrary()
    }

    func reloadLibrary() async {
        guard link.isOnline, let api else { return }

        let range = selectedDayRange()
        if let clips = try? await api.clips(from: range.from, to: range.to, cameraId: cameraFilter) {
            recordings = clips
        }
        if let items = try? await api.events(since: nil) {
            events = items
        }
    }

    // MARK: - 控制指令

    func toggleRecording() {
        let turningOn = !device.recording
        device.recording = turningOn

        guard link.isOnline, let api else {
            banner = turningOn ? "已开始录制（演示）" : "已停止录制（演示）"
            return
        }
        Task {
            do {
                try await api.control(turningOn ? .startRecording : .stopRecording)
                banner = turningOn ? "车机已开始录制" : "车机已停止录制"
            } catch {
                device.recording = !turningOn
                banner = "指令发送失败，请检查连接"
            }
        }
    }

    func setParkingMode(_ enabled: Bool) {
        settings.parkingMode = enabled
        device.parkingMode = enabled

        guard link.isOnline, let api else {
            banner = enabled ? "驻车监控已开启（演示）" : "驻车监控已关闭（演示）"
            return
        }
        Task {
            do {
                try await api.control(.setParkingMode, enabled: enabled)
                banner = enabled ? "车机已开启驻车监控" : "车机已关闭驻车监控"
            } catch {
                settings.parkingMode = !enabled
                device.parkingMode = !enabled
                banner = "设置未生效，请检查连接"
            }
        }
    }

    func requestSnapshot(camera: CameraSource) {
        guard link.isOnline, let api else {
            banner = "已保存 \(camera.name) 截图（演示）"
            return
        }
        Task {
            do {
                try await api.control(.snapshot, cameraId: camera.id)
                banner = "已保存 \(camera.name) 截图到车机"
            } catch {
                banner = "截图失败"
            }
        }
    }

    // MARK: - 事件

    var unreadCount: Int {
        events.filter { !$0.isRead }.count
    }

    func markRead(_ event: SentinelEvent) {
        guard let index = events.firstIndex(where: { $0.id == event.id }) else { return }
        events[index].isRead = true
    }

    func markAllRead() {
        for index in events.indices {
            events[index].isRead = true
        }
        device.unreadEvents = 0
    }

    // MARK: - 派生数据

    var selectedDayClips: [RecordingClip] {
        let calendar = Calendar.current
        let day = selectedDay
        return recordings.filter {
            calendar.isDate($0.startDate, inSameDayAs: day)
                && (cameraFilter == nil || $0.cameraId == cameraFilter)
        }
    }

    var groupedClips: [ClipGroup] {
        var buckets: [Int: [RecordingClip]] = [:]
        for clip in selectedDayClips {
            let hour = Calendar.current.component(.hour, from: clip.startDate)
            buckets[hour, default: []].append(clip)
        }
        return buckets.keys.sorted(by: >).map { hour in
            ClipGroup(
                id: String(hour),
                title: String(format: "%02d:00", hour),
                clips: (buckets[hour] ?? []).sorted { $0.startAt > $1.startAt }
            )
        }
    }

    var daySummary: DaySummary {
        let clips = selectedDayClips
        return DaySummary(
            clipCount: clips.count,
            durationSec: clips.reduce(0) { $0 + $1.durationSec },
            sizeBytes: clips.reduce(0) { $0 + $1.sizeBytes }
        )
    }

    var filteredEvents: [SentinelEvent] {
        guard let eventFilter else { return events }
        return events.filter { $0.kind == eventFilter }
    }

    /// 回放页的日期选项卡（今天往前 7 天）
    var dayOptions: [Date] {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        return (0..<7).compactMap { calendar.date(byAdding: .day, value: -$0, to: today) }
    }

    func camera(for id: String) -> CameraSource? {
        cameras.first { $0.id == id }
    }

    func cameraName(for id: String) -> String {
        camera(for: id)?.name ?? id
    }

    func clip(for id: String) -> RecordingClip? {
        recordings.first { $0.id == id }
    }

    /// 相对路径 → 绝对 URL。播放器只认绝对地址，车机换 IP 时这里自动跟着变。
    func mediaURL(_ path: String?) -> URL? {
        guard let endpoint else { return nil }
        return SentryAPI(endpoint: endpoint).absoluteURL(path)
    }

    // MARK: - 轮询

    private func startPolling() {
        stopPolling()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task<Never, Never>.sleep(nanoseconds: 3_000_000_000)
                if Task.isCancelled { return }
                guard let self else { return }
                await self.refresh(silent: true)
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: - 持久化

    private func selectedDayRange() -> (from: Int, to: Int) {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: selectedDay)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start
        return (
            Int(start.timeIntervalSince1970),
            Int(end.timeIntervalSince1970)
        )
    }

    private func persistEndpoint(_ value: DeviceEndpoint?) {
        if let value, let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: Keys.endpoint)
        } else {
            defaults.removeObject(forKey: Keys.endpoint)
        }
    }

    private func loadEndpoint() -> DeviceEndpoint? {
        guard let data = defaults.data(forKey: Keys.endpoint) else { return nil }
        return try? JSONDecoder().decode(DeviceEndpoint.self, from: data)
    }

    private func persistSettings() {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: Keys.settings)
    }

    private func loadSettings() {
        guard
            let data = defaults.data(forKey: Keys.settings),
            let saved = try? JSONDecoder().decode(SentrySettings.self, from: data)
        else { return }
        settings = saved
    }
}
