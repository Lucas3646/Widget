import WidgetKit
import SwiftUI

struct DividendWidgetEntry: TimelineEntry {
    let date: Date
    let dividend: DividendSnapshot
}

struct DividendWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> DividendWidgetEntry {
        DividendWidgetEntry(date: Date(), dividend: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (DividendWidgetEntry) -> Void) {
        completion(DividendWidgetEntry(date: Date(), dividend: .preview))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DividendWidgetEntry>) -> Void) {
        let entry = DividendWidgetEntry(date: Date(), dividend: .preview)
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(6 * 60 * 60))))
    }
}

struct DividendWidgetView: View {
    let entry: DividendWidgetEntry

    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("PROCHAIN DIVIDENDE")
                .font(.caption2.weight(.bold))
                .foregroundStyle(green)

            Text("\(entry.dividend.companyName) · \(entry.dividend.symbol)")
                .font(.headline)
                .foregroundStyle(.white)
                .lineLimit(1)

            Text("\(entry.dividend.amountPerShare.formatted(.number.precision(.fractionLength(2)))) \(entry.dividend.currency) / action")
                .font(.caption)
                .foregroundStyle(muted)

            Text(entry.dividend.exDividendDate, format: .dateTime.day().month(.abbreviated))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)

            if let estimated = entry.dividend.estimatedAmount {
                Text("Estimation · \(estimated.formatted(.number.precision(.fractionLength(2)))) \(entry.dividend.currency)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(green)
            }
        }
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }
}

struct DividendWidget: Widget {
    let kind = "DividendWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DividendWidgetProvider()) { entry in
            DividendWidgetView(entry: entry)
        }
        .configurationDisplayName("Prochain dividende")
        .description("Affiche le prochain dividende attendu sur ton portefeuille.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
