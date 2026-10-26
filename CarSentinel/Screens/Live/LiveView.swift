import SwiftUI

// =============================================================================
// 实时页
//
// 一屏看完四路：状态条（连接 / 码率 / 存储）→ 2×2 宫格 → 录制开关。
// 点任意一格进全屏，全屏里可以左右切机位、开轮播。
// =============================================================================

struct LiveView: View {

    @EnvironmentObject private var store: SentryStore
    @Environment(\.colorScheme) private var scheme

    @State private var fullScreenCamera: CameraSource?
    @State private var carouselOnOpen = false

    private var onlineCount: Int {
        store.cameras.filter { $0.online }.count
    }

    private var totalBitrateText: String {
        let mbps = Double(store.liveBitrateKbps) / 1000.0
        return String(format: "%.1f", mbps)
    }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "实时画面", subtitle: subtitle) {
                HStack(spacing: 0) {
                    AuroraToolbarIconButton("arrow.clockwise", accessibilityLabel: "刷新") {
                        Task { await store.refresh() }
                    }
                    AuroraToolbarIconButton(
                        "rectangle.on.rectangle",
                        accessibilityLabel: "全屏轮播"
                    ) {
                        carouselOnOpen = true
                        fullScreenCamera = store.cameras.first
                    }
                }
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: AuroraMetrics.cardSpacing) {

                    statusCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                        .padding(.top, 4)

                    SectionHeader(
                        title: "全部机位",
                        detail: "\(onlineCount)/\(store.cameras.count) 在线"
                    )
                    .padding(.horizontal, AuroraMetrics.pagePadding)

                    cameraGrid
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    noticeCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    Spacer().frame(height: PageLayout.tabBarInset)
                }
            }
            .scrollIndicators(.hidden)
        }
        .fullScreenCover(item: $fullScreenCamera) { camera in
            CameraFullScreenView(
                initialCamera: camera,
                startCarousel: carouselOnOpen
            )
            .environmentObject(store)
            .onDisappear { carouselOnOpen = false }
        }
    }

    private var subtitle: String {
        switch store.link {
        case .online:
            return "\(store.device.deviceName) · \(store.device.model)"
        case .connecting:
            return "正在连接车机…"
        case .failed(let reason):
            return reason
        case .mock:
            return "未连接车机 · 当前为演示画面"
        }
    }

    // MARK: - 状态条

    private var statusCard: some View {
        SectionCard {
            HStack(spacing: 8) {
                StatusPill(text: store.link.title, color: store.link.color)

                if store.device.recording {
                    HStack(spacing: 4) {
                        RecDot(size: 5)
                        Text("录制中")
                            .font(AuroraFont.labelSmall)
                            .foregroundStyle(Aurora.rose)
                    }
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(Aurora.rose.tint(0.13)))
                }

                Spacer(minLength: 0)

                Text(store.device.uptimeText)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.muted)
            }

            Spacer().frame(height: 12)

            HStack(spacing: 0) {
                MetricTile(
                    label: "总码率",
                    value: totalBitrateText,
                    unit: "Mbps",
                    tint: Aurora.brand,
                    systemIcon: "waveform.path.ecg"
                )

                divider

                MetricTile(
                    label: "在线机位",
                    value: "\(onlineCount)",
                    unit: "/\(store.cameras.count)",
                    tint: Aurora.teal,
                    systemIcon: "video"
                )

                divider

                MetricTile(
                    label: "存储剩余",
                    value: StorageInfo.bytes(store.device.storage.freeBytes),
                    tint: Aurora.info,
                    systemIcon: "internaldrive"
                )

                divider

                MetricTile(
                    label: "可回溯",
                    value: "\(store.device.storage.retentionDays)",
                    unit: "天",
                    tint: Aurora.warning,
                    systemIcon: "clock.arrow.circlepath"
                )
            }

            Spacer().frame(height: 14)

            Button {
                store.toggleRecording()
            } label: {
                AuroraButtonLabel(
                    text: store.device.recording ? "停止录制" : "开始录制",
                    systemIcon: store.device.recording ? "stop.fill" : "record.circle",
                    height: AuroraMetrics.primaryButtonHeight,
                    font: AuroraFont.labelLarge,
                    expands: true
                )
                .foregroundStyle(
                    store.device.recording ? Aurora.readableRose(scheme) : Aurora.readableBrand(scheme)
                )
                .auroraGlass(
                    AuroraShape.button,
                    tint: store.device.recording ? Aurora.rose : Aurora.brand,
                    tintOpacity: store.device.recording ? 0.18 : 0.12
                )
            }
            .buttonStyle(PressScaleStyle())
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(Aurora.hairline)
            .frame(width: 1, height: 26)
    }

    // MARK: - 宫格

    private var cameraGrid: some View {
        TimelineView(.periodic(from: Date(), by: 1)) { context in
            gridBody(stamp: Clock.hhmmss(Int(context.date.timeIntervalSince1970)))
        }
    }

    // ViewBuilder 里不能放 let 声明，所以时间戳作为参数传进来
    private func gridBody(stamp: String) -> some View {
        LazyVGrid(
            columns: [GridItem(.adaptive(minimum: 152), spacing: 8)],
            spacing: 8
        ) {
            ForEach(store.cameras) { camera in
                Button {
                    carouselOnOpen = false
                    fullScreenCamera = camera
                } label: {
                    Rectangle()
                        .fill(Color(hex: 0x0B0B12))
                        .aspectRatio(16.0 / 10.0, contentMode: .fit)
                        .overlay(
                            CameraCanvas(
                                camera: camera,
                                timestamp: camera.online ? stamp : nil
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Aurora.line.opacity(0.55), lineWidth: 1)
                        )
                }
                .buttonStyle(PressScaleStyle(scale: 0.97))
            }
        }
    }

    // MARK: - 说明卡

    private var noticeCard: some View {
        SectionCard {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: store.link.isMock ? "sparkles" : "wifi")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Aurora.brand)
                    .frame(width: 26, height: 26)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(Aurora.brand.tint(0.11))
                    )

                VStack(alignment: .leading, spacing: 3) {
                    Text(store.link.isMock ? "当前是演示画面" : "画面来自车机局域网推流")
                        .font(AuroraFont.titleSmall)
                        .foregroundStyle(Aurora.ink)

                    Text(
                        store.link.isMock
                        ? "车机端装上 App 并与手机连同一个 WiFi，本页会自动切到真实画面。现在看到的是合成信号，用来验证布局和交互。"
                        : "手机与车机需处于同一局域网。切到「设备」页可以查看连接详情或换一台车机。"
                    )
                    .font(AuroraFont.bodySmall)
                    .foregroundStyle(Aurora.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}
