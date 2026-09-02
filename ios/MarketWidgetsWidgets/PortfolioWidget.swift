import WidgetKit
import SwiftUI

struct PortfolioWidgetEntry: TimelineEntry { let date: Date; let snapshot: PortfolioSnapshot }
struct PortfolioWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> PortfolioWidgetEntry { PortfolioWidgetEntry(date: Date(), snapshot: .preview) }
    func getSnapshot(in context: Context, completion: @escaping (PortfolioWidgetEntry) -> Void) { completion(PortfolioWidgetEntry(date: Date(), snapshot: .preview)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<PortfolioWidgetEntry>) -> Void) { completion(Timeline(entries: [PortfolioWidgetEntry(date: Date(), snapshot: .preview)], policy: .after(Date().addingTimeInterval(1800)))) }
}
struct PortfolioWidgetView: View {
    let entry: PortfolioWidgetEntry
    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("PORTFOLIO · IBKR + KRAKEN").font(.caption2.weight(.bold)).foregroundStyle(green)
            Text(entry.snapshot.totalEUR.formatted(.currency(code: "EUR"))).font(.system(size: 25, weight: .bold, design: .rounded)).foregroundStyle(.white).minimumScaleFactor(0.7)
            Text(String(format: "%+.2f %% aujourd’hui", entry.snapshot.dayChangePercent)).font(.caption.weight(.semibold)).foregroundStyle(entry.snapshot.dayChangePercent >= 0 ? green : .red)
            Spacer(minLength: 0)
            ForEach(entry.snapshot.accounts, id: \.broker) { account in HStack { Text(account.broker == .ibkr ? "IBKR" : "Kraken").foregroundStyle(.white); Spacer(); Text(account.valueEUR.formatted(.currency(code: "EUR"))).foregroundStyle(muted) }.font(.caption) }
        }.containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }
}
struct PortfolioWidget: Widget {
    let kind = "PortfolioWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: PortfolioWidgetProvider()) { PortfolioWidgetView(entry: $0) }
            .configurationDisplayName("Portfolio · IBKR + Kraken")
            .description("Valeur totale et performance quotidienne de tes comptes.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
