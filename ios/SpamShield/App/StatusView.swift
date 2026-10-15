import SpamShieldCore
import SwiftUI

struct StatusView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingSetup = false

    var body: some View {
        NavigationStack {
            List {
                if !model.usesAppGroup {
                    Section {
                        Label(
                            "The App Group is not configured, so the call and message extensions can't see your lists. See ios/README.md.",
                            systemImage: "exclamationmark.triangle"
                        )
                        .foregroundStyle(.orange)
                    }
                }

                Section("Call blocking") {
                    LabeledContent("Extension", value: statusText)
                    LabeledContent("Numbers blocked", value: "\(model.callDirectoryCounts.blocking)")
                    LabeledContent("Numbers labeled", value: "\(model.callDirectoryCounts.identification)")
                    if model.callDirectoryStatus != .enabled {
                        Button("Turn On in Settings") { model.openCallBlockingSettings() }
                    }
                    Button("Reload Call Blocking List") { model.reloadCallDirectory() }
                }

                Section {
                    LabeledContent("Reported numbers", value: "\(model.community.scores.count)")
                    LabeledContent("Last sync", value: lastSyncText)
                    Button {
                        Task { await model.syncNow() }
                    } label: {
                        HStack {
                            Text("Sync Now")
                            if model.isSyncing {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(model.isSyncing || !model.settings.useCommunity)
                } header: {
                    Text("Community blocklist")
                } footer: {
                    if !model.settings.useCommunity {
                        Text("Community data is turned off in Settings.")
                    }
                }

                Section {
                    if model.flaggedMessages.isEmpty {
                        Text("No texts flagged yet.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.flaggedMessages) { message in
                            FlaggedMessageRow(message: message)
                        }
                        Button("Clear", role: .destructive) { model.clearFlaggedMessages() }
                    }
                } header: {
                    Text("Recently filtered texts")
                } footer: {
                    Text("Filtered texts are in Messages › Junk. iOS may not let the filter record them here.")
                }

                Section {
                    Button("How to Turn On SpamShield") { showingSetup = true }
                }
            }
            .navigationTitle("SpamShield")
            .refreshable {
                model.refreshFromStore()
                model.refreshStatus()
            }
            .sheet(isPresented: $showingSetup) {
                SetupView { showingSetup = false }
            }
        }
    }

    private var statusText: String {
        switch model.callDirectoryStatus {
        case .enabled: return "On"
        case .disabled: return "Off"
        case .unknown: return "Unknown"
        case .failed(let message): return message
        }
    }

    private var lastSyncText: String {
        guard let date = model.community.lastSync else { return "Never" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

private struct FlaggedMessageRow: View {
    let message: FlaggedMessage

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(message.sender ?? "Unknown sender")
                    .font(.headline)
                Spacer()
                Text(message.date.formatted(date: .omitted, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(message.reasons.map(ReasonText.describe).joined(separator: ", "))
                .font(.caption)
                .foregroundStyle(.secondary)
            if let text = message.body {
                Text(text)
                    .font(.caption)
                    .lineLimit(2)
            }
        }
    }
}
