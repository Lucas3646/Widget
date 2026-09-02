import WidgetKit
import SwiftUI

struct DividendWidgetEntry: TimelineEntry {
    let date: Date
    let dividend: IBKRDividendWidgetSnapshot?
}

struct DividendWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> DividendWidgetEntry {
        DividendWidgetEntry(date: Date(), dividend: IBKRDividendWidgetSnapshot(symbol: "KO", description: "Dividende attendu", amount: 12.75, currency: "USD", date: Calendar.current.date(byAdding: .day, value: 11, to: Date()) ?? Date(), isUpcoming: true))
    }

    func getSnapshot(in context: Context, completion: @escaping (DividendWidgetEntry) -> Void) {
        completion(DividendWidgetEntry(date: Date(), dividend: IBKRDividendService.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DividendWidgetEntry>) -> Void) {
        let entry = DividendWidgetEntry(date: Date(), dividend: IBKRDividendService.cached())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(3600))))
    }
}

struct DividendWidgetView: View {
    let entry: DividendWidgetEntry
    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.dividend?.isUpcoming == true ? "PROCHAIN DIVIDENDE · IBKR" : "DIVIDENDES · IBKR")
                .font(.caption2.weight(.bold)).foregroundStyle(green)
            if let dividend = entry.dividend {
                Text(dividend.symbol).font(.headline).foregroundStyle(.white).lineLimit(1)
                Text(dividend.amount.formatted(.currency(code: dividend.currency)))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                Text(dividend.isUpcoming ? "Prévu le \(dividend.date.formatted(date: .abbreviated, time: .omitted))" : "Reçu le \(dividend.date.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption).foregroundStyle(muted)
                Text(dividend.description.isEmpty ? (dividend.isUpcoming ? "Dividende attendu" : "Dernier dividende reçu") : dividend.description)
                    .font(.caption2).foregroundStyle(muted).lineLimit(2)
            } else {
                Text("Aucun dividende").font(.headline).foregroundStyle(.white)
                Text("Actualise IBKR dans Market Widgets")
                    .font(.caption).foregroundStyle(muted)
            }
        }
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }
}

struct DividendWidget: Widget {
    let kind = "DividendWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DividendWidgetProvider()) { DividendWidgetView(entry: $0) }
            .configurationDisplayName("Dividendes · IBKR")
            .description("Affiche le prochain dividende attendu ou le dernier dividende reçu sur IBKR.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
