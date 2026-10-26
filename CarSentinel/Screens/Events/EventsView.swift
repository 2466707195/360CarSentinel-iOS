import SwiftUI

// =============================================================================
// 事件页
//
// 哨兵的核心价值不是「能看画面」，而是「有事的时候主动告诉你」。
// 所以这一页按告警级别上色，未读用品牌色点标出来，点进去直接跳到对应录像。
// =============================================================================

struct EventsView: View {

    @EnvironmentObject private var store: SentryStore
    @State private var playingClip: RecordingClip?

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "哨兵事件", subtitle: subtitle) {
                if store.unreadCount > 0 {
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) {
                            store.markAllRead()
                            store.banner = "已全部标记为已读"
                        }
                    } label: {
                        Text("全部已读")
                            .font(AuroraFont.labelMedium)
                            .foregroundStyle(Aurora.brand)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: AuroraMetrics.cardSpacing) {
                    filterRow
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                        .padding(.top, 4)

                    summaryCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    if store.filteredEvents.isEmpty {
                        emptyCard
                            .padding(.horizontal, AuroraMetrics.pagePadding)
                    } else {
                        ForEach(store.filteredEvents) { event in
                            eventCard(event)
                                .padding(.horizontal, AuroraMetrics.pagePadding)
                        }
                    }

                    Spacer().frame(height: PageLayout.tabBarInset)
                }
            }
            .scrollIndicators(.hidden)
        }
        .fullScreenCover(item: $playingClip) { clip in
            ClipPlayerView(clip: clip).environmentObject(store)
        }
    }

    private var subtitle: String {
        if store.unreadCount > 0 {
            return "\(store.unreadCount) 条未读 · 共 \(store.events.count) 条"
        }
        return "共 \(store.events.count) 条 · 无未读"
    }

    // MARK: - 筛选

    private var filterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ChipButton(
                    title: "全部",
                    selected: store.eventFilter == nil,
                    count: store.events.count
                ) {
                    store.eventFilter = nil
                }

                ForEach(EventKind.allCases, id: \.self) { kind in
                    if eventCount(kind) > 0 {
                        ChipButton(
                            title: kind.label,
                            systemIcon: kind.icon,
                            selected: store.eventFilter == kind,
                            tint: statusColor(kind.defaultLevel),
                            count: eventCount(kind)
                        ) {
                            store.eventFilter = kind
                        }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func eventCount(_ kind: EventKind) -> Int {
        store.events.filter { $0.kind == kind }.count
    }

    // MARK: - 汇总

    private var summaryCard: some View {
        let critical = store.events.filter { $0.level >= 4 }.count
        let warnings = store.events.filter { $0.level == 3 }.count

        return SectionCard {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("最近 3 天")
                        .font(AuroraFont.titleMedium)
                        .foregroundStyle(Aurora.ink)
                    Text(store.link.isMock ? "演示数据" : "车机主动上报")
                        .font(AuroraFont.bodySmall)
                        .foregroundStyle(Aurora.inkSoft)
                }

                Spacer(minLength: 0)

                HStack(spacing: 0) {
                    MetricTile(
                        label: "严重",
                        value: "\(critical)",
                        tint: Aurora.danger,
                        systemIcon: "exclamationmark.triangle.fill"
                    )
                    Rectangle().fill(Aurora.hairline).frame(width: 1, height: 24).padding(.horizontal, 10)
                    MetricTile(
                        label: "提醒",
                        value: "\(warnings)",
                        tint: Aurora.warning,
                        systemIcon: "bell.badge"
                    )
                    Rectangle().fill(Aurora.hairline).frame(width: 1, height: 24).padding(.horizontal, 10)
                    MetricTile(
                        label: "未读",
                        value: "\(store.unreadCount)",
                        tint: Aurora.brand,
                        systemIcon: "circle.fill"
                    )
                }
                .fixedSize()
            }
        }
    }

    // MARK: - 事件卡

    private func eventCard(_ event: SentinelEvent) -> some View {
        let tint = statusColor(event.level)

        return Button {
            openClip(for: event)
        } label: {
            SectionCard {
                HStack(alignment: .top, spacing: 11) {
                    // 级别图标
                    Image(systemName: event.kind.icon)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(tint)
                        .frame(width: 34, height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 11, style: .continuous)
                                .fill(tint.tint(0.13))
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 5) {
                            Text(event.kind.label)
                                .font(AuroraFont.titleSmall)
                                .foregroundStyle(Aurora.ink)

                            if !event.isRead {
                                Circle()
                                    .fill(Aurora.brand)
                                    .frame(width: 6, height: 6)
                            }

                            Text(levelLabel(event.level))
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(tint)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Capsule().fill(tint.tint(0.13)))
                        }

                        Text(event.note)
                            .font(AuroraFont.bodySmall)
                            .foregroundStyle(Aurora.inkSoft)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)

                        HStack(spacing: 6) {
                            Text(Clock.relative(event.at))
                                .font(AuroraFont.labelSmall)
                                .foregroundStyle(Aurora.muted)
                            Text("·")
                                .font(AuroraFont.labelSmall)
                                .foregroundStyle(Aurora.muted)
                            Text(store.cameraName(for: event.cameraId))
                                .font(AuroraFont.labelSmall)
                                .foregroundStyle(Aurora.muted)
                        }
                    }

                    Spacer(minLength: 0)

                    // 缩略图
                    Rectangle()
                        .fill(Color(hex: 0x0B0B12))
                        .frame(width: 54, height: 38)
                        .overlay(
                            SignalPlaceholder(
                                position: store.camera(for: event.cameraId)?.position ?? .front,
                                online: true,
                                compact: true
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(alignment: .bottom) {
                            Image(systemName: "play.fill")
                                .font(.system(size: 7, weight: .bold))
                                .foregroundStyle(Color.white)
                                .padding(3)
                                .background(Circle().fill(Color.black.opacity(0.5)))
                                .padding(.bottom, 3)
                        }
                }
            }
        }
        .buttonStyle(PressScaleStyle(scale: 0.985))
    }

    private func levelLabel(_ level: Int) -> String {
        switch level {
        case 4: return "严重"
        case 3: return "提醒"
        case 2: return "正常"
        case 1: return "信息"
        default: return "其他"
        }
    }

    // MARK: - 空态

    private var emptyCard: some View {
        SectionCard {
            EmptyStateView(
                systemIcon: "checkmark.shield",
                title: "没有这类事件",
                message: "换个筛选条件，或者车停得挺安稳。"
            )
            .padding(.vertical, 18)
        }
    }

    // MARK: - 行为

    private func openClip(for event: SentinelEvent) {
        if !event.isRead {
            store.markRead(event)
        }

        // 优先用事件里带的 clipId，其次按时间在当天录像里就近找一段
        if let clipId = event.clipId, let clip = store.clip(for: clipId) {
            playingClip = clip
            return
        }

        let nearest = store.recordings
            .filter { $0.cameraId == event.cameraId }
            .min { abs($0.startAt - event.at) < abs($1.startAt - event.at) }

        if let nearest, abs(nearest.startAt - event.at) <= 900 {
            playingClip = nearest
        } else {
            store.banner = "这条事件没有关联录像"
        }
    }
}
