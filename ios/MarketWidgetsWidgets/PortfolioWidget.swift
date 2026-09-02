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
    @AppStorage("portfolioTimeframe") private var timeframe = "1S"
    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)
    private let periods = ["1S", "1M", "3M", "YTD", "1A"]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("PORTFOLIO TRACKER").font(.caption2.weight(.bold)).foregroundStyle(green)
                Spacer()
                ForEach(periods, id: \.self) { period in
                    Button(intent: SetPortfolioTimeframeIntent(value: period)) {
                        Text(period).font(.system(size: 8, weight: .bold)).foregroundStyle(period == timeframe ? green : muted)
                    }.buttonStyle(.plain)
                }
            }
            HStack(alignment: .firstTextBaseline) {
                Text(entry.snapshot.totalEUR.formatted(.currency(code: "EUR"))).font(.system(size: 22, weight: .bold, design: .rounded)).foregroundStyle(.white).minimumScaleFactor(0.65)
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    Text(entry.snapshot.dayChangeEUR.formatted(.currency(code: "EUR").sign(strategy: .always())))
                    Text(String(format: "%+.2f %% · %@", entry.snapshot.dayChangePercent, timeframe))
                }.font(.caption2.weight(.semibold)).foregroundStyle(entry.snapshot.dayChangePercent >= 0 ? green : .red)
            }
            HStack(alignment: .top, spacing: 10) { ranking(title: "TOP 3", items: entry.snapshot.top3, accent: green); ranking(title: "FLOP 3", items: entry.snapshot.flop3, accent: .red) }
            Spacer(minLength: 0)
            HStack { ForEach(entry.snapshot.accounts, id: \.broker) { account in Text("\(account.broker == .ibkr ? "IBKR" : "Kraken") · \(account.valueEUR.formatted(.currency(code: "EUR")))").font(.caption2).foregroundStyle(muted).lineLimit(1).minimumScaleFactor(0.7); if account != entry.snapshot.accounts.last { Spacer() } } }
        }.containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }

    @ViewBuilder private func ranking(title: String, items: [PortfolioPositionSnapshot], accent: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) { Text(title).font(.caption2.weight(.bold)).foregroundStyle(accent); ForEach(items) { item in HStack(spacing: 3) { Text(item.symbol).fontWeight(.semibold); Text(item.valueEUR.formatted(.currency(code: "EUR").precision(.fractionLength(0)))).foregroundStyle(muted); Spacer(minLength: 2); Text(String(format: "%+.1f%%", item.dayChangePercent)).foregroundStyle(item.dayChangePercent >= 0 ? green : .red) }.font(.system(size: 9)).lineLimit(1) } }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

import AppIntents
struct SetPortfolioTimeframeIntent: AppIntent {
    static var title: LocalizedStringResource = "Changer la période"
    @Parameter(title: "Période") var value: String
    init() {}
    init(value: String) { self.value = value }
    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(value, forKey: "portfolioTimeframe")
        WidgetCenter.shared.reloadTimelines(ofKind: "PortfolioWidget")
        return .result()
    }
}

struct PortfolioWidget: Widget {
    let kind = "PortfolioWidget"
    var body: some WidgetConfiguration { StaticConfiguration(kind: kind, provider: PortfolioWidgetProvider()) { PortfolioWidgetView(entry: $0) }.configurationDisplayName("Portfolio Tracker · IBKR + Kraken").description("Valeur, performance par période et Top/Flop 3.").supportedFamilies([.systemMedium]) }
}
