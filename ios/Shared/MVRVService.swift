import Foundation
import WidgetKit

struct MVRVWidgetSnapshot: Codable, Hashable {
    let zScore: Double
    let price: Double?
    let updatedAt: Date
}

enum MVRVService {
    private static let cacheKey = "mvrvWidgetSnapshot"
    private static let base = "https://bitcoin-data.com/v1"

    static func cached() -> MVRVWidgetSnapshot? {
        guard let data = UserDefaults.standard.data(forKey: cacheKey) else { return nil }
        return try? JSONDecoder().decode(MVRVWidgetSnapshot.self, from: data)
    }

    static func refresh() async throws -> MVRVWidgetSnapshot {
        async let zAny = fetchJSON("\(base)/mvrv-zscore/last")
        async let priceAny = fetchJSON("\(base)/btc-price/last")
        let zRoot = try await zAny
        let priceRoot = try? await priceAny
        guard let z = findNumber(zRoot, preferred: ["mvrv-zscore", "mvrv_zscore", "mvrvzscore", "zscore", "value"]) else {
            throw URLError(.cannotParseResponse)
        }
        let price = priceRoot.flatMap { findNumber($0, preferred: ["price", "btcprice", "btc_price", "close", "value"]) }
        let snapshot = MVRVWidgetSnapshot(zScore: z, price: price, updatedAt: Date())
        if let data = try? JSONEncoder().encode(snapshot) { UserDefaults.standard.set(data, forKey: cacheKey) }
        WidgetCenter.shared.reloadTimelines(ofKind: "MVRVWidget")
        return snapshot
    }

    static func zone(_ z: Double) -> String {
        if z < 0 { return "Sous-évalué" }
        if z < 2 { return "Basse" }
        if z < 5 { return "Neutre" }
        if z < 7 { return "Chaude" }
        return "Haute"
    }

    private static func fetchJSON(_ url: String) async throws -> Any {
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("MarketWidgets/1.9 iOS", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return try JSONSerialization.jsonObject(with: data)
    }

    private static func findNumber(_ node: Any, preferred: Set<String>) -> Double? {
        if let dict = node as? [String: Any] {
            for (key, value) in dict where preferred.contains(key.lowercased()) {
                if let number = value as? NSNumber { return number.doubleValue }
                if let string = value as? String, let number = Double(string.replacingOccurrences(of: ",", with: "")) { return number }
            }
            for value in dict.values { if let found = findNumber(value, preferred: preferred) { return found } }
        } else if let array = node as? [Any] {
            for value in array { if let found = findNumber(value, preferred: preferred) { return found } }
        }
        return nil
    }
}
