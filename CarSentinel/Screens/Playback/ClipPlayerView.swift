import SwiftUI

// =============================================================================
// 片段播放
//
// 有真流就走 AVPlayer，没连车机就走合成画面 + 可拖动的时间轴。
// 两条路的控件长一样，用户不用知道自己在看哪一种。
// =============================================================================

struct ClipPlayerView: View {

    @EnvironmentObject private var store: SentryStore
    @Environment(\.dismiss) private var dismiss

    let clip: RecordingClip

    @StateObject private var player = StreamPlayer()
    @State private var isPlaying = true
    @State private var progress: Double = 0
    @State private var scrubbing = false

    private var realURL: URL? {
        guard store.link.isOnline else { return nil }
        return store.mediaURL(clip.hlsPath)
    }

    private var isSimulated: Bool { realURL == nil }

    private var positionSec: Int {
        clip.startAt + Int(Double(clip.durationSec) * progress)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar

                Spacer(minLength: 0)

                Rectangle()
                    .fill(Color(hex: 0x0B0B12))
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .overlay(videoContent)
                    .clipped()

                Spacer(minLength: 0)

                transport
            }
        }
        .statusBarHidden(true)
        .onAppear(perform: startPlayback)
        .onDisappear { player.release() }
        .task(id: isPlaying) {
            guard isSimulated, isPlaying else { return }
            while !Task.isCancelled {
                try? await Task<Never, Never>.sleep(nanoseconds: 400_000_000)
                if Task.isCancelled { return }
                guard !scrubbing else { continue }
                let step = 0.4 / Double(max(1, clip.durationSec))
                progress = min(1, progress + step)
                if progress >= 1 {
                    isPlaying = false
                    return
                }
            }
        }
    }

    // MARK: - 画面

    @ViewBuilder
    private var videoContent: some View {
        if realURL != nil {
            PlayerSurface(player: player.player)
        } else {
            ZStack {
                SignalPlaceholder(
                    position: store.camera(for: clip.cameraId)?.position ?? .front,
                    online: true
                )

                // 中央播放/暂停（暂停时才显示，播放时别挡画面）
                if !isPlaying {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 54))
                        .foregroundStyle(Color.white.opacity(0.82))
                }

                VStack {
                    HStack {
                        HStack(spacing: 4) {
                            Text(store.cameraName(for: clip.cameraId))
                            Text(Clock.hhmmss(positionSec))
                        }
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.white.opacity(0.85))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.black.opacity(0.4)))
                        Spacer()
                    }
                    Spacer()
                }
                .padding(10)
            }
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: 10) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Color.white.opacity(0.14)))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 1) {
                Text("\(store.cameraName(for: clip.cameraId)) · \(clip.timeRangeText)")
                    .font(AuroraFont.titleSmall)
                    .foregroundStyle(Color.white)
                Text("\(clip.sizeText) · \(clip.source.label)录像")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.5))
            }

            Spacer(minLength: 0)

            if clip.locked {
                HStack(spacing: 3) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 9, weight: .semibold))
                    Text("已锁定")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(Aurora.warning)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Capsule().fill(Aurora.warning.tint(0.22)))
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
    }

    // MARK: - 控件

    private var transport: some View {
        VStack(spacing: 12) {
            // 时间轴
            HStack(spacing: 10) {
                Text(Clock.hhmmss(positionSec))
                    .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.7))
                    .frame(width: 56, alignment: .leading)

                Slider(
                    value: $progress,
                    in: 0...1,
                    onEditingChanged: { editing in
                        scrubbing = editing
                        if isSimulated && !editing && progress < 1 {
                            isPlaying = true
                        }
                    }
                )
                .tint(Aurora.brand)

                Text(Clock.hhmmss(clip.startAt + clip.durationSec))
                    .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.7))
                    .frame(width: 56, alignment: .trailing)
            }
            .opacity(isSimulated ? 1 : 0.35)
            .disabled(!isSimulated)

            HStack(spacing: 8) {
                Button {
                    togglePlay()
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 13, weight: .semibold))
                        Text(isPlaying ? "暂停" : "播放")
                            .font(AuroraFont.labelMedium)
                    }
                    .foregroundStyle(Color.black)
                    .frame(width: 92, height: 40)
                    .background(Capsule().fill(Color.white))
                    .contentShape(Capsule())
                }
                .buttonStyle(PressScaleStyle(scale: 0.96))

                Spacer(minLength: 0)

                Text(isSimulated ? "演示片段（未连接车机）" : "车机录像 · HLS")
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Color.white.opacity(0.42))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Color(hex: 0x1C1C24))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
    }

    // MARK: - 行为

    private func startPlayback() {
        if let url = realURL {
            player.load(url)
        } else {
            progress = 0
            isPlaying = true
        }
    }

    private func togglePlay() {
        isPlaying.toggle()
        if realURL != nil {
            if isPlaying { player.play() } else { player.pause() }
        }
    }
}
