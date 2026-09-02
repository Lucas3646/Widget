import Foundation
import WidgetKit

struct IBKRDividendWidgetSnapshot: Codable, Hashable {
    let symbol: String
    let amount: Double
    let currency: String
    let date: Date
    let daysUntil: Int
    let receivedYTDEUR: Double
    let remainingYearEUR: Double
    let isUpcoming: Bool
}

private struct IBKRDividendRow {
    let symbol: String
    let amount: Double
    let currency: String
    let date: Date
    let upcoming: Bool
}

private final class IBKRDividendXMLParser: NSObject, XMLParserDelegate {
    var rows: [IBKRDividendRow] = []

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes a: [String : String] = [:]) {
        if elementName == "OpenDividendAccrual", let row = Self.row(from: a, upcoming: true) {
            rows.append(row)
        } else if elementName == "CashTransaction" {
            let haystack = [a["type"], a["transactionType"], a["description"], a["activityDescription"]]
                .compactMap { $0 }.joined(separator: " ").lowercased()
            if (haystack.contains("dividend") || haystack.contains("dividende")), let row = Self.row(from: a, upcoming: false) {
                rows.append(row)
            }
        }
    }

    private static func row(from a: [String: String], upcoming: Bool) -> IBKRDividendRow? {
        let symbol = a["symbol"] ?? ""
        let amountKeys = upcoming ? ["grossAmount", "accruedAmount", "amount", "netAmount"] : ["amount", "netAmount", "grossAmount", "proceeds"]
        let amount = amountKeys.compactMap { key in a[key].flatMap { Double($0.replacingOccurrences(of: ",", with: "")) } }.first ?? 0
        let currency = a["currency"] ?? a["fxCurrency"] ?? "EUR"
        let dateKeys = upcoming ? ["payDate", "exDate", "date", "reportDate"] : ["dateTime", "date", "settleDate", "reportDate"]
        guard let rawDate = dateKeys.compactMap({ a[$0] }).first, let date = parseDate(rawDate) else { return nil }
        guard !symbol.isEmpty || amount != 0 else { return nil }
        return IBKRDividendRow(symbol: symbol, amount: amount, currency: currency, date: date, upcoming: upcoming)
    }

    private static func parseDate(_ raw: String) -> Date? {
        let clean = raw.components(separatedBy: ";").first?.components(separatedBy: " ").first ?? raw
        for format in ["yyyyMMdd", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy"] {
            let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = format
            if let d = f.date(from: clean) { return d }
        }
        return nil
    }
}

enum IBKRDividendService {
    private static let base = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
    private static let cacheKey = "ibkrDividendSnapshot"

    static func cached() -> IBKRDividendWidgetSnapshot? {
        guard let data = UserDefaults.standard.data(forKey: cacheKey) else { return nil }
        return try? JSONDecoder().decode(IBKRDividendWidgetSnapshot.self, from: data)
    }

    static func refresh() async throws -> IBKRDividendWidgetSnapshot? {
        guard let (token, queryID) = IBKRFlexKeychainStore.credentials() else {
            throw NSError(domain: "IBKR", code: 20, userInfo: [NSLocalizedDescriptionKey: "Token Flex ou Query ID absent"])
        }
        let xml = try await fetchStatement(token: token, queryID: queryID)
        let parser = IBKRDividendXMLParser(); let xp = XMLParser(data: Data(xml.utf8)); xp.delegate = parser
        guard xp.parse() else { throw URLError(.cannotParseResponse) }

        let calendar = Calendar.current
        let now = Date()
        let startOfToday = calendar.startOfDay(for: now)
        let year = calendar.component(.year, from: now)
        let yearStart = calendar.date(from: DateComponents(year: year, month: 1, day: 1)) ?? now
        let yearEnd = calendar.date(from: DateComponents(year: year, month: 12, day: 31, hour: 23, minute: 59, second: 59)) ?? now
        let upcoming = parser.rows.filter { $0.upcoming && $0.date >= startOfToday && $0.date <= yearEnd }.sorted { $0.date < $1.date }
        let received = parser.rows.filter { !$0.upcoming && $0.date >= yearStart && $0.date <= now }
        let next = upcoming.first

        var receivedYTD = 0.0
        for row in received { receivedYTD += row.amount * (await fxToEUR(row.currency)) }
        var remaining = 0.0
        for row in upcoming { remaining += row.amount * (await fxToEUR(row.currency)) }

        guard let source = next ?? received.max(by: { $0.date < $1.date }) else {
            UserDefaults.standard.removeObject(forKey: cacheKey)
            WidgetCenter.shared.reloadTimelines(ofKind: "DividendWidget")
            return nil
        }
        let days = next == nil ? 0 : max(calendar.dateComponents([.day], from: startOfToday, to: calendar.startOfDay(for: source.date)).day ?? 0, 0)
        let snapshot = IBKRDividendWidgetSnapshot(
            symbol: source.symbol.isEmpty ? "IBKR" : source.symbol,
            amount: source.amount,
            currency: source.currency,
            date: source.date,
            daysUntil: days,
            receivedYTDEUR: receivedYTD,
            remainingYearEUR: remaining,
            isUpcoming: next != nil
        )
        if let data = try? JSONEncoder().encode(snapshot) { UserDefaults.standard.set(data, forKey: cacheKey) }
        WidgetCenter.shared.reloadTimelines(ofKind: "DividendWidget")
        return snapshot
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: cacheKey)
        WidgetCenter.shared.reloadTimelines(ofKind: "DividendWidget")
    }

    private static func fxToEUR(_ currency: String) async -> Double {
        let c = currency.uppercased()
        if c.isEmpty || c == "EUR" || c == "BASE_SUMMARY" { return 1 }
        let pair = "EUR\(c)=X"
        var components = URLComponents(string: "https://query1.finance.yahoo.com/v8/finance/chart/\(pair.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? pair)")!
        components.queryItems = [.init(name: "range", value: "1d"), .init(name: "interval", value: "5m")]
        guard let (data, _) = try? await URLSession.shared.data(from: components.url!),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let chart = root["chart"] as? [String: Any],
              let result = (chart["result"] as? [[String: Any]])?.first,
              let meta = result["meta"] as? [String: Any],
              let rate = meta["regularMarketPrice"] as? Double, rate > 0 else { return 1 }
        return 1 / rate
    }

    private static func fetchStatement(token: String, queryID: String) async throws -> String {
        let t = token.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? token
        let q = queryID.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? queryID
        let send = try await get("\(base)/SendRequest?t=\(t)&q=\(q)&v=3")
        guard let ref = tag(send, "ReferenceCode") else { throw NSError(domain: "IBKR", code: 21, userInfo: [NSLocalizedDescriptionKey: tag(send, "ErrorMessage") ?? "ReferenceCode absent"]) }
        for _ in 0..<6 {
            let body = try await get("\(base)/GetStatement?t=\(t)&q=\(ref)&v=3")
            if body.contains("<FlexQueryResponse") { return body }
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
        throw NSError(domain: "IBKR", code: 22, userInfo: [NSLocalizedDescriptionKey: "Rapport IBKR indisponible"])
    }

    private static func get(_ url: String) async throws -> String {
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("MarketWidgets/1.7 iOS", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode < 300 else { throw URLError(.badServerResponse) }
        return String(decoding: data, as: UTF8.self)
    }

    private static func tag(_ xml: String, _ name: String) -> String? {
        guard let a = xml.range(of: "<\(name)>")?.upperBound,
              let b = xml.range(of: "</\(name)>", range: a..<xml.endIndex)?.lowerBound else { return nil }
        return String(xml[a..<b]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
