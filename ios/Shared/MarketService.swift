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

enum MarketServiceError: Error { case invalidURL, noData, invalidResponse }

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
        request.setValue("MarketWidgets-iOS/2.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let quotes = root?["quotes"] as? [[String: Any]] ?? []
        return quotes.compactMap { item in
            guard let symbol = item["symbol"] as? String, !symbol.isEmpty else { return nil }
            let quoteType = (item["quoteType"] as? String) ?? ""
            if quoteType.uppercased() == "OPTION" { return nil }
            let name = (item["shortname"] as? String) ?? (item["longname"] as? String) ?? symbol
            return AssetSuggestion(symbol: symbol, name: name, type: quoteType, exchange: (item["exchange"] as? String) ?? "")
        }
    }

    static func quote(symbol: String) async throws -> QuoteSnapshot {
        try await quote(symbol: symbol, timeframe: "1D")
    }

    static func quote(symbol: String, timeframe: String) async throws -> QuoteSnapshot {
        let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        let params: (String, String) = switch timeframe {
        case "5D": ("5d", "15m")
        case "1M": ("1mo", "1h")
        case "3M": ("3mo", "1d")
        case "YTD": ("ytd", "1d")
        case "1Y": ("1y", "1d")
        default: ("1d", "5m")
        }
        guard let url = URL(string: "https://query1.finance.yahoo.com/v8/finance/chart/\(encoded)?range=\(params.0)&interval=\(params.1)&includePrePost=true") else { throw MarketServiceError.invalidURL }
        var request = URLRequest(url: url)
        request.setValue("MarketWidgets-iOS/2.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let chart = root?["chart"] as? [String: Any], let results = chart["result"] as? [[String: Any]], let result = results.first, let meta = result["meta"] as? [String: Any] else { throw MarketServiceError.noData }
        let current = (meta["regularMarketPrice"] as? NSNumber)?.doubleValue ?? latestClose(from: result)
        guard let price = current else { throw MarketServiceError.noData }
        let baseline: Double? = timeframe == "1D"
            ? ((meta["chartPreviousClose"] as? NSNumber)?.doubleValue ?? (meta["previousClose"] as? NSNumber)?.doubleValue ?? firstClose(from: result))
            : firstClose(from: result)
        let change = baseline.flatMap { $0 != 0 ? ((price / $0) - 1) * 100 : nil }
        let name = (meta["shortName"] as? String) ?? (meta["longName"] as? String) ?? symbol
        return QuoteSnapshot(symbol: symbol, name: name, price: price, previousClose: baseline, changePercent: change, updatedAt: Date())
    }

    static func drawdown(symbol: String) async throws -> DrawdownSnapshot {
        let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        guard let url = URL(string: "https://query1.finance.yahoo.com/v8/finance/chart/\(encoded)?range=max&interval=1wk&includePrePost=false&includeAdjustedClose=true") else { throw MarketServiceError.invalidURL }
        var request = URLRequest(url: url)
        request.setValue("MarketWidgets-iOS/2.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw MarketServiceError.invalidResponse }
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let chart = root?["chart"] as? [String: Any], let results = chart["result"] as? [[String: Any]], let result = results.first, let meta = result["meta"] as? [String: Any], let indicators = result["indicators"] as? [String: Any], let quotes = indicators["quote"] as? [[String: Any]], let quote = quotes.first, let highs = quote["high"] as? [Any] else { throw MarketServiceError.noData }
        let current = (meta["regularMarketPrice"] as? NSNumber)?.doubleValue ?? latestClose(from: result)
        guard let price = current else { throw MarketServiceError.noData }
        let highValues = highs.compactMap { ($0 as? NSNumber)?.doubleValue }
        guard let historicalAth = highValues.max() else { throw MarketServiceError.noData }
        let ath = max(historicalAth, price)
        return DrawdownSnapshot(symbol: symbol, price: price, ath: ath, drawdownPercent: (price / ath - 1) * 100, updatedAt: Date())
    }

    private static func closes(from result: [String: Any]) -> [Double] {
        guard let indicators = result["indicators"] as? [String: Any], let quotes = indicators["quote"] as? [[String: Any]], let raw = quotes.first?["close"] as? [Any] else { return [] }
        return raw.compactMap { ($0 as? NSNumber)?.doubleValue }
    }
    private static func latestClose(from result: [String: Any]) -> Double? { closes(from: result).last }
    private static func firstClose(from result: [String: Any]) -> Double? { closes(from: result).first }
}
