import SwiftUI

struct BrokerConnectionsView: View {
    @State private var apiKey = ""
    @State private var apiSecret = ""
    @State private var status = "Non connecté"
    @State private var isLoading = false
    @State private var flexToken = ""
    @State private var flexQueryID = ""
    @State private var ibkrStatus = "Non connecté"
    @State private var ibkrLoading = false
    @State private var editingKraken = false
    @State private var editingIBKR = false

    private var hasKraken: Bool { KrakenKeychainStore.credentials() != nil }
    private var hasIBKR: Bool { IBKRFlexKeychainStore.credentials() != nil }

    var body: some View {
        Form {
            Section("Kraken Pro") {
                Text(status).foregroundStyle(status.hasPrefix("Connecté") ? .green : .secondary)
                if !hasKraken || editingKraken {
                    TextField("API Key", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Private Key / Secret", text: $apiSecret)
                    Button(isLoading ? "Vérification…" : "Connecter Kraken") { connectKraken() }
                        .disabled(isLoading || apiKey.isEmpty || apiSecret.isEmpty)
                } else {
                    Text("Identifiants enregistrés dans le Trousseau iOS. Tu n’as plus à les saisir.").font(.caption).foregroundStyle(.secondary)
                    Button("Modifier les identifiants Kraken") { editingKraken = true }
                }
                Button("Supprimer Kraken", role: .destructive) { KrakenKeychainStore.clear(); status = "Non connecté"; editingKraken = false }
            }

            Section("Interactive Brokers · Flex") {
                Text("Flex fournit positions + dividendes. Token et Query ID sont saisis une seule fois puis conservés dans le Trousseau iOS.")
                    .font(.caption).foregroundStyle(.secondary)
                Text(ibkrStatus).foregroundStyle(ibkrStatus.hasPrefix("Connecté") ? .green : .secondary)
                if !hasIBKR || editingIBKR {
                    SecureField("Flex Web Service Token", text: $flexToken)
                    TextField("Activity Flex Query ID", text: $flexQueryID).keyboardType(.numberPad)
                    Button(ibkrLoading ? "Vérification…" : "Connecter IBKR") { connectIBKR() }
                        .disabled(ibkrLoading || flexToken.isEmpty || flexQueryID.isEmpty)
                } else {
                    Text("Token + Query ID enregistrés. Les actualisations réutilisent automatiquement ces informations.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("Actualiser IBKR + dividendes") { refreshIBKR() }.disabled(ibkrLoading)
                    Button("Modifier Token / Query ID") { editingIBKR = true }
                }
                Button("Supprimer IBKR", role: .destructive) {
                    IBKRFlexKeychainStore.clear(); IBKRDividendService.clear(); ibkrStatus = "Non connecté"; editingIBKR = false
                }
            }
        }
        .navigationTitle("Comptes connectés")
        .preferredColorScheme(.dark)
        .task { await loadExistingStatus() }
    }

    private func loadExistingStatus() async {
        if hasKraken { status = "Identifiants Kraken enregistrés" }
        if hasIBKR { ibkrStatus = "Token + Query ID enregistrés" }
    }

    private func connectKraken() {
        isLoading = true
        do { try KrakenKeychainStore.save(apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines), apiSecret: apiSecret.trimmingCharacters(in: .whitespacesAndNewlines)) }
        catch { status = "Erreur de stockage"; isLoading = false; return }
        Task {
            do {
                let snapshot = try await KrakenConnectionService.refresh()
                await MainActor.run { status = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"; apiKey = ""; apiSecret = ""; isLoading = false; editingKraken = false }
            } catch { await MainActor.run { status = "Connexion refusée · \(error.localizedDescription)"; isLoading = false } }
        }
    }

    private func connectIBKR() {
        ibkrLoading = true
        do { try IBKRFlexKeychainStore.save(token: flexToken.trimmingCharacters(in: .whitespacesAndNewlines), queryID: flexQueryID.trimmingCharacters(in: .whitespacesAndNewlines)) }
        catch { ibkrStatus = "Erreur de stockage"; ibkrLoading = false; return }
        Task {
            do {
                let snapshot = try await IBKRFlexService.refresh()
                _ = try? await IBKRDividendService.refresh()
                await MainActor.run { ibkrStatus = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"; flexToken = ""; flexQueryID = ""; ibkrLoading = false; editingIBKR = false }
            } catch { await MainActor.run { ibkrStatus = "Connexion refusée · \(error.localizedDescription)"; ibkrLoading = false } }
        }
    }

    private func refreshIBKR() {
        ibkrLoading = true
        Task {
            do {
                let snapshot = try await IBKRFlexService.refresh()
                _ = try? await IBKRDividendService.refresh()
                await MainActor.run { ibkrStatus = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"; ibkrLoading = false }
            } catch { await MainActor.run { ibkrStatus = "Actualisation refusée · \(error.localizedDescription)"; ibkrLoading = false } }
        }
    }
}
