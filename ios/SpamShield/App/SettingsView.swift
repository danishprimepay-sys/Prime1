import SpamShieldCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Detect neighbor spoofing", isOn: $model.settings.detectNeighborSpoofing)
                    TextField("My phone number", text: userNumber)
                        .keyboardType(.phonePad)
                    TextField("Country calling code", text: $model.settings.countryCode)
                        .keyboardType(.numberPad)
                } header: {
                    Text("Calls")
                } footer: {
                    Text("Neighbor spoofing is when scammers call from a number that looks like yours (same area code and prefix). Your number stays on this device. iOS only shows SpamShield's labels and blocks; it can't warn during a call.")
                }

                Section {
                    Toggle("Use the spam text classifier", isOn: $model.settings.useClassifier)
                    Toggle("Record flagged senders", isOn: $model.preferences.logFlaggedMessages)
                    Toggle("Also record message text", isOn: $model.preferences.logMessageBodies)
                        .disabled(!model.preferences.logFlaggedMessages)
                } header: {
                    Text("Texts")
                } footer: {
                    Text("Texts are checked on this device and never uploaded.")
                }

                Section {
                    Toggle("Use community reports", isOn: $model.settings.useCommunity)
                    TextField("Server URL", text: $model.preferences.backendBaseURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !isBackendURLValid {
                        Text("Enter an http(s) URL.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Button("Use Default Server") {
                        model.preferences.backendBaseURL = CommunityClient.defaultBaseURL
                    }
                    .disabled(model.preferences.backendBaseURL == CommunityClient.defaultBaseURL)
                } header: {
                    Text("Community")
                } footer: {
                    Text("SpamShield downloads a list of numbers other people reported. Only numbers you report yourself are sent.")
                }
            }
            .navigationTitle("Settings")
            .onDisappear {
                // Community and country settings change what calls are blocked.
                model.reloadCallDirectory()
            }
        }
    }

    private var userNumber: Binding<String> {
        Binding(
            get: { model.settings.userNumber ?? "" },
            set: { model.settings.userNumber = $0.isEmpty ? nil : $0 }
        )
    }

    private var isBackendURLValid: Bool {
        (try? CommunityClient(baseURL: model.preferences.backendBaseURL)) != nil
    }
}
