import SwiftUI

/// 根视图：极光背景 + 四页 + 自绘底栏。
///
/// 每个页面自己画顶栏（`AppTopBar`），不套 NavigationStack ——
/// Aurora 的顶栏是自绘的，套系统导航栏会多出一层玻璃背景。
struct RootView: View {

    @EnvironmentObject private var store: SentryStore
    @State private var tab: AppTab = .live

    var body: some View {
        ZStack {
            AuroraBackdrop()

            AuroraTabBar(selection: $tab, badgeCount: store.unreadCount) { current in
                switch current {
                case .live:
                    LiveView()
                case .playback:
                    PlaybackView()
                case .events:
                    EventsView()
                case .device:
                    DeviceView()
                }
            }
        }
        .banner($store.banner)
        .preferredColorScheme(.light)
        .task { await store.bootstrap() }
    }
}
