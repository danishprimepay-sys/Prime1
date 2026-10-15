import BackgroundTasks
import Foundation
import SpamShieldCore

/// Periodically refreshes the community blocklist with a BGAppRefreshTask.
enum BackgroundRefresh {
    static let interval: TimeInterval = 6 * 60 * 60

    static func register(model: AppModel) {
        _ = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: AppIdentifiers.backgroundRefreshTask,
            using: nil
        ) { task in
            handle(task, model: model)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: AppIdentifiers.backgroundRefreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Expected in the Simulator and when Background App Refresh is off.
            print("SpamShield: could not schedule refresh: \(error)")
        }
    }

    private static func handle(_ task: BGTask, model: AppModel) {
        schedule()
        let work = Task { @MainActor in
            let succeeded = await model.syncNow()
            task.setTaskCompleted(success: succeeded)
        }
        task.expirationHandler = {
            work.cancel()
        }
    }
}
