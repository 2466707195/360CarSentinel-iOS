import SwiftUI

// =============================================================================
// 回放页
//
// 顶部选日期 → 中间看当天汇总 → 下面按「小时」分组列录像。
// 分组卡片比一把梭的长列表好扫：找「昨晚 9 点」比滚 200 行快得多。
// =============================================================================

struct PlaybackView: View {

    @EnvironmentObject private var store: SentryStore
    @State private var selectedClip: RecordingClip?

    var body: some View {
        VStack(spacing: 0) {
            AppTopBar(title: "录像回放", subtitle: subtitle) {
                AuroraToolbarIconButton("arrow.clockwise", accessibilityLabel: "刷新") {
                    Task {
                        await store.reloadLibrary()
                        if store.link.isMock { store.banner = "演示数据已刷新" }
                    }
                }
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: AuroraMetrics.cardSpacing) {
                    dayPicker
                        .padding(.horizontal, AuroraMetrics.pagePadding)
                        .padding(.top, 4)

                    cameraPicker
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    summaryCard
                        .padding(.horizontal, AuroraMetrics.pagePadding)

                    if store.groupedClips.isEmpty {
                        emptyCard
                            .padding(.horizontal, AuroraMetrics.pagePadding)
                    } else {
                        ForEach(store.groupedClips) { group in
                            VStack(alignment: .leading, spacing: 6) {
                                SectionHeader(
                                    title: group.title,
                                    detail: "\(group.clips.count) 段"
                                )
                                clipCard(group.clips)
                            }
                            .padding(.horizontal, AuroraMetrics.pagePadding)
                        }
                    }

                    Spacer().frame(height: PageLayout.tabBarInset)
                }
            }
            .scrollIndicators(.hidden)
        }
        .fullScreenCover(item: $selectedClip) { clip in
            ClipPlayerView(clip: clip)
                .environmentObject(store)
        }
    }

    private var subtitle: String {
        let summary = store.daySummary
        if summary.clipCount == 0 { return "当天没有录像" }
        return "\(summary.clipCount) 段 · \(summary.durationText)"
    }

    // MARK: - 日期选择

    private var dayPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(store.dayOptions, id: \.self) { day in
                    Button {
                        store.selectedDay = Calendar.current.startOfDay(for: day)
                        if store.link.isOnline {
                            Task { await store.reloadLibrary() }
                        }
                    } label: {
                        VStack(spacing: 1) {
                            Text(dayShortLabel(day, isSelected: isSelectedDay(day)))
                                .font(AuroraFont.labelMedium)
                            Text(Clock.monthDay(day))
                                .font(.system(size: 9.5, weight: .medium))
                                .opacity(0.75)
                        }
                        .foregroundStyle(isSelectedDay(day) ? Color.white : Aurora.inkSoft)
                        .frame(width: 62)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(isSelectedDay(day) ? Aurora.brand : Color.white)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(
                                    isSelectedDay(day) ? Aurora.brand : Aurora.line.opacity(0.7),
                                    lineWidth: 1
                                )
                        )
                    }
                    .buttonStyle(PressScaleStyle(scale: 0.96))
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func isSelectedDay(_ day: Date) -> Bool {
        Calendar.current.isDate(day, inSameDayAs: store.selectedDay)
    }

    private func dayShortLabel(_ day: Date, isSelected: Bool) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return "今天" }
        if calendar.isDateInYesterday(day) { return "昨天" }
        return Clock.weekday(day)
    }

    // MARK: - 机位筛选

    private var cameraPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ChipButton(
                    title: "全部",
                    systemIcon: "square.grid.2x2",
                    selected: store.cameraFilter == nil
                ) {
                    store.cameraFilter = nil
                    Task { await store.reloadLibrary() }
                }

                ForEach(store.cameras) { camera in
                    ChipButton(
                        title: camera.name,
                        systemIcon: camera.position.icon,
                        selected: store.cameraFilter == camera.id
                    ) {
                        store.cameraFilter = camera.id
                        Task { await store.reloadLibrary() }
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: - 当天汇总

    private var summaryCard: some View {
        let summary = store.daySummary
        return SectionCard {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(Clock.monthDay(store.selectedDay))
                        .font(AuroraFont.titleMedium)
                        .foregroundStyle(Aurora.ink)
                    Text(store.link.isMock ? "演示数据" : "来自车机本地存储")
                        .font(AuroraFont.bodySmall)
                        .foregroundStyle(Aurora.inkSoft)
                }

                Spacer(minLength: 0)

                HStack(spacing: 0) {
                    MetricTile(label: "段数", value: "\(summary.clipCount)", tint: Aurora.brand)
                    Rectangle()
                        .fill(Aurora.hairline)
                        .frame(width: 1, height: 24)
                        .padding(.horizontal, 10)
                    MetricTile(label: "总时长", value: summary.durationText, tint: Aurora.teal)
                    Rectangle()
                        .fill(Aurora.hairline)
                        .frame(width: 1, height: 24)
                        .padding(.horizontal, 10)
                    MetricTile(label: "占用", value: summary.sizeText, tint: Aurora.info)
                }
                .fixedSize()
            }
        }
    }

    // MARK: - 分组列表

    private func clipCard(_ clips: [RecordingClip]) -> some View {
        SectionCard {
            VStack(spacing: 0) {
                ForEach(Array(clips.enumerated()), id: \.element.id) { index, clip in
                    Button {
                        selectedClip = clip
                    } label: {
                        clipRow(clip)
                    }
                    .buttonStyle(.plain)

                    if index < clips.count - 1 {
                        HairLine().padding(.leading, 78)
                    }
                }
            }
        }
    }

    private func clipRow(_ clip: RecordingClip) -> some View {
        HStack(spacing: 12) {
            // 缩略图
            Rectangle()
                .fill(Color(hex: 0x0B0B12))
                .frame(width: 66, height: 44)
                .overlay(
                    SignalPlaceholder(
                        position: store.camera(for: clip.cameraId)?.position ?? .front,
                        online: true,
                        compact: true
                    )
                )
                .overlay(alignment: .bottomTrailing) {
                    Text(Clock.hhmm(clip.startAt))
                        .font(.system(size: 8, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.white.opacity(0.9))
                        .padding(.horizontal, 3)
                        .padding(.vertical, 1)
                        .background(Color.black.opacity(0.35))
                        .padding(2)
                }
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(store.cameraName(for: clip.cameraId))
                        .font(AuroraFont.titleSmall)
                        .foregroundStyle(Aurora.ink)

                    if clip.source == .event {
                        Text("事件")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(Aurora.onRoseContainer)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Aurora.roseContainer))
                    }

                    if clip.locked {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 8.5, weight: .semibold))
                            .foregroundStyle(Aurora.warning)
                    }
                }

                Text("\(clip.timeRangeText) · \(clip.durationText)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Aurora.inkSoft)
                    .lineLimit(1)

                Text(clip.sizeText)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.muted)
            }

            Spacer(minLength: 0)

            Image(systemName: "play.circle.fill")
                .font(.system(size: 24))
                .foregroundStyle(Aurora.brand)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    // MARK: - 空状态

    private var emptyCard: some View {
        SectionCard {
            EmptyStateView(
                systemIcon: "film",
                title: "这天没有录像",
                message: "换个日期看看，或者确认车机上的循环录制还开着。"
            )
            .padding(.vertical, 18)
        }
    }
}
