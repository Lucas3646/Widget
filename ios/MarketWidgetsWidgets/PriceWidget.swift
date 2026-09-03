import WidgetKit
import SwiftUI
import AppIntents

struct PriceWidgetEntry: TimelineEntry {
    let date: Date
    let symbol: String
    let timeframe: String
    let quote: QuoteSnapshot?
}

struct PriceWidgetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PriceWidgetEntry {
        PriceWidgetEntry(date: Date(), symbol: "^NDX", timeframe: "1D", quote: QuoteSnapshot(symbol: "^NDX", name: "NASDAQ 100", price: 23_500.00, previousClose: 23_300.00, changePercent: 0.86, updatedAt: Date()))
    }

    func snapshot(for configuration: AssetWidgetIntent, in context: Context) async -> PriceWidgetEntry {
        let symbol = normalized(configuration.symbol)
        let timeframe = configuration.timeframe.rawValue
        let quote = try? await MarketService.quote(symbol: symbol, timeframe: timeframe)
        return PriceWidgetEntry(date: Date(), symbol: symbol, timeframe: timeframe, quote: quote)
    }

    func timeline(for configuration: AssetWidgetIntent, in context: Context) async -> Timeline<PriceWidgetEntry> {
        let symbol = normalized(configuration.symbol)
        let timeframe = configuration.timeframe.rawValue
        let quote = try? await MarketService.quote(symbol: symbol, timeframe: timeframe)
        let entry = PriceWidgetEntry(date: Date(), symbol: symbol, timeframe: timeframe, quote: quote)
        return Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60)))
    }

    private func normalized(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "^NDX" : trimmed.uppercased()
    }
}

struct PriceWidgetView: View {
    let entry: PriceWidgetEntry
    private var green: Color { Color(red: 56/255, green: 242/255, blue: 122/255) }
    private var muted: Color { Color(red: 142/255, green: 160/255, blue: 178/255) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.quote?.name ?? entry.symbol).font(.caption.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                    Text("\(entry.symbol) · \(entry.timeframe)").font(.caption2).foregroundStyle(muted)
                }
                Spacer()
                Text(entry.date, style: .time).font(.caption2).foregroundStyle(muted)
            }
            Spacer(minLength: 0)
            if let quote = entry.quote {
                Text(formatPrice(quote.price)).font(.system(size: 28, weight: .bold, design: .rounded)).foregroundStyle(.white).minimumScaleFactor(0.75)
                HStack(spacing: 8) {
                    if let change = quote.changePercent {
                        Text(String(format: "%+.2f%%", change)).font(.subheadline.weight(.bold)).foregroundStyle(change >= 0 ? green : .red)
                    }
                    if let baseline = quote.previousClose {
                        Text(String(format: "%+.2f", quote.price - baseline)).font(.caption).foregroundStyle(muted)
                    }
                }
            } else {
                Text("Cours indisponible").font(.headline).foregroundStyle(.white)
                Text("Réessaie plus tard").font(.caption).foregroundStyle(muted)
            }
            Spacer(minLength: 0)
            Text("Market Widgets").font(.caption2).foregroundStyle(muted)
        }
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }

    private func formatPrice(_ value: Double) -> String { value.formatted(.number.precision(.fractionLength(value < 1 ? 4 : 2))) }
}

struct MarketPriceWidget: Widget {
    let kind = "MarketPriceWidget"
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: AssetWidgetIntent.self, provider: PriceWidgetProvider()) { entry in PriceWidgetView(entry: entry) }
            .configurationDisplayName("Cours marché")
            .description("Cours, variation et timeframe de l'actif choisi.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
