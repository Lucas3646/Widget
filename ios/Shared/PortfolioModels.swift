import Foundation

struct BrokerAccountSnapshot: Codable, Hashable {
    enum Broker: String, Codable, Hashable {
        case ibkr
        case kraken
    }

    let broker: Broker
    let valueEUR: Double
    let dayChangeEUR: Double
    let dayChangePercent: Double
}

struct PortfolioPositionSnapshot: Codable, Hashable, Identifiable {
    var id: String { "\(broker.rawValue)-\(symbol)" }
    let broker: BrokerAccountSnapshot.Broker
    let symbol: String
    let valueEUR: Double
    let dayChangePercent: Double
}

struct PortfolioSnapshot: Codable, Hashable {
    let accounts: [BrokerAccountSnapshot]
    let positions: [PortfolioPositionSnapshot]
    let updatedAt: Date

    var totalEUR: Double { accounts.reduce(0) { $0 + $1.valueEUR } }
    var dayChangeEUR: Double { accounts.reduce(0) { $0 + $1.dayChangeEUR } }
    var dayChangePercent: Double {
        let previous = totalEUR - dayChangeEUR
        return previous == 0 ? 0 : dayChangeEUR / previous * 100
    }
    var top3: [PortfolioPositionSnapshot] { Array(positions.sorted { $0.dayChangePercent > $1.dayChangePercent }.prefix(3)) }
    var flop3: [PortfolioPositionSnapshot] { Array(positions.sorted { $0.dayChangePercent < $1.dayChangePercent }.prefix(3)) }

    static let preview = PortfolioSnapshot(
        accounts: [
            BrokerAccountSnapshot(broker: .ibkr, valueEUR: 13_420, dayChangeEUR: 107.36, dayChangePercent: 0.81),
            BrokerAccountSnapshot(broker: .kraken, valueEUR: 5_222, dayChangeEUR: 106.64, dayChangePercent: 2.08)
        ],
        positions: [
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "NVDA", valueEUR: 3850, dayChangePercent: 4.21),
            PortfolioPositionSnapshot(broker: .kraken, symbol: "BTC", valueEUR: 3420, dayChangePercent: 3.46),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "AAPL", valueEUR: 2710, dayChangePercent: 1.82),
            PortfolioPositionSnapshot(broker: .kraken, symbol: "ETH", valueEUR: 1802, dayChangePercent: -0.74),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "AMD", valueEUR: 1260, dayChangePercent: -1.32),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "TSLA", valueEUR: 1540, dayChangePercent: -2.14)
        ],
        updatedAt: Date()
    )
}

struct DividendSnapshot: Codable, Hashable {
    let symbol: String
    let companyName: String
    let amountPerShare: Double
    let currency: String
    let exDividendDate: Date
    let estimatedAmount: Double?

    static let preview = DividendSnapshot(
        symbol: "KO",
        companyName: "Coca-Cola",
        amountPerShare: 0.51,
        currency: "USD",
        exDividendDate: Calendar.current.date(byAdding: .day, value: 11, to: Date()) ?? Date(),
        estimatedAmount: 12.75
    )
}
