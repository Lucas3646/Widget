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

    var body: some View {
        Form {
            Section("Kraken Pro") {
                TextField("API Key", text: $apiKey).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Private Key / Secret", text: $apiSecret)
                Text(status).foregroundStyle(status.hasPrefix("Connecté") ? .green : .secondary)
                Button(isLoading ? "Vérification…" : "Connecter Kraken") { connectKraken() }.disabled(isLoading || apiKey.isEmpty || apiSecret.isEmpty)
                Button("Supprimer Kraken", role: .destructive) { KrakenKeychainStore.clear(); status = "Non connecté" }
                Text("Clé Kraken dédiée en lecture seule. Stockage dans le Trousseau iOS.").font(.caption).foregroundStyle(.secondary)
            }

            Section("Interactive Brokers · Flex") {
                Text("Flex fournit positions et historique. Market Widgets actualise ensuite les prix de marché sans Gateway ni reconnexion quotidienne.")
                    .font(.caption).foregroundStyle(.secondary)
                SecureField("Flex Web Service Token", text: $flexToken)
                TextField("Activity Flex Query ID", text: $flexQueryID).keyboardType(.numberPad)
                Text(ibkrStatus).foregroundStyle(ibkrStatus.hasPrefix("Connecté") ? .green : .secondary)
                Button(ibkrLoading ? "Vérification…" : "Connecter IBKR") { connectIBKR() }.disabled(ibkrLoading || flexToken.isEmpty || flexQueryID.isEmpty)
                Button("Supprimer IBKR", role: .destructive) { IBKRFlexKeychainStore.clear(); ibkrStatus = "Non connecté" }
                Text("Le token reste dans le Trousseau iOS. Ne le stocke pas dans GitHub.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Comptes connectés")
        .preferredColorScheme(.dark)
    }

    private func connectKraken() {
        isLoading = true
        do { try KrakenKeychainStore.save(apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines), apiSecret: apiSecret.trimmingCharacters(in: .whitespacesAndNewlines)) }
        catch { status = "Erreur de stockage"; isLoading = false; return }
        Task {
            do {
                let snapshot = try await KrakenConnectionService.refresh()
                await MainActor.run { status = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"; apiKey = ""; apiSecret = ""; isLoading = false }
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
                await MainActor.run { ibkrStatus = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"; flexToken = ""; flexQueryID = ""; ibkrLoading = false }
            } catch { await MainActor.run { ibkrStatus = "Connexion refusée · \(error.localizedDescription)"; ibkrLoading = false } }
        }
    }
}
