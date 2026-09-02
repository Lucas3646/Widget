import Foundation
import WidgetKit

struct IBKRDividendWidgetSnapshot: Codable, Hashable {
    let symbol: String
    let description: String
    let amount: Double
    let currency: String
    let date: Date
    let isUpcoming: Bool

    static let empty = IBKRDividendWidgetSnapshot(symbol: "IBKR", description: "Aucune échéance trouvée", amount: 0, currency: "USD", date: Date(), isUpcoming: false)
}

private struct IBKRDividendRow {
    let symbol: String
    let description: String
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
            let type = (a["type"] ?? a["transactionType"] ?? a["description"] ?? "").lowercased()
            if (type.contains("dividend") || type.contains("dividende")), let row = Self.row(from: a, upcoming: false) {
                rows.append(row)
            }
        }
    }

    private static func row(from a: [String: String], upcoming: Bool) -> IBKRDividendRow? {
        let symbol = a["symbol"] ?? ""
        let description = a["description"] ?? a["companyName"] ?? a["issuer"] ?? ""
        guard !symbol.isEmpty || !description.isEmpty else { return nil }
        let amount = ["grossAmount", "amount", "accruedAmount", "netAmount"].compactMap { key in
            a[key].flatMap { Double($0.replacingOccurrences(of: ",", with: "")) }
        }.first ?? 0
        let currency = a["currency"] ?? a["fxCurrency"] ?? "USD"
        guard let rawDate = ["payDate", "exDate", "dateTime", "reportDate", "settleDate", "date"].compactMap({ a[$0] }).first,
              let date = parseDate(rawDate) else { return nil }
        return IBKRDividendRow(symbol: symbol, description: description, amount: amount, currency: currency, date: date, upcoming: upcoming)
    }

    private static func parseDate(_ raw: String) -> Date? {
        let compact = String(raw.prefix(10)).replacingOccurrences(of: "-", with: "")
        let candidates = [compact, raw]
        let formats = ["yyyyMMdd", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy", "yyyyMMdd;HHmmss"]
        for value in candidates {
            for format in formats {
                let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = format
                if let d = f.date(from: value) { return d }
            }
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
        let p = IBKRDividendXMLParser(); let xp = XMLParser(data: Data(xml.utf8)); xp.delegate = p
        guard xp.parse() else { throw URLError(.cannotParseResponse) }
        let now = Date()
        let next = p.rows.filter { $0.upcoming && $0.date >= now }.min { $0.date < $1.date }
            ?? p.rows.filter { $0.upcoming }.max { $0.date < $1.date }
            ?? p.rows.filter { !$0.upcoming }.max { $0.date < $1.date }
        let snapshot = next.map { IBKRDividendWidgetSnapshot(symbol: $0.symbol.isEmpty ? "IBKR" : $0.symbol, description: $0.description, amount: $0.amount, currency: $0.currency, date: $0.date, isUpcoming: $0.upcoming) }
        if let snapshot, let data = try? JSONEncoder().encode(snapshot) { UserDefaults.standard.set(data, forKey: cacheKey) }
        else { UserDefaults.standard.removeObject(forKey: cacheKey) }
        WidgetCenter.shared.reloadTimelines(ofKind: "DividendWidget")
        return snapshot
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: cacheKey)
        WidgetCenter.shared.reloadTimelines(ofKind: "DividendWidget")
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
        request.setValue("MarketWidgets/1.5 iOS", forHTTPHeaderField: "User-Agent")
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
