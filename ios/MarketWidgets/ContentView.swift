import SwiftUI

struct AlertRuleIOS: Identifiable, Codable, Hashable {
    var id = UUID()
    let symbol: String
    let operatorSymbol: String
    let threshold: Double
}

struct ContentView: View {
    @State private var query = ""
    @State private var suggestions: [AssetSuggestion] = []
    @State private var selectedAsset: AssetSuggestion?
    @State private var quote: QuoteSnapshot?
    @State private var isSearching = false
    @State private var isLoadingPrice = false
    @State private var operatorSymbol = "<"
    @State private var targetText = ""
    @State private var alerts: [AlertRuleIOS] = []

    private let operators = ["<", ">", "≤", "≥"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    widgetSummary
                    alertForm
                    alertList
                }
                .padding(18)
            }
            .background(Color(red: 7/255, green: 15/255, blue: 25/255))
            .navigationBarHidden(true)
        }
        .task { loadAlerts() }
        .task(id: query) { await searchTask() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Market Widgets")
                .font(.system(size: 31, weight: .bold))
                .foregroundStyle(.white)
            Text("Tes marchés. Tes règles. Tes widgets.")
                .font(.system(size: 15))
                .foregroundStyle(muted)
        }
    }

    private var widgetSummary: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("MES WIDGETS")
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Cours marché · Widget")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text("Cours actuel, variation et configuration par ticker.")
                        .foregroundStyle(muted)
                    Divider().overlay(Color.white.opacity(0.08))
                    Text("ATH Drawdown · Widget")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text("Baisse depuis le dernier plus haut historique.")
                        .foregroundStyle(muted)
                }
            }
        }
    }

    private var alertForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("CRÉER UNE ALERTE")
            Text("Recherche un nom ou un ticker. Le cours actuel s'affiche avant l'ajout de l'alerte.")
                .font(.system(size: 13))
                .foregroundStyle(muted)

            card {
                VStack(alignment: .leading, spacing: 12) {
                    TextField("🔎  Rechercher un actif", text: $query)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .padding(12)
                        .background(inputBackground)
                        .foregroundStyle(.white)

                    if isSearching {
                        ProgressView("Recherche…")
                            .tint(green)
                            .foregroundStyle(muted)
                    }

                    if !suggestions.isEmpty && selectedAsset == nil {
                        VStack(spacing: 4) {
                            ForEach(suggestions.prefix(6)) { asset in
                                Button {
                                    select(asset)
                                } label: {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("\(asset.symbol) — \(asset.name)")
                                                .foregroundStyle(.white)
                                                .lineLimit(1)
                                            Text([asset.type, asset.exchange].filter { !$0.isEmpty }.joined(separator: " · "))
                                                .font(.caption)
                                                .foregroundStyle(muted)
                                        }
                                        Spacer()
                                    }
                                    .padding(.vertical, 8)
                                }
                            }
                        }
                    }

                    if let asset = selectedAsset {
                        VStack(alignment: .leading, spacing: 5) {
                            Text("✓ \(asset.name) · \(asset.symbol)")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(green)
                            if isLoadingPrice {
                                ProgressView("Cours actuel…")
                                    .tint(green)
                            } else if let quote {
                                HStack(alignment: .firstTextBaseline, spacing: 10) {
                                    Text(formatPrice(quote.price))
                                        .font(.system(size: 27, weight: .bold, design: .rounded))
                                        .foregroundStyle(.white)
                                    if let change = quote.changePercent {
                                        Text(String(format: "%+.2f%%", change))
                                            .font(.headline)
                                            .foregroundStyle(change >= 0 ? green : .red)
                                    }
                                }
                                Text("Cours récupéré avant création de l'alerte")
                                    .font(.caption)
                                    .foregroundStyle(muted)
                            } else {
                                Text("Cours indisponible pour le moment")
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }

                    HStack(spacing: 8) {
                        Picker("Condition", selection: $operatorSymbol) {
                            ForEach(operators, id: \.self) { Text($0) }
                        }
                        .pickerStyle(.menu)
                        .frame(width: 82)
                        .padding(.vertical, 6)
                        .background(inputBackground)

                        TextField("Prix cible", text: $targetText)
                            .keyboardType(.decimalPad)
                            .padding(12)
                            .background(inputBackground)
                            .foregroundStyle(.white)
                    }

                    Button(action: addAlert) {
                        Text("+ AJOUTER L'ALERTE")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .foregroundStyle(Color(red: 7/255, green: 17/255, blue: 28/255))
                            .background(green, in: RoundedRectangle(cornerRadius: 13))
                    }
                    .disabled(selectedAsset == nil || Double(targetText.replacingOccurrences(of: ",", with: ".")) == nil)
                    .opacity(selectedAsset == nil ? 0.55 : 1)
                }
            }
        }
    }

    private var alertList: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("MES ALERTES")
            if alerts.isEmpty {
                card {
                    Text("Aucune alerte pour le moment\nCrée ta première condition ci-dessus.")
                        .foregroundStyle(muted)
                }
            } else {
                ForEach(alerts) { alert in
                    card {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(alert.symbol)
                                    .font(.headline)
                                    .foregroundStyle(.white)
                                Text("\(alert.operatorSymbol) \(formatPrice(alert.threshold))")
                                    .foregroundStyle(muted)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                alerts.removeAll { $0.id == alert.id }
                                saveAlerts()
                            } label: {
                                Image(systemName: "trash")
                            }
                        }
                    }
                }
            }
        }
    }

    private func searchTask() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            suggestions = []
            selectedAsset = nil
            quote = nil
            return
        }
        if selectedAsset?.symbol.caseInsensitiveCompare(text) == .orderedSame { return }
        selectedAsset = nil
        quote = nil
        isSearching = true
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        do {
            suggestions = try await MarketService.search(text)
        } catch {
            suggestions = []
        }
        isSearching = false
    }

    private func select(_ asset: AssetSuggestion) {
        selectedAsset = asset
        suggestions = []
        query = asset.symbol
        quote = nil
        isLoadingPrice = true
        Task {
            let result = try? await MarketService.quote(symbol: asset.symbol)
            await MainActor.run {
                if selectedAsset?.symbol == asset.symbol { quote = result }
                isLoadingPrice = false
            }
        }
    }

    private func addAlert() {
        guard let asset = selectedAsset,
              let threshold = Double(targetText.replacingOccurrences(of: ",", with: ".")),
              threshold > 0 else { return }
        let normalizedOperator = operatorSymbol == "≤" ? "<=" : operatorSymbol == "≥" ? ">=" : operatorSymbol
        alerts.insert(AlertRuleIOS(symbol: asset.symbol, operatorSymbol: normalizedOperator, threshold: threshold), at: 0)
        saveAlerts()
        targetText = ""
        query = ""
        selectedAsset = nil
        quote = nil
    }

    private func saveAlerts() {
        if let data = try? JSONEncoder().encode(alerts) {
            UserDefaults.standard.set(data, forKey: "ios_alert_rules")
        }
    }

    private func loadAlerts() {
        guard let data = UserDefaults.standard.data(forKey: "ios_alert_rules"),
              let saved = try? JSONDecoder().decode([AlertRuleIOS].self, from: data) else { return }
        alerts = saved
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(green)
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(red: 14/255, green: 25/255, blue: 39/255), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 28/255, green: 47/255, blue: 65/255)))
    }

    private var inputBackground: some ShapeStyle {
        Color(red: 9/255, green: 20/255, blue: 32/255)
    }

    private var green: Color { Color(red: 56/255, green: 242/255, blue: 122/255) }
    private var muted: Color { Color(red: 142/255, green: 160/255, blue: 178/255) }

    private func formatPrice(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(value < 1 ? 4 : 2)))
    }
}
