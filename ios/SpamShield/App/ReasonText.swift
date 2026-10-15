import Foundation
import SpamShieldCore

/// Human-readable descriptions of verdict reason ids.
enum ReasonText {
    static func describe(_ reason: String) -> String {
        let keywordPrefix = "keyword:"
        if reason.hasPrefix(keywordPrefix) {
            let id = String(reason.dropFirst(keywordPrefix.count))
            return "Scam wording: " + id.replacingOccurrences(of: "_", with: " ")
        }
        switch reason {
        case "contact": return "Sender is a contact"
        case "allowlist": return "On your allow list"
        case "blocklist": return "On your block list"
        case "hidden_number": return "Hidden caller ID"
        case "verification_failed": return "Caller ID verification failed"
        case "invalid_number": return "Not a valid phone number"
        case "neighbor_spoof": return "Looks like your own number (neighbor spoofing)"
        case "community": return "Reported by the community"
        case "sender_email": return "Sent from an email address"
        case "url_ip_host": return "Link to a bare IP address"
        case "url_shortener": return "Shortened link"
        case "url_suspicious_tld": return "Link with a suspicious domain ending"
        case "classifier": return "Reads like spam"
        default: return reason
        }
    }

    static func describe(_ action: Action) -> String {
        switch action {
        case .allow: return "Allowed"
        case .warn: return "Suspicious"
        case .block: return "Blocked"
        }
    }
}
