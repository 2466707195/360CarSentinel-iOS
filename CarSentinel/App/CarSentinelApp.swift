import SwiftUI

@main
struct CarSentinelApp: App {

    @StateObject private var store = SentryStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
        }
    }
}
