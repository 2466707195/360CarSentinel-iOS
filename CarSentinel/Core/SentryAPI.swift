import Foundation

// =============================================================================
// 车机端 REST 客户端
//
// 全部走局域网 HTTP，没有云端。协议见 docs/PROTOCOL.md。
// 超时压得很短（4 秒）：车载 WiFi 断流时用户不能盯着转圈 30 秒。
// =============================================================================

enum SentryError: LocalizedError {
    case badURL
    case http(Int)
    case decode(String)
    case unreachable

    var errorDescription: String? {
        switch self {
        case .badURL:
            return "地址无效"
        case .http(let code):
            return "车机返回 \(code)"
        case .decode(let detail):
            return "数据格式不匹配：\(detail)"
        case .unreachable:
            return "连不上车机"
        }
    }
}

enum ControlAction: String {
    case startRecording = "start_record"
    case stopRecording = "stop_record"
    case snapshot
    case setParkingMode = "set_parking_mode"
}

struct SentryAPI {

    let endpoint: DeviceEndpoint

    private let session: URLSession
    private let decoder: JSONDecoder

    init(endpoint: DeviceEndpoint, session: URLSession? = nil) {
        self.endpoint = endpoint
        self.session = session ?? SentryAPI.makeSession()
        self.decoder = JSONDecoder()
    }

    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 6
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }

    // MARK: - 端点

    /// 把相对路径拼成绝对 URL。播放器（AVPlayer）要的是绝对地址。
    func absoluteURL(_ path: String?) -> URL? {
        guard let path, !path.isEmpty else { return nil }
        if path.lowercased().hasPrefix("http://") || path.lowercased().hasPrefix("https://") {
            return URL(string: path)
        }
        let trimmed = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return URL(string: "http://\(endpoint.host):\(endpoint.port)/\(trimmed)")
    }

    // MARK: - 接口

    func info() async throws -> DeviceSnapshot {
        try await get("api/v1/info")
    }

    func clips(from: Int, to: Int, cameraId: String?, limit: Int = 300) async throws -> [RecordingClip] {
        var query = "?from=\(from)&to=\(to)&limit=\(limit)"
        if let cameraId, !cameraId.isEmpty {
            query += "&camera=\(cameraId)"
        }
        let envelope: ClipEnvelope = try await get("api/v1/recordings\(query)")
        return envelope.clips
    }

    func events(since: Int?) async throws -> [SentinelEvent] {
        var path = "api/v1/events"
        if let since {
            path += "?since=\(since)"
        }
        let envelope: EventEnvelope = try await get(path)
        return envelope.events
    }

    func control(_ action: ControlAction, cameraId: String? = nil, enabled: Bool? = nil) async throws {
        var body: [String: Any] = ["action": action.rawValue]
        if let cameraId { body["cameraId"] = cameraId }
        if let enabled { body["enabled"] = enabled }
        _ = try await post("api/v1/control", body: body)
    }

    /// 轻量探活：只取 info 的状态码，不解析 body。
    func probe() async -> Bool {
        guard let url = URL(string: "http://\(endpoint.host):\(endpoint.port)/api/v1/info") else {
            return false
        }
        do {
            let (_, response) = try await session.data(from: url)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200..<300).contains(http.statusCode)
        } catch {
            return false
        }
    }

    // MARK: - 底层

    private func get<T: Decodable>(_ path: String) async throws -> T {
        guard let url = URL(string: "http://\(endpoint.host):\(endpoint.port)/\(path)") else {
            throw SentryError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else { throw SentryError.unreachable }
        guard (200..<300).contains(http.statusCode) else { throw SentryError.http(http.statusCode) }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw SentryError.decode(String(describing: error))
        }
    }

    @discardableResult
    private func post(_ path: String, body: [String: Any]) async throws -> Data {
        guard let url = URL(string: "http://\(endpoint.host):\(endpoint.port)/\(path)") else {
            throw SentryError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        } catch {
            throw SentryError.badURL
        }

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else { throw SentryError.unreachable }
        guard (200..<300).contains(http.statusCode) else { throw SentryError.http(http.statusCode) }
        return data
    }

    private func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch {
            throw SentryError.unreachable
        }
    }
}

// MARK: - 响应外壳

private struct ClipEnvelope: Decodable {
    var clips: [RecordingClip]
}

private struct EventEnvelope: Decodable {
    var events: [SentinelEvent]
}
