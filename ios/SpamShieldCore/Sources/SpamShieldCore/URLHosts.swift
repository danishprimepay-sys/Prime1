import Foundation

/// URL detection in (lowercased) message text (SPEC §4).
public enum URLHosts {
    public static let urlPattern =
        "(https?://)?(([a-z0-9-]+[.])+[a-z]{2,24}|[0-9]{1,3}([.][0-9]{1,3}){3})(?![a-z0-9-])"
    static let ipHostPattern = "^[0-9]{1,3}([.][0-9]{1,3}){3}$"

    // The patterns are constants covered by the test vectors.
    private static let urlRegex = try! PortableRegex(urlPattern)
    private static let ipHostRegex = try! PortableRegex(ipHostPattern)

    /// Hosts (capture group 2) of every URL found in `text`, in order.
    public static func hosts(in text: String) -> [String] {
        urlRegex.captures(group: 2, in: text)
    }

    public static func isIPHost(_ host: String) -> Bool {
        ipHostRegex.find(in: host)
    }

    public static func isShortener(_ host: String, shorteners: [String]) -> Bool {
        shorteners.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    /// The text after the host's last `.` (the whole host when it has none).
    public static func topLevelDomain(_ host: String) -> String {
        guard let dot = host.range(of: ".", options: .backwards) else { return host }
        return String(host[dot.upperBound...])
    }
}
