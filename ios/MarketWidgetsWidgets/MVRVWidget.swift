import WidgetKit
import SwiftUI

struct MVRVWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: MVRVWidgetSnapshot?
}

struct MVRVTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> MVRVWidgetEntry {
        MVRVWidgetEntry(date: Date(), snapshot: MVRVWidgetSnapshot(zScore: 2.14, price: 104250, updatedAt: Date()))
    }

    func getSnapshot(in context: Context, completion: @escaping (MVRVWidgetEntry) -> Void) {
        completion(MVRVWidgetEntry(date: Date(), snapshot: MVRVService.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MVRVWidgetEntry>) -> Void) {
        Task {
            let fresh = try? await MVRVService.refresh()
            completion(Timeline(entries: [MVRVWidgetEntry(date: Date(), snapshot: fresh ?? MVRVService.cached())], policy: .after(Date().addingTimeInterval(6 * 3600))))
        }
    }
}

struct MVRVWidgetView: View {
    let entry: MVRVWidgetEntry
    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let blue = Color(red: 80/255, green: 191/255, blue: 255/255)
    private let orange = Color(red: 255/255, green: 188/255, blue: 66/255)
    private let red = Color(red: 255/255, green: 82/255, blue: 82/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)

    private func accent(_ z: Double) -> Color {
        if z >= 7 { return red }
        if z >= 5 { return orange }
        if z < 0 { return green }
        return blue
    }

    var body: some View {
        HStack {
            if let s = entry.snapshot {
                VStack(alignment: .leading, spacing: 2) {
                    Text("BTC · MVRV").font(.caption2.weight(.bold)).foregroundStyle(muted)
                    Text("Z \(s.zScore, specifier: "%.2f")")
                        .font(.title2.weight(.bold)).foregroundStyle(accent(s.zScore))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(MVRVService.zone(s.zScore)).font(.caption.weight(.bold)).foregroundStyle(.white)
                    if let price = s.price {
                        Text("BTC $\(Int(price))").font(.caption2).foregroundStyle(muted)
                    }
                }
            } else {
                Text("MVRV indisponible").font(.caption).foregroundStyle(muted)
            }
        }
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }
}

struct MVRVWidget: Widget {
    let kind = "MVRVWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: MVRVTimelineProvider()) { MVRVWidgetView(entry: $0) }
            .configurationDisplayName("BTC MVRV Z-Score")
            .description("MVRV Z-Score Bitcoin en format compact.")
            .supportedFamilies([.systemMedium])
    }
}
