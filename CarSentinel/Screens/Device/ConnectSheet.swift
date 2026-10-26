import SwiftUI

// =============================================================================
// 连接车机
//
// 两条路：
//   1. 自动扫描 —— 车机广播 `_carsentry._tcp`，同一个 WiFi 下直接出现
//   2. 手动输入 —— 车机热点网段不固定（192.168.43.1 / 192.168.0.x 都有），
//      自动扫不到时得能手输
// =============================================================================

struct ConnectSheet: View {

    @EnvironmentObject private var store: SentryStore
    @Environment(\.dismiss) private var dismiss

    @StateObject private var discovery = DeviceDiscovery()

    @State private var host: String = ""
    @State private var port: String = "\(DeviceEndpoint.defaultPort)"
    @State private var connecting = false
    @State private var errorText: String?

    var body: some View {
        ZStack {
            AuroraBackdrop()

            ScrollView {
                VStack(alignment: .leading, spacing: AuroraMetrics.cardSpacing) {

                    header

                    scanCard

                    manualCard

                    hintCard

                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, AuroraMetrics.pagePadding)
                .padding(.top, 10)
            }
            .scrollIndicators(.hidden)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear {
            discovery.start()
            if let endpoint = store.endpoint {
                host = endpoint.host
                port = "\(endpoint.port)"
            }
        }
        .onDisappear { discovery.stop() }
    }

    // MARK: - 头部

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text("连接车机")
                    .font(AuroraFont.headlineSmall)
                    .foregroundStyle(Aurora.ink)
                Text("手机和车机必须在同一个 WiFi 下")
                    .font(AuroraFont.bodySmall)
                    .foregroundStyle(Aurora.inkSoft)
            }
            Spacer(minLength: 0)
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Aurora.inkSoft)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(Aurora.surfaceVariant))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 4)
    }

    // MARK: - 扫描

    private var scanCard: some View {
        SectionCard {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("附近的哨兵车机")
                        .font(AuroraFont.titleSmall)
                        .foregroundStyle(Aurora.ink)
                    Text(discovery.isBrowsing ? "正在扫描局域网…" : "扫描已停止")
                        .font(AuroraFont.labelSmall)
                        .foregroundStyle(Aurora.muted)
                }

                Spacer(minLength: 0)

                SmallTonalButton(
                    text: discovery.isBrowsing ? "停止" : "扫描",
                    systemIcon: discovery.isBrowsing ? "stop" : "arrow.clockwise"
                ) {
                    if discovery.isBrowsing {
                        discovery.stop()
                    } else {
                        discovery.start()
                    }
                }
            }

            if let lastError = discovery.lastError {
                Spacer().frame(height: 8)
                Text(lastError)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if discovery.found.isEmpty {
                Spacer().frame(height: 10)
                HairLine()
                HStack(spacing: 8) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.system(size: 15))
                        .foregroundStyle(Aurora.muted)
                    Text(discovery.isBrowsing ? "还没发现设备，请确认车机端已启动" : "点「扫描」开始查找")
                        .font(AuroraFont.bodySmall)
                        .foregroundStyle(Aurora.muted)
                }
                .padding(.top, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Spacer().frame(height: 8)
                ForEach(discovery.found) { endpoint in
                    HairLine()
                    Button {
                        connect(to: endpoint)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "car.fill")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Aurora.brand)
                                .frame(width: 28, height: 28)
                                .background(
                                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                                        .fill(Aurora.brand.tint(0.11))
                                )

                            VStack(alignment: .leading, spacing: 1) {
                                Text(endpoint.name)
                                    .font(AuroraFont.bodyMedium)
                                    .foregroundStyle(Aurora.ink)
                                    .lineLimit(1)
                                Text(endpoint.display)
                                    .font(.system(size: 10.5, design: .monospaced))
                                    .foregroundStyle(Aurora.muted)
                            }

                            Spacer(minLength: 0)

                            Text("连接")
                                .font(AuroraFont.labelMedium)
                                .foregroundStyle(Aurora.brand)
                        }
                        .padding(.vertical, 9)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - 手动

    private var manualCard: some View {
        SectionCard {
            Text("手动输入地址")
                .font(AuroraFont.titleSmall)
                .foregroundStyle(Aurora.ink)
                .padding(.bottom, 8)

            HStack(spacing: 8) {
                AuroraTextField(
                    placeholder: "192.168.43.1",
                    text: $host,
                    systemIcon: "network"
                )

                AuroraTextField(
                    placeholder: "端口",
                    text: $port,
                    systemIcon: nil
                )
                .frame(width: 84)
            }

            if let errorText {
                Spacer().frame(height: 8)
                Text(errorText)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.rose)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer().frame(height: 12)

            Button {
                let trimmedPort = port.trimmingCharacters(in: .whitespaces)
                let raw = trimmedPort.isEmpty ? host : "\(host):\(trimmedPort)"
                guard let endpoint = DeviceDiscovery.parseEndpoint(raw) else {
                    errorText = "地址格式不对，试试 192.168.43.1"
                    return
                }
                connect(to: endpoint)
            } label: {
                AuroraButtonLabel(
                    text: connecting ? "连接中…" : "连接",
                    systemIcon: "bolt.horizontal.circle",
                    height: AuroraMetrics.primaryButtonHeight,
                    font: AuroraFont.labelLarge,
                    expands: true
                )
                .foregroundStyle(Color.white)
                .background(
                    AuroraShape.button.fill(connecting ? Aurora.muted : Aurora.brand)
                )
            }
            .buttonStyle(PressScaleStyle())
            .disabled(connecting)
        }
    }

    // MARK: - 提示

    private var hintCard: some View {
        SectionCard {
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "questionmark.circle")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Aurora.info)
                    .frame(width: 24, height: 24)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(Aurora.info.tint(0.12))
                    )

                VStack(alignment: .leading, spacing: 4) {
                    Text("找不到车机？")
                        .font(AuroraFont.titleSmall)
                        .foregroundStyle(Aurora.ink)
                    Text("· 车机端 App 要处于前台或已设为开机自启\n· 车机开热点，手机连这个热点最稳\n· 首次连接时 iOS 会弹「本地网络」权限，必须允许\n· 车机防火墙不要拦 8710 端口")
                        .font(AuroraFont.labelSmall)
                        .foregroundStyle(Aurora.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: - 行为

    private func connect(to endpoint: DeviceEndpoint) {
        connecting = true
        errorText = nil

        Task {
            await store.connect(to: endpoint)
            connecting = false

            if store.link.isOnline {
                discovery.stop()
                dismiss()
            } else if case .failed(let reason) = store.link {
                errorText = reason
            } else {
                errorText = "连接失败，请检查地址和 WiFi"
            }
        }
    }
}
