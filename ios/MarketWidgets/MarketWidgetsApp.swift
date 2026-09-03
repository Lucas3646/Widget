import SwiftUI

@main
struct MarketWidgetsApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { BrokerConnectionsView() }
                    .tabItem { Label("Connexions", systemImage: "link") }
                ContentView()
                    .tabItem { Label("Widgets", systemImage: "square.grid.2x2") }
            }
            .preferredColorScheme(.dark)
        }
    }
}
