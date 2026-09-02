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
                isUpcoming: true
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
        VStack(alignment: .leading, spacing: 5) {
            Text(entry.dividend?.isUpcoming == true ? "PROCHAIN DIVIDENDE · IBKR" : "DIVIDENDES · IBKR")
                .font(.caption2.weight(.bold)).foregroundStyle(green)
            if let dividend = entry.dividend {
                HStack(alignment: .firstTextBaseline) {
                    Text(dividend.symbol).font(.title2.weight(.bold)).foregroundStyle(.white).lineLimit(1)
                    Spacer()
                    Text(dividend.isUpcoming ? "J-\(max(dividend.daysUntil, 0))" : "—")
                        .font(.headline.weight(.bold)).foregroundStyle(green)
                }
                Text(dividend.amount.formatted(.currency(code: dividend.currency)))
                    .font(.headline.weight(.bold)).foregroundStyle(.white)
                Text(dividend.isUpcoming ? "Prévu le \(dividend.date.formatted(.dateTime.day().month(.abbreviated)))" : "Dernier reçu · \(dividend.date.formatted(.dateTime.day().month(.abbreviated)))")
                    .font(.caption).foregroundStyle(muted)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("REÇU YTD").font(.system(size: 8, weight: .bold)).foregroundStyle(muted)
                        Text(dividend.receivedYTDEUR.formatted(.currency(code: "EUR"))).font(.caption.weight(.bold)).foregroundStyle(green)
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        Text("RESTANT 31/12").font(.system(size: 8, weight: .bold)).foregroundStyle(muted)
                        Text(dividend.remainingYearEUR.formatted(.currency(code: "EUR"))).font(.caption.weight(.bold)).foregroundStyle(.white)
                    }
                }
                Text("Restant = dividendes déjà déclarés par IBKR")
                    .font(.system(size: 8)).foregroundStyle(muted)
            } else {
                Text("Aucune donnée dividende").font(.headline).foregroundStyle(.white)
                Text("La Flex Query doit inclure Open Dividend Accruals + Cash Transactions")
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
            .description("Prochain dividende, J-x, reçu YTD et restant déclaré jusqu’au 31/12.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
