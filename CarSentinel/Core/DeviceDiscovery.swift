import Combine
import Foundation

// =============================================================================
// 局域网设备发现（Bonjour / mDNS）
//
// 车机端启动后广播 `_carsentry._tcp`，手机在同一 WiFi 下自动看到它，
// 不用让用户去车机设置里翻 IP。
//
// 实现说明：
//   · 直接用 NetService 的 `hostName`（形如 android-abc.local.）当主机名，
//     不解析 sockaddr —— iOS 原生能解析 .local，省掉一大段字节序代码。
//   · NetService.delegate 是 unowned 的，所以必须用数组强引用住，否则回调丢失。
// =============================================================================

final class DeviceDiscovery: NSObject, ObservableObject {

    @Published private(set) var found: [DeviceEndpoint] = []
    @Published private(set) var isBrowsing = false
    @Published private(set) var lastError: String?

    private var browser: NetServiceBrowser?
    private var pending: [NetService] = []

    func start() {
        guard !isBrowsing else { return }
        found.removeAll()
        lastError = nil

        let browser = NetServiceBrowser()
        browser.delegate = self
        browser.searchForServices(ofType: "_carsentry._tcp.", inDomain: "local.")
        self.browser = browser
        isBrowsing = true
    }

    func stop() {
        browser?.stop()
        browser = nil
        for service in pending { service.stop() }
        pending.removeAll()
        isBrowsing = false
    }

    /// 把用户手输的 `192.168.43.1` / `192.168.43.1:8710` / `http://x:8710/` 统一成 endpoint
    static func parseEndpoint(_ text: String) -> DeviceEndpoint? {
        var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }

        value = value.replacingOccurrences(of: "http://", with: "")
        value = value.replacingOccurrences(of: "https://", with: "")
        while value.hasSuffix("/") { value.removeLast() }

        var host = value
        var port = DeviceEndpoint.defaultPort

        if let colon = value.lastIndex(of: ":"), value.filter({ $0 == ":" }).count == 1 {
            let portPart = String(value[value.index(after: colon)...])
            if let parsed = Int(portPart), (1...65_535).contains(parsed) {
                host = String(value[..<colon])
                port = parsed
            }
        }

        guard !host.isEmpty else { return nil }
        return DeviceEndpoint(host: host, port: port, name: host)
    }
}

extension DeviceDiscovery: NetServiceBrowserDelegate {

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        service.delegate = self
        pending.append(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemove service: NetService,
        moreComing: Bool
    ) {
        pending.removeAll { $0.name == service.name }
        found.removeAll { $0.name == service.name }
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didNotSearch errorDict: [String: NSNumber]
    ) {
        isBrowsing = false
        lastError = "无法搜索局域网（可能是本地网络权限未开启）"
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        isBrowsing = false
    }
}

extension DeviceDiscovery: NetServiceDelegate {

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let raw = sender.hostName, !raw.isEmpty else { return }
        let clean = raw.hasSuffix(".") ? String(raw.dropLast()) : raw
        let port = sender.port > 0 ? sender.port : DeviceEndpoint.defaultPort

        let endpoint = DeviceEndpoint(host: clean, port: port, name: sender.name)
        if !found.contains(where: { $0.display == endpoint.display }) {
            found.append(endpoint)
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        pending.removeAll { $0 === sender }
    }
}
