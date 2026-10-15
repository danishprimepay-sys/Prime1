import CallKit
import Foundation
import SpamShieldCore

/// Hands iOS the numbers to block or label. Runs whenever the app calls
/// `CXCallDirectoryManager.reloadExtension`; always does a full reload.
final class CallDirectoryHandler: CXCallDirectoryProvider {
    override func beginRequest(with context: CXCallDirectoryExtensionContext) {
        context.delegate = self

        if context.isIncremental {
            context.removeAllBlockingEntries()
            context.removeAllIdentificationEntries()
        }

        if let store = SharedStore.appGroup() {
            // Only the thresholds are needed here; skip loading the classifier.
            let thresholds = (try? SpamEngine.bundledRules().thresholds)
                ?? Rules.Thresholds(block: 0.85, warn: 0.55)
            let entries = CallDirectoryBuilder.build(store: store, thresholds: thresholds)
            for number in entries.blocking {
                context.addBlockingEntry(withNextSequentialPhoneNumber: number)
            }
            for entry in entries.identification {
                context.addIdentificationEntry(withNextSequentialPhoneNumber: entry.number, label: entry.label)
            }
        }

        context.completeRequest()
    }
}

extension CallDirectoryHandler: CXCallDirectoryExtensionContextDelegate {
    func requestFailed(for extensionContext: CXCallDirectoryExtensionContext, withError error: Error) {
        // The app sees the failure as an error from reloadExtension(withIdentifier:).
        NSLog("SpamShield Call Directory request failed: %@", String(describing: error))
    }
}
