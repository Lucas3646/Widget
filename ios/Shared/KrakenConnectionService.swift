import Foundation
import CryptoKit
import Security

struct KrakenIOSPosition: Codable, Hashable {
    let symbol: String
    let valueEUR: Double
    let dayChangeEUR: Double
    let dayChangePercent: Double
}

struct KrakenIOSSnapshot: Codable, Hashable {
    let totalEUR: Double
    let dayChangeEUR: Double
    let dayChangePercent: Double
    let balances: [String: Double]
    let positions: [KrakenIOSPosition]
    let updatedAt: Date
}

private struct KrakenTickerSnapshot {
    let current: Double
    let open: Double
}

enum KrakenKeychainStore {
    private static let service = "com.lucas.marketwidgets.kraken"

    static func save(apiKey: String, apiSecret: String) throws {
        try saveValue(apiKey, account: "apiKey")
        try saveValue(apiSecret, account: "apiSecret")
    }

    static func credentials() -> (String, String)? {
        guard let key = value(account: "apiKey"), let secret = value(account: "apiSecret") else { return nil }
        return (key, secret)
    }

    static func clear() {
        ["apiKey", "apiSecret"].forEach { account in
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account] as CFDictionary)
        }
    }

    private static func saveValue(_ value: String, account: String) throws {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        SecItemDelete(query as CFDictionary)
        var insert = query
        insert[kSecValueData] = data
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    private static func value(account: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

enum KrakenConnectionService {
    private static let base = URL(string: "https://api.kraken.com")!
    private static let cashLike: Set<String> = ["EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY", "USDT", "USDC"]

    static func refresh() async throws -> KrakenIOSSnapshot {
        guard let (apiKey, secret) = KrakenKeychainStore.credentials() else {
            throw NSError(domain: "Kraken", code: 1, userInfo: [NSLocalizedDescriptionKey: "Identifiants Kraken absents"])
        }
        let balances = try await balance(apiKey: apiKey, secret: secret).filter { abs($0.value) > 0.00000001 }
        var total = 0.0
        var totalDayChange = 0.0
        var positions: [KrakenIOSPosition] = []

        for (rawAsset, amount) in balances {
            let symbol = normalize(rawAsset)
            guard let ticker = try? await eurTicker(asset: rawAsset) else { continue }
            let value = amount * ticker.current
            let dayChange = amount * (ticker.current - ticker.open)
            let dayPercent = ticker.open > 0 ? (ticker.current / ticker.open - 1) * 100 : 0
            total += value
            totalDayChange += dayChange

            if !cashLike.contains(symbol), ticker.current > 0, value > 0.01 {
                positions.append(
                    KrakenIOSPosition(
                        symbol: symbol == "XBT" ? "BTC" : symbol,
                        valueEUR: value,
                        dayChangeEUR: dayChange,
                        dayChangePercent: dayPercent
                    )
                )
            }
        }

        let previous = total - totalDayChange
        let totalDayPercent = previous > 0 ? totalDayChange / previous * 100 : 0
        return KrakenIOSSnapshot(
            totalEUR: total,
            dayChangeEUR: totalDayChange,
            dayChangePercent: totalDayPercent,
            balances: balances,
            positions: positions,
            updatedAt: Date()
        )
    }

    private static func balance(apiKey: String, secret: String) async throws -> [String: Double] {
        let path = "/0/private/Balance"
        let nonce = String(Int(Date().timeIntervalSince1970 * 1000))
        let postData = "nonce=\(nonce)"
        let signature = try sign(path: path, nonce: nonce, postData: postData, secret: secret)
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.httpBody = Data(postData.utf8)
        request.setValue(apiKey, forHTTPHeaderField: "API-Key")
        request.setValue(signature, forHTTPHeaderField: "API-Sign")
        request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode ?? 500 < 300 else { throw URLError(.badServerResponse) }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let errors = json?["error"] as? [String], let first = errors.first, !first.isEmpty {
            throw NSError(domain: "Kraken", code: 2, userInfo: [NSLocalizedDescriptionKey: first])
        }
        guard let result = json?["result"] as? [String: String] else { throw URLError(.cannotParseResponse) }
        return result.compactMapValues(Double.init)
    }

    private static func sign(path: String, nonce: String, postData: String, secret: String) throws -> String {
        guard let secretData = Data(base64Encoded: secret) else { throw URLError(.userAuthenticationRequired) }
        let digest = SHA256.hash(data: Data((nonce + postData).utf8))
        var message = Data(path.utf8)
        message.append(contentsOf: digest)
        let key = SymmetricKey(data: secretData)
        let code = HMAC<SHA512>.authenticationCode(for: message, using: key)
        return Data(code).base64EncodedString()
    }

    private static func eurTicker(asset raw: String) async throws -> KrakenTickerSnapshot {
        let asset = normalize(raw)
        if asset == "EUR" { return KrakenTickerSnapshot(current: 1, open: 1) }
        let candidates: [(String, Bool)] = asset == "XBT" ? [("XBTEUR", false), ("XXBTZEUR", false)] :
            asset == "USD" ? [("EURUSD", true)] :
            asset == "USDT" ? [("USDTEUR", false)] :
            asset == "USDC" ? [("USDCEUR", false)] : [("\(asset)EUR", false)]
        for (pair, inverse) in candidates {
            if let t = try? await ticker(pair: pair), t.current > 0, t.open > 0 {
                return inverse ? KrakenTickerSnapshot(current: 1 / t.current, open: 1 / t.open) : t
            }
        }
        throw URLError(.cannotParseResponse)
    }

    private static func ticker(pair: String) async throws -> KrakenTickerSnapshot {
        var components = URLComponents(url: base.appendingPathComponent("/0/public/Ticker"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "pair", value: pair)]
        let (data, _) = try await URLSession.shared.data(from: components.url!)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let result = json?["result"] as? [String: Any],
              let first = result.values.first as? [String: Any],
              let close = first["c"] as? [String],
              let current = close.first.flatMap(Double.init) else { throw URLError(.cannotParseResponse) }
        let open = (first["o"] as? String).flatMap(Double.init) ?? current
        return KrakenTickerSnapshot(current: current, open: open)
    }

    private static func normalize(_ raw: String) -> String {
        let base = raw.split(separator: ".").first.map(String.init) ?? raw
        switch base {
        case "ZEUR": return "EUR"
        case "ZUSD": return "USD"
        case "XXBT": return "XBT"
        case "XETH": return "ETH"
        default: return String(base.drop(while: { $0 == "X" || $0 == "Z" }))
        }
    }
}
