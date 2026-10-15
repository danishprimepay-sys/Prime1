import SwiftUI

@main
@MainActor
struct SpamShieldApp: App {
    @StateObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let model = AppModel()
        _model = StateObject(wrappedValue: model)
        // Must be registered before the app finishes launching.
        BackgroundRefresh.register(model: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                model.refreshFromStore()
                model.refreshStatus()
                Task { await model.syncIfStale() }
            case .background:
                BackgroundRefresh.schedule()
            default:
                break
            }
        }
    }
}
