import SwiftUI

// =============================================================================
// 单路全屏
//
// 黑色底 + 尽量大的画面 + 一层深色浮动控件。
// 控件层是**实色**（0x1C1C24），不是半透明 —— 半透明压在视频上会糊成一片。
// 轮播：每 5 秒换下一路，用 .task(id:) 驱动，不用 Timer，切页自动取消。
// =============================================================================

struct CameraFullScreenView: View {

    @EnvironmentObject private var store: SentryStore
    @Environment(\.dismiss) private var dismiss

    @StateObject private var player = StreamPlayer()

    @State private var cameraID: String
    @State private var carousel: Bool
    @State private var showChrome = true

    init(initialCamera: CameraSource, startCarousel: Bool) {
        _cameraID = State(initialValue: initialCamera.id)
        _carousel = State(initialValue: startCarousel)
    }

    private var camera: CameraSource? {
        store.camera(for: cameraID) ?? store.cameras.first
    }

    private var streamURL: URL? {
        guard store.link.isOnline, let camera else { return nil }
        return store.mediaURL(camera.hlsPath)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let url = streamURL {
                PlayerSurface(player: player.player)
                    .ignoresSafeArea()
            } else if let camera {
                SignalPlaceholder(position: camera.position, online: camera.online)
                    .ignoresSafeArea()
            }

            VStack(spacing: 0) {
                topChrome
                Spacer(minLength: 0)
                if showChrome {
                    bottomChrome
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .statusBarHidden(true)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.2)) { showChrome.toggle() }
        }
        .onAppear { loadStream() }
        .onChange(of: cameraID) { _ in loadStream() }
        .onDisappear { player.release() }
        .task(id: carousel) {
            guard carousel, store.cameras.count > 1 else { return }
            while !Task.isCancelled {
                try? await Task<Never, Never>.sleep(nanoseconds: 5_000_000_000)
                if Task.isCancelled { return }
                advance()
            }
        }
    }

    // MARK: - 顶栏

    private var topChrome: some View {
        HStack(spacing: 10) {
            circleButton("xmark") { dismiss() }

            VStack(alignment: .leading, spacing: 1) {
                Text(camera?.name ?? "未知机位")
                    .font(AuroraFont.titleSmall)
                    .foregroundStyle(Color.white)
                Text("\(camera?.resolution ?? "--") · \(camera?.fps ?? 0) fps")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.white.opacity(0.55))
            }

            Spacer(minLength: 0)

            if store.link.isMock {
                darkPill(text: "演示信号", color: Aurora.warning)
            } else if streamURL == nil {
                darkPill(text: "无信号", color: Aurora.muted)
            } else {
                HStack(spacing: 4) {
                    RecDot(size: 5)
                    Text("LIVE")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.white)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Capsule().fill(Color.black.opacity(0.45)))
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 6)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0.55), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea(edges: .top)
        )
        .opacity(showChrome ? 1 : 0)
    }

    // MARK: - 底部控件

    private var bottomChrome: some View {
        VStack(spacing: 10) {
            // 机位切换
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(store.cameras) { item in
                        Button {
                            carousel = false
                            cameraID = item.id
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: item.position.icon)
                                    .font(.system(size: 10, weight: .semibold))
                                Text(item.name)
                                    .font(AuroraFont.labelMedium)
                            }
                            .foregroundStyle(item.id == cameraID ? Color.black.opacity(0.85) : Color.white)
                            .padding(.horizontal, 11)
                            .padding(.vertical, 7)
                            .auroraGlassCapsule(
                                tint: item.id == cameraID ? Color.white : Color.black,
                                tintOpacity: item.id == cameraID ? 0.62 : 0.22
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 2)
            }

            HStack(spacing: 8) {
                chromeAction(
                    icon: carousel ? "rectangle.on.rectangle.fill" : "rectangle.on.rectangle",
                    title: "轮播",
                    active: carousel
                ) {
                    carousel.toggle()
                }

                chromeAction(
                    icon: "camera",
                    title: "截图",
                    active: false
                ) {
                    if let camera { store.requestSnapshot(camera: camera) }
                }

                chromeAction(
                    icon: player.isMuted ? "speaker.slash" : "speaker.wave.2",
                    title: "声音",
                    active: !player.isMuted
                ) {
                    player.isMuted.toggle()
                }

                Spacer(minLength: 0)

                Text(store.link.isMock ? "演示" : player.stateText)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Color.white.opacity(0.5))
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Color(hex: 0x1C1C24))
                .shadow(color: Color.black.opacity(0.4), radius: 12, x: 0, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .stroke(Color.white.opacity(0.08), lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
        // 浮层整块吞点击，避免点到背景把控件收起来
        .contentShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .onTapGesture { }
    }

    // MARK: - 小零件

    private func circleButton(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.white)
                .auroraGlassCircle(diameter: 34, tint: Color.black, tintOpacity: 0.26)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }

    private func darkPill(text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(Capsule().fill(color.tint(0.22)))
    }

    private func chromeAction(
        icon: String,
        title: String,
        active: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                Text(title)
                    .font(.system(size: 10, weight: .medium))
            }
            .foregroundStyle(active ? Aurora.brand : Color.white.opacity(0.9))
            .frame(width: 54, height: 44)
            .auroraGlass(
                cornerRadius: 14,
                tint: active ? Color.white : Color.black,
                tintOpacity: active ? 0.60 : 0.22,
                shadowRadius: 6
            )
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 行为

    private func loadStream() {
        guard let url = streamURL else {
            player.release()
            return
        }
        player.load(url)
    }

    private func advance() {
        let list = store.cameras
        guard !list.isEmpty else { return }
        let currentIndex = list.firstIndex { $0.id == cameraID } ?? 0
        let next = (currentIndex + 1) % list.count
        withAnimation(.easeInOut(duration: 0.25)) {
            cameraID = list[next].id
        }
    }
}
