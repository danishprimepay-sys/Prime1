import SpamShieldCore
import SwiftUI

struct ListsView: View {
    private enum Kind: String, CaseIterable, Identifiable {
        case block = "Block"
        case allow = "Allow"
        var id: String { rawValue }
    }

    @EnvironmentObject private var model: AppModel
    @State private var kind: Kind = .block
    @State private var draft = ""
    @State private var draftError: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("List", selection: $kind) {
                        ForEach(Kind.allCases) { kind in
                            Text(kind.rawValue).tag(kind)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                    HStack {
                        TextField("Number or pattern", text: $draft)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onSubmit(add)
                        Button("Add", action: add)
                            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    if let draftError = draftError {
                        Text(draftError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text(kind == .block ? Self.blockHelp : Self.allowHelp)
                }

                Section {
                    let entries = currentEntries
                    if entries.isEmpty {
                        Text("Nothing here yet.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                        EntryRow(entry: entry, countryCode: model.settings.countryCode)
                    }
                    .onDelete(perform: delete)
                } header: {
                    Text(listTitle)
                }
            }
            .navigationTitle("Block & Allow")
            .onDisappear {
                // Push list edits to the Call Directory extension.
                model.reloadCallDirectory()
            }
        }
    }

    private static let blockHelp = """
        Use * for any run of characters and ? for exactly one: +1800555* blocks every \
        number starting +1 800 555, and +1212555???? blocks +1 212 555 0000–9999. \
        Letters match sender names, e.g. PROMO*. \
        On iOS, patterns with * only work for texts: call blocking needs exact numbers, \
        so only exact numbers and patterns ending in ? (up to 10,000 numbers in total) \
        block calls.
        """

    private static let allowHelp = """
        Allowed numbers are never flagged and are removed from call blocking. \
        Wildcards (* and ?) work the same way as in the block list.
        """

    private var listTitle: String {
        kind == .block ? "Blocked" : "Always allowed"
    }

    private var currentEntries: [String] {
        kind == .block ? model.blocklist : model.allowlist
    }

    private func add() {
        let entry = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !entry.isEmpty else { return }
        guard NumberPattern.prepare(entry, countryCode: model.settings.countryCode) != nil else {
            draftError = "That doesn't look like a number or pattern."
            return
        }
        draftError = nil
        draft = ""
        switch kind {
        case .block:
            if !model.blocklist.contains(entry) { model.blocklist.append(entry) }
        case .allow:
            if !model.allowlist.contains(entry) { model.allowlist.append(entry) }
        }
    }

    private func delete(_ offsets: IndexSet) {
        switch kind {
        case .block: model.blocklist.remove(atOffsets: offsets)
        case .allow: model.allowlist.remove(atOffsets: offsets)
        }
    }
}

private struct EntryRow: View {
    let entry: String
    let countryCode: String

    var body: some View {
        let prepared = NumberPattern.prepare(entry, countryCode: countryCode) ?? entry
        VStack(alignment: .leading, spacing: 2) {
            Text(entry)
            if prepared != entry {
                Text("Matches as \(prepared)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if prepared.contains("*") {
                Text("Texts only — iOS can't block calls by pattern")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}
