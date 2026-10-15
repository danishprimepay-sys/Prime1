import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingSetup = false

    var body: some View {
        TabView {
            StatusView()
                .tabItem { Label("Status", systemImage: "shield.lefthalf.filled") }
            ListsView()
                .tabItem { Label("Lists", systemImage: "list.bullet") }
            ToolsView()
                .tabItem { Label("Tools", systemImage: "magnifyingglass") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .onAppear {
            if !model.preferences.hasCompletedOnboarding {
                showingSetup = true
            }
        }
        .sheet(isPresented: $showingSetup) {
            SetupView {
                model.preferences.hasCompletedOnboarding = true
                showingSetup = false
            }
        }
        .alert(
            "SpamShield",
            isPresented: Binding(
                get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}
