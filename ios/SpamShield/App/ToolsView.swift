import SpamShieldCore
import SwiftUI

struct ToolsView: View {
    var body: some View {
        NavigationStack {
            List {
                NavigationLink {
                    LookupView()
                } label: {
                    Label("Look Up a Number", systemImage: "magnifyingglass")
                }
                NavigationLink {
                    ReportView()
                } label: {
                    Label("Report a Number", systemImage: "exclamationmark.bubble")
                }
                NavigationLink {
                    TextTesterView()
                } label: {
                    Label("Test a Message", systemImage: "text.badge.checkmark")
                }
            }
            .navigationTitle("Tools")
        }
    }
}

struct LookupView: View {
    @EnvironmentObject private var model: AppModel
    @State private var number = ""
    @State private var result: NumberLookup?
    @State private var errorText: String?
    @State private var isLoading = false

    var body: some View {
        Form {
            Section {
                TextField("Phone number", text: $number)
                    .keyboardType(.phonePad)
                Button {
                    Task { await lookUp() }
                } label: {
                    HStack {
                        Text("Look Up")
                        if isLoading {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(number.isEmpty || isLoading)
            } footer: {
                Text("Looks the number up in the community database.")
            }

            if let result = result {
                Section("Result") {
                    LabeledContent("Number", value: result.number)
                    LabeledContent("Spam score", value: result.score.formatted(.percent.precision(.fractionLength(0))))
                    LabeledContent("Spam reports", value: "\(result.spamReports)")
                    LabeledContent("Not-spam reports", value: "\(result.notSpamReports)")
                    LabeledContent("Last reported", value: result.lastReportedAt ?? "Never")
                    LabeledContent("SpamShield would", value: verdictText(score: result.score))
                }
            }

            if let errorText = errorText {
                Section {
                    Text(errorText).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Look Up")
    }

    private func lookUp() async {
        isLoading = true
        defer { isLoading = false }
        do {
            result = try await model.lookup(number)
            errorText = nil
        } catch {
            result = nil
            errorText = error.localizedDescription
        }
    }

    private func verdictText(score: Double) -> String {
        if score >= model.thresholds.block { return "Block calls" }
        if score >= model.thresholds.warn { return "Label as \(CallDirectoryBuilder.identificationLabel)" }
        return "Allow"
    }
}

struct ReportView: View {
    @EnvironmentObject private var model: AppModel
    @State private var number = ""
    @State private var channel: Channel = .call
    @State private var verdict: ReportVerdict = .spam
    @State private var category = ""
    @State private var status: String?
    @State private var isSending = false

    var body: some View {
        Form {
            Section {
                TextField("Phone number", text: $number)
                    .keyboardType(.phonePad)
                Picker("Received as", selection: $channel) {
                    Text("Call").tag(Channel.call)
                    Text("Text").tag(Channel.sms)
                }
                .pickerStyle(.segmented)
                Picker("This number is", selection: $verdict) {
                    Text("Spam").tag(ReportVerdict.spam)
                    Text("Not spam").tag(ReportVerdict.notSpam)
                }
                .pickerStyle(.segmented)
                TextField("Category (optional, e.g. robocall)", text: $category)
                    .textInputAutocapitalization(.never)
            } footer: {
                Text("Only the number, how it reached you, your verdict and an anonymous install ID are sent.")
            }

            Section {
                Button {
                    Task { await send() }
                } label: {
                    HStack {
                        Text("Send Report")
                        if isSending {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(number.isEmpty || isSending)
                if let status = status {
                    Text(status)
                        .font(.footnote)
                }
            }

            Section {
                Button("Add to My Block List") {
                    add(toBlocklist: true)
                }
                .disabled(number.isEmpty)
                Button("Add to My Allow List") {
                    add(toBlocklist: false)
                }
                .disabled(number.isEmpty)
            }
        }
        .navigationTitle("Report")
    }

    private func send() async {
        isSending = true
        defer { isSending = false }
        do {
            let response = try await model.report(
                number,
                channel: channel,
                verdict: verdict,
                category: category.isEmpty ? nil : category
            )
            status = response.accepted ? "Thanks! Report for \(response.number) received." : "The server did not accept the report."
        } catch {
            status = "Could not send: \(error.localizedDescription)"
        }
    }

    private func add(toBlocklist: Bool) {
        let entry = model.normalized(number)
        if toBlocklist {
            if !model.blocklist.contains(entry) { model.blocklist.append(entry) }
        } else {
            if !model.allowlist.contains(entry) { model.allowlist.append(entry) }
        }
        status = "Added \(entry) to your \(toBlocklist ? "block" : "allow") list."
    }
}

struct TextTesterView: View {
    @EnvironmentObject private var model: AppModel
    @State private var channel: Channel = .sms
    @State private var sender = ""
    @State private var message = ""
    @State private var verdict: Verdict?

    var body: some View {
        Form {
            Section {
                Picker("Type", selection: $channel) {
                    Text("Text").tag(Channel.sms)
                    Text("Call").tag(Channel.call)
                }
                .pickerStyle(.segmented)
                TextField(senderPrompt, text: $sender)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if channel == .sms {
                    TextEditor(text: $message)
                        .frame(minHeight: 120)
                }
                Button("Check") {
                    verdict = model.evaluate(channel: channel, sender: sender, body: message)
                }
            } footer: {
                Text("Runs the same on-device checks as the message filter and call blocking, using your lists and settings. Nothing is sent anywhere.")
            }

            if let verdict = verdict {
                VerdictSection(verdict: verdict)
            } else if model.engine == nil {
                Section {
                    Text("The detection rules could not be loaded.").foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Test a Message")
    }

    private var senderPrompt: String {
        channel == .sms ? "Sender (number, short code or name)" : "Caller number (empty = hidden)"
    }
}

private struct VerdictSection: View {
    let verdict: Verdict

    var body: some View {
        Section("Verdict") {
            LabeledContent("Result", value: ReasonText.describe(verdict.action))
            LabeledContent("Score", value: verdict.score.formatted(.number.precision(.fractionLength(3))))
            if verdict.reasons.isEmpty {
                Text("No spam signals found.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(verdict.reasons, id: \.self) { reason in
                    VStack(alignment: .leading) {
                        Text(ReasonText.describe(reason))
                        Text(reason)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Text(actionExplanation)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var actionExplanation: String {
        switch verdict.action {
        case .allow: return "Delivered normally."
        case .warn, .block: return "A text would go to Messages › Junk."
        }
    }
}
