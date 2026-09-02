import WidgetKit
import SwiftUI

struct MacroWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: MacroWidgetSnapshot?
}

struct MacroTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> MacroWidgetEntry {
        MacroWidgetEntry(date: Date(), snapshot: MacroWidgetSnapshot(title: "CPI", country: "US", eventAt: Date().addingTimeInterval(7200), actual: nil, forecast: "2.8%", previous: "3.0%", importance: 3, released: false))
    }

    func getSnapshot(in context: Context, completion: @escaping (MacroWidgetEntry) -> Void) {
        completion(MacroWidgetEntry(date: Date(), snapshot: MacroService.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MacroWidgetEntry>) -> Void) {
        Task {
            let fresh = try? await MacroService.refresh()
            let entry = MacroWidgetEntry(date: Date(), snapshot: fresh ?? MacroService.cached())
            completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
        }
    }
}

struct MacroWidgetView: View {
    let entry: MacroWidgetEntry
    private let green = Color(red: 56/255, green: 242/255, blue: 122/255)
    private let muted = Color(red: 142/255, green: 160/255, blue: 178/255)

    private func countdown(_ snapshot: MacroWidgetSnapshot) -> String {
        if snapshot.released { return "PUBLIÉ" }
        let delta = snapshot.eventAt.timeIntervalSinceNow
        if delta <= 0 { return "MAINT." }
        if delta < 3600 { return "\(max(Int(delta / 60), 1)) min" }
        if delta < 86400 { return "H-\(max(Int(delta / 3600), 1))" }
        return "J-\(max(Int(delta / 86400), 1))"
    }

    var body: some View {
        VStack(spacing: 5) {
            if let s = entry.snapshot {
                Text("MACRO · \(s.country.uppercased().prefix(3))")
                    .font(.system(size: 9, weight: .bold)).foregroundStyle(green)
                Text(s.title)
                    .font(.headline.weight(.bold)).foregroundStyle(.white).lineLimit(1)
                Text("\(countdown(s)) · \(s.eventAt.formatted(date: .omitted, time: .shortened))")
                    .font(.caption2.weight(.bold)).foregroundStyle(.white)
                Spacer(minLength: 2)
                Text("Att. \(s.forecast ?? "—")")
                    .font(.caption2).foregroundStyle(muted)
                Text("Rés. \(s.actual ?? "—")")
                    .font(.caption.weight(.bold)).foregroundStyle(s.actual == nil ? .white : green)
                Text("Préc. \(s.previous ?? "—")")
                    .font(.system(size: 8)).foregroundStyle(muted)
            } else {
                Text("MACRO").font(.caption2.weight(.bold)).foregroundStyle(green)
                Text("Chargement…").font(.headline).foregroundStyle(.white)
                Text("Consensus + résultat").font(.caption2).foregroundStyle(muted)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .containerBackground(Color(red: 7/255, green: 15/255, blue: 25/255), for: .widget)
    }
}

struct MacroWidget: Widget {
    let kind = "MacroWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: MacroTimelineProvider()) { MacroWidgetView(entry: $0) }
            .configurationDisplayName("Macro · prochain événement")
            .description("Prochain événement macro, consensus, précédent et résultat publié.")
            .supportedFamilies([.systemSmall])
    }
}
