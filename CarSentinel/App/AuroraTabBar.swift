import SwiftUI

// =============================================================================
// 自绘底部 Tab 栏
//
// 为什么不用系统 TabView：
//   1. iOS 18 才有 `Tab` 这个 API，用它就得要求 Xcode 16 的 SDK；
//      本工程故意把门槛压到 16.4，好让任何云端机器都能编。
//   2. Aurora 的底栏是「实白胶囊 + 选中项浅品牌底 + 品牌色文字」，
//      系统底栏在低版本上对不上这个视觉。
//
// 视觉与 Android 端的 `BottomNav.kt` 逐值一致：26pt 圆角、1pt 描边、10pt 阴影。
// =============================================================================

enum AppTab: String, CaseIterable, Hashable {
    case live
    case playback
    case events
    case device

    var title: String {
        switch self {
        case .live: return "实时"
        case .playback: return "回放"
        case .events: return "事件"
        case .device: return "设备"
        }
    }

    /// 未选中用 outline 变体，选中用 fill 变体
    var icon: String {
        switch self {
        case .live: return "video"
        case .playback: return "play.rectangle"
        case .events: return "bell"
        case .device: return "gearshape"
        }
    }

    var selectedIcon: String {
        switch self {
        case .live: return "video.fill"
        case .playback: return "play.rectangle.fill"
        case .events: return "bell.fill"
        case .device: return "gearshape.fill"
        }
    }
}

struct AuroraTabBar<Content: View>: View {

    @Binding private var selection: AppTab
    private let badgeCount: Int
    private let content: (AppTab) -> Content

    init(
        selection: Binding<AppTab>,
        badgeCount: Int = 0,
        @ViewBuilder content: @escaping (AppTab) -> Content
    ) {
        self._selection = selection
        self.badgeCount = badgeCount
        self.content = content
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            content(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            HStack(spacing: 0) {
                ForEach(AppTab.allCases, id: \.self) { tab in
                    item(tab)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 7)
            .background(
                AuroraShape.bar
                    .fill(Color.white)
                    .shadow(color: Aurora.barShadow, radius: 10, x: 0, y: 4)
            )
            .overlay(
                AuroraShape.bar.stroke(Aurora.line.opacity(0.55), lineWidth: 1)
            )
            // 浮层必须整块吞点击，否则空白处点击会穿透到底下的列表项
            .contentShape(AuroraShape.bar)
            .onTapGesture { }
            .padding(.horizontal, AuroraMetrics.pagePadding)
            .padding(.top, 10)
            .padding(.bottom, 8)
        }
    }

    private func item(_ tab: AppTab) -> some View {
        let isSelected = tab == selection

        return Button {
            withAnimation(.easeOut(duration: 0.16)) { selection = tab }
        } label: {
            VStack(spacing: 2) {
                ZStack {
                    if isSelected {
                        Circle()
                            .fill(Aurora.brand.tint(0.13))
                            .frame(width: 34, height: 26)
                    }
                    Image(systemName: isSelected ? tab.selectedIcon : tab.icon)
                        .font(.system(size: 17, weight: .medium))
                        .frame(width: 34, height: 26)
                }
                .overlay(alignment: .topTrailing) {
                    if tab == .events, badgeCount > 0 {
                        Text(badgeCount > 99 ? "99" : "\(badgeCount)")
                            .font(.system(size: 9.5, weight: .bold))
                            .foregroundStyle(Color.white)
                            .padding(.horizontal, 4.5)
                            .padding(.vertical, 1)
                            .background(Capsule().fill(Aurora.rose))
                            .offset(x: 8, y: -2)
                    }
                }

                Text(tab.title)
                    .font(AuroraFont.labelSmall)
                    .lineLimit(1)
            }
            .foregroundStyle(isSelected ? Aurora.brand : Aurora.inkSoft)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 内容区底部留白：避开悬浮底栏。
enum PageLayout {
    /// 只有底栏时
    static let tabBarInset: CGFloat = 96
    /// 底栏 + 页内浮动操作条
    static let tabBarWithActionInset: CGFloat = 178
}
