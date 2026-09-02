import Foundation
import WidgetKit

struct MacroWidgetSnapshot: Codable, Hashable {
    let title: String
    let country: String
    let eventAt: Date
    let actual: String?
    let forecast: String?
    let previous: String?
    let importance: Int
    let released: Bool
}

private struct MacroCalendarEvent {
    let title: String
    let country: String
    let eventAt: Date
    let actual: String?
    let forecast: String?
    let previous: String?
    let importance: Int
}

enum MacroService {
    private static let cacheKey = "macroWidgetSnapshot"
    private static let endpoint = "https://economic-calendar.tradingview.com/events"
    private static let majorKeywords = [
        "cpi", "inflation", "pce", "nonfarm", "non-farm", "payroll", "unemployment",
        "fomc", "fed interest", "interest rate decision", "federal funds", "ecb", "bce",
        "gdp", "retail sales", "ism", "pmi"
    ]

    static func cached() -> MacroWidgetSnapshot? {
        guard let data = UserDefaults.standard.data(forKey: cacheKey) else { return nil }
        return try? JSONDecoder().decode(MacroWidgetSnapshot.self, from: data)
    }

    static func refresh() async throws -> MacroWidgetSnapshot {
        let now = Date()
        let events = try await fetchEvents(from: now.addingTimeInterval(-3 * 3600), to: now.addingTimeInterval(10 * 86400))
        guard !events.isEmpty else { throw URLError(.cannotParseResponse) }

        let major = events.filter { event in
            event.importance >= 3 && majorKeywords.contains { event.title.lowercased().contains($0) }
        }
        let high = events.filter { $0.importance >= 3 }
        let pool = !major.isEmpty ? major : (!high.isEmpty ? high : events)

        let recentReleased = pool
            .filter { $0.actual != nil && $0.eventAt <= now && now.timeIntervalSince($0.eventAt) <= 2 * 3600 }
            .max { $0.eventAt < $1.eventAt }
        let next = pool.filter { $0.eventAt >= now }.min { $0.eventAt < $1.eventAt }
        guard let selected = recentReleased ?? next ?? pool.max(by: { $0.eventAt < $1.eventAt }) else {
            throw URLError(.resourceUnavailable)
        }

        let snapshot = MacroWidgetSnapshot(
            title: compactTitle(selected.title),
            country: selected.country,
            eventAt: selected.eventAt,
            actual: selected.actual,
            forecast: selected.forecast,
            previous: selected.previous,
            importance: selected.importance,
            released: selected.actual != nil && selected.eventAt <= now
        )
        if let data = try? JSONEncoder().encode(snapshot) { UserDefaults.standard.set(data, forKey: cacheKey) }
        WidgetCenter.shared.reloadTimelines(ofKind: "MacroWidget")
        return snapshot
    }

    private static func fetchEvents(from: Date, to: Date) async throws -> [MacroCalendarEvent] {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var components = URLComponents(string: endpoint)!
        components.queryItems = [
            .init(name: "from", value: iso.string(from: from)),
            .init(name: "to", value: iso.string(from: to)),
            .init(name: "countries", value: "US,EU,GB,DE,FR")
        ]
        var request = URLRequest(url: components.url!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("https://www.tradingview.com", forHTTPHeaderField: "Origin")
        request.setValue("https://www.tradingview.com/economic-calendar/", forHTTPHeaderField: "Referer")
        request.setValue("Mozilla/5.0 MarketWidgets iOS", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = (root["result"] ?? root["data"]) as? [[String: Any]] else { throw URLError(.cannotParseResponse) }

        let fallbackISO = ISO8601DateFormatter()
        return rows.compactMap { row in
            guard let title = firstText(row, keys: ["title", "event", "indicator", "category"]),
                  let rawDate = firstText(row, keys: ["date", "datetime", "time"]),
                  let date = iso.date(from: rawDate) ?? fallbackISO.date(from: rawDate) else { return nil }
            let importance: Int
            if let n = row["importance"] as? NSNumber { importance = n.intValue }
            else if let s = row["importance"] as? String {
                switch s.lowercased() { case "high": importance = 3; case "medium": importance = 2; case "low": importance = 1; default: importance = Int(s) ?? 0 }
            } else { importance = 0 }
            return MacroCalendarEvent(
                title: title,
                country: firstText(row, keys: ["country", "countryCode", "currency"]) ?? "",
                eventAt: date,
                actual: clean(row["actual"]),
                forecast: clean(row["forecast"]),
                previous: clean(row["previous"]),
                importance: importance
            )
        }
    }

    private static func firstText(_ row: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = row[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.lowercased() != "null" { return value }
        }
        return nil
    }

    private static func clean(_ value: Any?) -> String? {
        guard let value, !(value is NSNull) else { return nil }
        let text = String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty || text == "-" || text.lowercased() == "null" ? nil : text
    }

    private static func compactTitle(_ raw: String) -> String {
        let l = raw.lowercased()
        if l.contains("nonfarm") || l.contains("non-farm") || l.contains("payroll") { return "NFP" }
        if l.contains("core pce") { return "CORE PCE" }
        if l.contains("pce") { return "PCE" }
        if l.contains("core cpi") { return "CORE CPI" }
        if l.contains("cpi") || l.contains("consumer price") { return "CPI" }
        if l.contains("fomc") || l.contains("fed interest") || l.contains("federal funds") { return "FED" }
        if l.contains("ecb") || l.contains("bce") { return "BCE" }
        if l.contains("gdp") { return "PIB" }
        if l.contains("unemployment") { return "CHÔMAGE" }
        if l.contains("retail sales") { return "VENTES" }
        if l.contains("ism") { return "ISM" }
        if l.contains("pmi") { return "PMI" }
        return String(raw.prefix(20)).uppercased()
    }
}
