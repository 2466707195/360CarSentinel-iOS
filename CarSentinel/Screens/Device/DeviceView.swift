import SwiftUI

// =============================================================================
// 设备页
//
// 连接管理 + 存储 + 录制设置 + 机位清单 + 关于。
// 「扫描局域网」走 Bonjour，找不到就让用户手输 IP —— 车机热点网段不固定，
// 两条路都得留。
// =============================================================================

struct DeviceView: View {

    @EnvironmentObject private var store: SentryStore

    @State private var showConnect = false
    @State private var showClearConfirm = false

    private var version: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(short) (\(build))"
    }

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "设备", subtitle: subtitle)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: AuroraMetrics.cardSpacing) {
                    connectionCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                        .padding(.top, 4)

                    SectionHeader(title: "存储")
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                    storageCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    SectionHeader(title: "录制设置")
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                    settingsCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    SectionHeader(title: "机位", detail: "\(store.cameras.count) 路")
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                    camerasCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    SectionHeader(title: "关于")
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                    aboutCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    Spacer().frame(height: PageLayout.tabBarInset)
                }
            }
            .scrollIndicators(.hidden)
        }
        .sheet(isPresented: $showConnect) {
            ConnectSheet()
                .environmentObject(store)
        }
        .alert("清理旧录像？", isPresented: $showClearConfirm) {
            Button("取消", role: .cancel) { }
            Button("清理", role: .destructive) {
                store.banner = "已通知车机清理 3 天前的录像"
            }
        } message: {
            Text("会删除车机上最早的循环录像（锁定的片段会保留）。")
        }
    }

    private var subtitle: String {
        switch store.link {
        case .online:
            return store.endpoint?.display ?? "已连接"
        case .connecting:
            return "正在连接…"
        case .failed(let reason):
            return reason
        case .mock:
            return "未连接 · 演示模式"
        }
    }

    // MARK: - 连接

    private var connectionCard: some View {
        SectionCard {
            HStack(alignment: .top, spacing: 12) {
                Monogram(
                    name: store.device.deviceName,
                    tone: store.link.color,
                    size: 46
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text(store.link.isMock ? "演示数据源" : store.device.deviceName)
                        .font(AuroraFont.titleMedium)
                        .foregroundStyle(Aurora.ink)
                        .lineLimit(1)

                    Text(store.link.isOnline ? store.device.model : "还没有连上真实车机")
                        .font(AuroraFont.bodySmall)
                        .foregroundStyle(Aurora.inkSoft)
                        .lineLimit(1)
                }

                Spacer(minLength: 0)

                StatusPill(text: store.link.title, color: store.link.color)
            }

            Spacer().frame(height: 12)

            HairLine()

            InfoRow(
                systemIcon: "network",
                title: "车机地址",
                value: store.endpoint?.display ?? "未设置"
            )
            HairLine()
            InfoRow(
                systemIcon: "memorychip",
                title: "固件版本",
                value: store.device.firmware
            )
            HairLine()
            InfoRow(
                systemIcon: "timer",
                title: "本次运行",
                value: store.device.uptimeText
            )

            Spacer().frame(height: 12)

            HStack(spacing: 8) {
                Button {
                    showConnect = true
                } label: {
                    AuroraButtonLabel(
                        text: store.link.isOnline ? "更换车机" : "连接车机",
                        systemIcon: "antenna.radiowaves.left.and.right",
                        height: AuroraMetrics.primaryButtonHeight,
                        font: AuroraFont.labelLarge,
                        expands: true
                    )
                    .foregroundStyle(Color.white)
                    .background(AuroraShape.button.fill(Aurora.brand))
                }
                .buttonStyle(PressScaleStyle())

                if store.link.isOnline {
                    Button {
                        store.disconnect()
                    } label: {
                        AuroraButtonLabel(
                            text: "断开",
                            systemIcon: "xmark.circle",
                            height: AuroraMetrics.primaryButtonHeight,
                            font: AuroraFont.labelLarge,
                            expands: true
                        )
                        .foregroundStyle(Aurora.onRoseContainer)
                        .background(AuroraShape.button.fill(Aurora.roseContainer))
                    }
                    .buttonStyle(PressScaleStyle())
                    .frame(width: 96)
                }
            }
        }
    }

    // MARK: - 存储

    private var storageCard: some View {
        SectionCard {
            HStack(alignment: .firstTextBaseline) {
                Text(StorageInfo.bytes(store.device.storage.usedBytes))
                    .font(AuroraFont.headlineSmall)
                    .foregroundStyle(Aurora.ink)
                Text("/ \(store.device.storage.totalText)")
                    .font(AuroraFont.bodySmall)
                    .foregroundStyle(Aurora.muted)
                Spacer(minLength: 0)
                Text("可回溯 \(store.device.storage.retentionDays) 天")
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.brand)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Aurora.brand.tint(0.12)))
            }

            Spacer().frame(height: 10)

            MeterBar(
                progress: store.device.storage.usedRatio,
                tint: store.device.storage.usedRatio > 0.85 ? Aurora.warning : Aurora.brand,
                height: 9
            )

            Spacer().frame(height: 10)

            HStack(spacing: 6) {
                Text("剩余 \(store.device.storage.freeText)")
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.inkSoft)
                Spacer(minLength: 0)
                TextLinkButton(text: "清理旧录像", systemIcon: "trash") {
                    showClearConfirm = true
                }
            }
        }
    }

    // MARK: - 录制设置

    private var settingsCard: some View {
        SectionCard {
            settingRow(
                icon: "moon",
                title: "驻车监控",
                detail: "熄火后继续用低功耗录制，检测到异常自动告警",
                isOn: Binding(
                    get: { store.settings.parkingMode },
                    set: { store.setParkingMode($0) }
                )
            )

            HairLine()

            settingRow(
                icon: "arrow.triangle.2.circlepath",
                title: "循环覆盖",
                detail: "存储写满后自动删最早的录像（锁定的除外）",
                isOn: Binding(
                    get: { store.settings.loopOverwrite },
                    set: { store.settings.loopOverwrite = $0 }
                )
            )

            HairLine()

            settingRow(
                icon: "wifi",
                title: "自动重连",
                detail: "回到车机 WiFi 后自动恢复连接",
                isOn: Binding(
                    get: { store.settings.autoReconnect },
                    set: { store.settings.autoReconnect = $0 }
                )
            )

            HairLine()

            VStack(alignment: .leading, spacing: 8) {
                Text("碰撞灵敏度")
                    .font(AuroraFont.bodyMedium)
                    .foregroundStyle(Aurora.ink)
                HStack(spacing: 6) {
                    ForEach(SentrySettings.Sensitivity.allCases, id: \.self) { level in
                        ChipButton(
                            title: level.label,
                            selected: store.settings.sensitivity == level
                        ) {
                            store.settings.sensitivity = level
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            .padding(.vertical, 10)

            HairLine()

            VStack(alignment: .leading, spacing: 8) {
                Text("手机端画质")
                    .font(AuroraFont.bodyMedium)
                    .foregroundStyle(Aurora.ink)
                HStack(spacing: 6) {
                    ForEach(SentrySettings.StreamQuality.allCases, id: \.self) { quality in
                        ChipButton(
                            title: quality.label,
                            selected: store.settings.streamQuality == quality
                        ) {
                            store.settings.streamQuality = quality
                        }
                    }
                    Spacer(minLength: 0)
                }
                Text(store.settings.streamQuality.detail)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.muted)
            }
            .padding(.vertical, 10)
        }
    }

    private func settingRow(
        icon: String,
        title: String,
        detail: String,
        isOn: Binding<Bool>
    ) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Aurora.brand)
                .frame(width: 26, height: 26)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Aurora.brand.tint(0.11))
                )

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AuroraFont.bodyMedium)
                    .foregroundStyle(Aurora.ink)
                Text(detail)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            AuroraSwitch(isOn: isOn)
        }
        .padding(.vertical, 10)
    }

    // MARK: - 机位

    private var camerasCard: some View {
        SectionCard {
            VStack(spacing: 0) {
                ForEach(Array(store.cameras.enumerated()), id: \.element.id) { index, camera in
                    HStack(spacing: 10) {
                        Image(systemName: camera.position.icon)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Aurora.brand)
                            .frame(width: 28, height: 28)
                            .background(
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(Aurora.brand.tint(0.11))
                            )

                        VStack(alignment: .leading, spacing: 1) {
                            Text("\(camera.name) · \(camera.id)")
                                .font(AuroraFont.bodyMedium)
                                .foregroundStyle(Aurora.ink)
                                .lineLimit(1)
                            Text("\(camera.resolution) · \(camera.fps) fps · \(camera.bitrateText)")
                                .font(AuroraFont.labelSmall)
                                .foregroundStyle(Aurora.muted)
                                .lineLimit(1)
                        }

                        Spacer(minLength: 0)

                        StatusPill(
                            text: camera.online ? "在线" : "离线",
                            color: camera.online ? Aurora.success : Aurora.muted
                        )
                    }
                    .padding(.vertical, 9)

                    if index < store.cameras.count - 1 {
                        HairLine()
                    }
                }
            }
        }
    }

    // MARK: - 关于

    private var aboutCard: some View {
        SectionCard {
            InfoRow(systemIcon: "app.badge", title: "App 版本", value: version)
            HairLine()
            InfoRow(systemIcon: "doc.text", title: "接口协议", value: "v1")
            HairLine()
            InfoRow(systemIcon: "lock.shield", title: "传输范围", value: "仅局域网")
            Spacer().frame(height: 8)
            Text("画面与录像只在手机和车机之间直连，不经过任何服务器。车机端需要安装配套的「车载哨兵 · 车机端」并开热点或接入同一 WiFi。")
                .font(AuroraFont.labelSmall)
                .foregroundStyle(Aurora.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
