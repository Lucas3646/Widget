import Foundation

struct AssetSuggestion: Identifiable, Hashable, Sendable {
    let symbol: String
    let name: String
    let type: String
    let exchange: String
    var id: String { symbol }
}

struct QuoteSnapshot: Sendable {
    let symbol: String
    let name: String
    let price: Double
    let previousClose: Double?
    let changePercent: Double?
    let updatedAt: Date
}

struct DrawdownSnapshot: Sendable {
    let symbol: String
    let price: Double
    let ath: Double
    let drawdownPercent: Double
    let updatedAt: Date
}

enum MarketServiceError: Error {
    case invalidURL
    case noData
    case invalidResponse
}

enum MarketService {
    static func search(_ query: String) async throws -> [AssetSuggestion] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        var components = URLComponents(string: "https://query2.finance.yahoo.com/v1/finance/search")!
        components.queryItems = [
            URLQueryItem(name: "q", value: text),
            URLQueryItem(name: "quotesCount", value: "8"),
            URLQueryItem(name: "newsCount", value: "0"),
            URLQueryItem(name: "enableFuzzyQuery", value: "true")
        ]
        guard let url = components.url else { throw MarketServiceError.invalidURL }
        var request = URLRequest(url: url)
        request.setValue("MarketWidgets-iOS/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let quotes = root?["quotes"] as? [[String: Any]] ?? []
        return quotes.compactMap { item in
            guard let symbol = item["symbol"] as? String, !symbol.isEmpty else { return nil }
            let quoteType = (item["quoteType"] as? String) ?? ""
            if quoteType.uppercased() == "OPTION" { return nil }
            let name = (item["shortname"] as? String)
                ?? (item["longname"] as? String)
                ?? symbol
            return AssetSuggestion(
                symbol: symbol,
                name: name,
                type: quoteType,
                exchange: (item["exchange"] as? String) ?? ""
            )
        }
    }

    static func quote(symbol: String) async throws -> QuoteSnapshot {
        let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        guard let url = URL(string: "https://query1.finance.yahoo.com/v8/finance/chart/\(encoded)?range=1d&interval=5m&includePrePost=true") else {
            throw MarketServiceError.invalidURL
        }
        var request = URLRequest(url: url)
        request.setValue("MarketWidgets-iOS/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard
            let chart = root?["chart"] as? [String: Any],
            let results = chart["result"] as? [[String: Any]],
            let result = results.first,
            let meta = result["meta"] as? [String: Any]
        else { throw MarketServiceError.noData }

        let price = (meta["regularMarketPrice"] as? NSNumber)?.doubleValue
            ?? latestClose(from: result)
        guard let current = price else { throw MarketServiceError.noData }
        let previous = (meta["chartPreviousClose"] as? NSNumber)?.doubleValue
            ?? (meta["previousClose"] as? NSNumber)?.doubleValue
        let change = previous.flatMap { $0 != 0 ? ((current / $0) - 1) * 100 : nil }
        let name = (meta["shortName"] as? String) ?? (meta["longName"] as? String) ?? symbol
        return QuoteSnapshot(symbol: symbol, name: name, price: current, previousClose: previous, changePercent: change, updatedAt: Date())
    }

    static func drawdown(symbol: String) async throws -> DrawdownSnapshot {
        let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        guard let url = URL(string: "https://query1.finance.yahoo.com/v8/finance/chart/\(encoded)?range=max&interval=1wk&includePrePost=false&includeAdjustedClose=true") else {
            throw MarketServiceError.invalidURL
        }
        var request = URLRequest(url: url)
        request.setValue("MarketWidgets-iOS/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard
            let chart = root?["chart"] as? [String: Any],
            let results = chart["result"] as? [[String: Any]],
            let result = results.first,
            let meta = result["meta"] as? [String: Any],
            let indicators = result["indicators"] as? [String: Any],
            let quotes = indicators["quote"] as? [[String: Any]],
            let quote = quotes.first,
            let highs = quote["high"] as? [Any]
        else { throw MarketServiceError.noData }

        let current = (meta["regularMarketPrice"] as? NSNumber)?.doubleValue ?? latestClose(from: result)
        guard let price = current else { throw MarketServiceError.noData }
        let highValues = highs.compactMap { ($0 as? NSNumber)?.doubleValue }
        guard let historicalAth = highValues.max() else { throw MarketServiceError.noData }
        let ath = max(historicalAth, price)
        return DrawdownSnapshot(symbol: symbol, price: price, ath: ath, drawdownPercent: (price / ath - 1) * 100, updatedAt: Date())
    }

    private static func latestClose(from result: [String: Any]) -> Double? {
        guard
            let indicators = result["indicators"] as? [String: Any],
            let quotes = indicators["quote"] as? [[String: Any]],
            let closes = quotes.first?["close"] as? [Any]
        else { return nil }
        return closes.reversed().compactMap { ($0 as? NSNumber)?.doubleValue }.first
    }
}
