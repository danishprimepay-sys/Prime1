import SwiftUI

/// Explains how to switch SpamShield on; iOS gives apps no way to do it for the user.
struct SetupView: View {
    var onDone: () -> Void
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("iOS doesn't let apps answer or screen live calls. SpamShield gives iOS a list of numbers to block or label, and sorts texts from unknown senders into Junk. Both are switched on in Settings.")
                }

                Section("1. Block and label calls") {
                    SetupStep(number: 1, text: "Open the Settings app.")
                    SetupStep(number: 2, text: "Go to Phone › Call Blocking & Identification (on iOS 18: Apps › Phone › Call Blocking & Identification).")
                    SetupStep(number: 3, text: "Turn on SpamShield.")
                    Button("Open Call Blocking Settings") {
                        model.openCallBlockingSettings()
                    }
                }

                Section("2. Filter spam texts") {
                    SetupStep(number: 1, text: "Open the Settings app.")
                    SetupStep(number: 2, text: "Go to Apps › Messages › Unknown & Spam (on iOS 16–17: Messages › Unknown & Spam).")
                    SetupStep(number: 3, text: "Under SMS Filtering, choose SpamShield.")
                    Text("Texts from your contacts are never sent to SpamShield. Flagged texts go to the Junk list in Messages.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("3. Hidden and unknown callers") {
                    Text("iOS doesn't share hidden (No Caller ID) calls with apps. To stop them, turn on Settings › Phone › Silence Unknown Callers — this silences every number not in your contacts or recent calls.")
                }

                Section("Staying up to date") {
                    Text("SpamShield downloads the community blocklist when you open the app and periodically in the background. Keep Background App Refresh on for SpamShield.")
                }
            }
            .navigationTitle("Set Up SpamShield")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                }
            }
        }
    }
}

private struct SetupStep: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(number).")
                .font(.headline)
                .foregroundStyle(.tint)
            Text(text)
        }
    }
}
