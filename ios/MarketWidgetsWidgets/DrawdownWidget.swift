import WidgetKit
import SwiftUI
import AppIntents

struct DrawdownWidgetEntry: TimelineEntry {
    let date: Date
    let symbol: String
    let snapshot: DrawdownSnapshot?
}

struct DrawdownWidgetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> DrawdownWidgetEntry {
        DrawdownWidgetEntry(
            date: Date(),
            symbol: "BTC-USD",
            snapshot: DrawdownSnapshot(symbol: "BTC-USD", price: 94_940, ath: 126_080, drawdownPercent: -24.70, updatedAt: Date())
        )
    }

    func snapshot(for configuration: AssetWidgetIntent, in context: Context) async -> DrawdownWidgetEntry {
        let symbol = normalized(configuration.symbol, fallback: "BTC-USD")
        let snapshot = try? await MarketService.drawdown(symbol: symbol)
        return DrawdownWidgetEntry(date: Date(), symbol: symbol, snapshot: snapshot)
    }

    func timeline(for configuration: AssetWidgetIntent, in context: Context) async -> Timeline<DrawdownWidgetEntry> {
        let symbol = normalized(configuration.symbol, fallback: "BTC-USD")
        let snapshot = try? await MarketService.drawdown(symbol: symbol)
        let entry = DrawdownWidgetEntry(date: Date(), symbol: symbol, snapshot: snapshot)
        return Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(30 * 60)))
    }

    private func normalized(_ value: String, fallback: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed == "^NDX" ? fallback : trimmed.uppercased()
    }
}

struct DrawdownWidgetView: View {
    let entry: DrawdownWidgetEntry

    private var green: Color { Color(red: 56/255, green: 242/255, blue: 122/255) }
    private var muted: Color { Color(red: 142/255, green: 160/255, blue: 178/255) }

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(entry.symbol) · ATH")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if let snapshot = entry.snapshot {
                    Text(String(format: "%.1f%%", snapshot.drawdownPercent))
                        .font(.system(size: 25, weight: .bold, design: .rounded))
                        .foregroundStyle(drawdownColor(snapshot.drawdownPercent))
                        .minimumScaleFactor(0.7)
                } else {
                    Text("--.-%")
                        .font(.system(size: 25, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                }
            }

            Spacer(minLength: 0)

            VStack(alignment: .trailing, spacing: 4) {
                if let snapshot = entry.snapshot {
                    Text("ATH \(formatPrice(snapshot.ath))")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                    Text("Actuel \(formatPrice(snapshot.price))")
                        .font(.caption)
                        .foregroundStyle(muted)
                } else {
                    Text("ATH --")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                    Text("Données indisponibles")
                        .font(.caption2)
                        .foregroundStyle(muted)
                }
            }
        }
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }

    private func drawdownColor(_ value: Double) -> Color {
        if value >= -5 { return green }
        if value >= -20 { return .orange }
        return .red
    }

    private func formatPrice(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(value < 1 ? 4 : 2)))
    }
}

struct ATHDrawdownWidget: Widget {
    let kind = "ATHDrawdownWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: AssetWidgetIntent.self, provider: DrawdownWidgetProvider()) { entry in
            DrawdownWidgetView(entry: entry)
        }
        .configurationDisplayName("ATH Drawdown")
        .description("Baisse de l'actif depuis son plus haut historique.")
        .supportedFamilies([.systemMedium])
    }
}
