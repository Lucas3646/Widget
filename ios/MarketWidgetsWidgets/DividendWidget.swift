import WidgetKit
import SwiftUI

struct DividendWidgetEntry: TimelineEntry {
    let date: Date
    let dividend: IBKRDividendWidgetSnapshot?
}

struct DividendWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> DividendWidgetEntry {
        DividendWidgetEntry(
            date: Date(),
            dividend: IBKRDividendWidgetSnapshot(
                symbol: "KO",
                amount: 12.75,
                currency: "USD",
                date: Calendar.current.date(byAdding: .day, value: 3, to: Date()) ?? Date(),
                daysUntil: 3,
                receivedYTDEUR: 84.20,
                remainingYearEUR: 41.60,
                isUpcoming: true,
                estimatedUpcoming: true
            )
        )
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
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.dividend?.isUpcoming == true ? "PROCHAIN DIVIDENDE" : "DIVIDENDES · IBKR")
                .font(.system(size: 9, weight: .bold)).foregroundStyle(green).lineLimit(1)
            if let dividend = entry.dividend {
                HStack(alignment: .firstTextBaseline) {
                    Text(dividend.symbol).font(.title3.weight(.bold)).foregroundStyle(.white).lineLimit(1)
                    Spacer()
                    Text(dividend.isUpcoming ? "J-\(max(dividend.daysUntil, 0))" : "—")
                        .font(.subheadline.weight(.bold)).foregroundStyle(green)
                }
                Text(dividend.amount.formatted(.currency(code: dividend.currency)))
                    .font(.subheadline.weight(.bold)).foregroundStyle(.white).lineLimit(1)
                Text(
                    dividend.isUpcoming
                        ? "\(dividend.estimatedUpcoming ? "Estimé" : "Prévu") · \(dividend.date.formatted(.dateTime.day().month(.abbreviated)))"
                        : "Dernier · \(dividend.date.formatted(.dateTime.day().month(.abbreviated)))"
                )
                    .font(.system(size: 9)).foregroundStyle(muted).lineLimit(1)
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("REÇU YTD").font(.system(size: 7, weight: .bold)).foregroundStyle(muted)
                        Text(dividend.receivedYTDEUR.formatted(.currency(code: "EUR"))).font(.system(size: 10, weight: .bold)).foregroundStyle(green).lineLimit(1)
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        Text("RESTANT").font(.system(size: 7, weight: .bold)).foregroundStyle(muted)
                        Text(dividend.remainingYearEUR.formatted(.currency(code: "EUR"))).font(.system(size: 10, weight: .bold)).foregroundStyle(.white).lineLimit(1)
                    }
                }
                Text(dividend.estimatedUpcoming ? "Estimation via historique IBKR" : "Déclaré par IBKR")
                    .font(.system(size: 7)).foregroundStyle(muted).lineLimit(1)
            } else {
                Text("Aucune donnée dividende").font(.subheadline.weight(.bold)).foregroundStyle(.white)
                Text("Historique IBKR requis")
                    .font(.caption2).foregroundStyle(muted)
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
            .description("Format compact : prochain dividende, J-x, reçu YTD et restant.")
            .supportedFamilies([.systemSmall])
    }
}
