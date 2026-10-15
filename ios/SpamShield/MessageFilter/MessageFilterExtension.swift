import Foundation
import IdentityLookup
import SpamShieldCore

/// Classifies texts from unknown senders entirely on device. iOS never sends
/// messages from contacts to this extension.
final class MessageFilterExtension: ILMessageFilterExtension {
    /// Compiled once per extension process.
    fileprivate static let engine: SpamEngine? = try? SpamEngine.bundled()
}

extension MessageFilterExtension: ILMessageFilterQueryHandling {
    func handle(
        _ queryRequest: ILMessageFilterQueryRequest,
        context: ILMessageFilterExtensionContext,
        completion: @escaping (ILMessageFilterQueryResponse) -> Void
    ) {
        let response = ILMessageFilterQueryResponse()
        response.action = action(for: queryRequest)
        completion(response)
    }

    private func action(for request: ILMessageFilterQueryRequest) -> ILMessageFilterAction {
        guard let engine = MessageFilterExtension.engine, let store = SharedStore.appGroup() else {
            return ILMessageFilterAction.none
        }
        let event = Event(
            channel: .sms,
            number: request.sender,
            body: request.messageBody,
            isContact: false,
            verification: .unknown
        )
        let verdict = engine.evaluate(event, context: store.context())
        switch verdict.action {
        case .allow:
            return .allow
        case .warn, .block:
            record(verdict, for: request, in: store)
            return .junk
        }
    }

    /// Best effort: iOS may not let message filters write to the shared container.
    private func record(_ verdict: Verdict, for request: ILMessageFilterQueryRequest, in store: SharedStore) {
        let preferences = store.preferences()
        guard preferences.logFlaggedMessages else { return }
        let message = FlaggedMessage(
            sender: request.sender,
            action: verdict.action,
            score: verdict.score,
            reasons: verdict.reasons,
            body: preferences.logMessageBodies ? request.messageBody : nil
        )
        try? store.appendFlaggedMessage(message)
    }
}
