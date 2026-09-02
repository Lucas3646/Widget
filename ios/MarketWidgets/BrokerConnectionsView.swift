import SwiftUI

struct BrokerConnectionsView: View {
    @State private var apiKey = ""
    @State private var apiSecret = ""
    @State private var status = "Non connecté"
    @State private var isLoading = false

    var body: some View {
        Form {
            Section("Kraken Pro") {
                TextField("API Key", text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Private Key / Secret", text: $apiSecret)
                Text(status)
                    .foregroundStyle(status.hasPrefix("Connecté") ? .green : .secondary)
                Button(isLoading ? "Vérification…" : "Connecter Kraken") {
                    connectKraken()
                }
                .disabled(isLoading || apiKey.isEmpty || apiSecret.isEmpty)
                Button("Supprimer Kraken", role: .destructive) {
                    KrakenKeychainStore.clear()
                    status = "Non connecté"
                }
                Text("Utilise une clé Kraken dédiée en lecture seule. Les identifiants sont stockés dans le Trousseau iOS.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Interactive Brokers") {
                Text("La connexion retail IBKR via Client Portal Gateway nécessite une authentification navigateur quotidienne. Aucun mot de passe IBKR n’est stocké par Market Widgets.")
                    .font(.caption)
                Link("Ouvrir la configuration IBKR", destination: URL(string: "https://www.interactivebrokers.com/docs/web-api/getting-started")!)
            }
        }
        .navigationTitle("Comptes connectés")
        .preferredColorScheme(.dark)
    }

    private func connectKraken() {
        isLoading = true
        do {
            try KrakenKeychainStore.save(apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines), apiSecret: apiSecret.trimmingCharacters(in: .whitespacesAndNewlines))
        } catch {
            status = "Erreur de stockage"
            isLoading = false
            return
        }
        Task {
            do {
                let snapshot = try await KrakenConnectionService.refresh()
                await MainActor.run {
                    status = "Connecté · \(snapshot.totalEUR.formatted(.currency(code: "EUR")))"
                    apiKey = ""
                    apiSecret = ""
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    status = "Connexion refusée · \(error.localizedDescription)"
                    isLoading = false
                }
            }
        }
    }
}
