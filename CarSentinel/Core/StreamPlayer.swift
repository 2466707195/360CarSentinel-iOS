import AVFoundation
import Combine
import SwiftUI

// =============================================================================
// HLS 播放器封装
//
// 为什么用 HLS 而不是 WebRTC：
//   · AVPlayer 原生支持，零第三方依赖 → 云端 CI 不会因为 pod 下载失败而挂
//   · 车机端就是把 MediaCodec 出来的 H.264 切片写进 m3u8，不需要额外服务
//   · 1 秒分片 + 部分分片可以把延迟压到 1~2 秒，监看够用
//
// 想换 WebRTC 的话，只要替换这个文件里的播放实现，上层页面不用动。
// =============================================================================

final class StreamPlayer: ObservableObject {

    let player = AVPlayer()

    @Published private(set) var isReady = false
    @Published private(set) var hasFailed = false
    @Published private(set) var stateText = "连接中"
    @Published var isMuted = true {
        didSet { player.isMuted = isMuted }
    }

    private var currentURL: URL?
    private var cancellables: Set<AnyCancellable> = []

    init() {
        player.automaticallyWaitsToMinimizeStalling = false
        player.isMuted = true
    }

    func load(_ url: URL) {
        if currentURL == url {
            player.play()
            return
        }

        release()

        currentURL = url
        hasFailed = false
        isReady = false
        stateText = "连接中"

        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 1

        item.publisher(for: \.status, options: [.initial, .new])
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                guard let self else { return }
                switch status {
                case .readyToPlay:
                    self.isReady = true
                    self.stateText = "直播中"
                case .failed:
                    self.hasFailed = true
                    self.stateText = "拉流失败"
                default:
                    self.stateText = "缓冲中"
                }
            }
            .store(in: &cancellables)

        player.replaceCurrentItem(with: item)
        player.play()
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    /// 彻底释放当前流，切页 / 关全屏时调，避免后台继续拉流耗电。
    func release() {
        cancellables.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        currentURL = nil
        isReady = false
        stateText = "连接中"
    }
}

// MARK: - 播放画面

/// 把 AVPlayer 贴到 UIView 上（AVPlayerLayer 走硬件解码，比 VideoPlayer 轻）。
struct PlayerSurface: UIViewRepresentable {

    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.backgroundColor = .black
        view.attach(player, gravity: gravity)
        return view
    }

    func updateUIView(_ uiView: PlayerHostView, context: Context) {
        uiView.attach(player, gravity: gravity)
    }

    static func dismantleUIView(_ uiView: PlayerHostView, coordinator: ()) {
        uiView.detach()
    }
}

final class PlayerHostView: UIView {

    override class var layerClass: AnyClass { AVPlayerLayer.self }

    private var playerLayer: AVPlayerLayer {
        // layerClass 已经指定成 AVPlayerLayer，这里的强转不可能失败
        layer as? AVPlayerLayer ?? AVPlayerLayer()
    }

    func attach(_ player: AVPlayer, gravity: AVLayerVideoGravity) {
        if playerLayer.player !== player {
            playerLayer.player = player
        }
        playerLayer.videoGravity = gravity
    }

    func detach() {
        playerLayer.player = nil
    }
}
