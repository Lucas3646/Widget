import Foundation

struct BrokerAccountSnapshot: Codable, Hashable {
    enum Broker: String, Codable, Hashable { case ibkr, kraken }
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
    let chartValues: [Double]
    let updatedAt: Date

    var totalEUR: Double { accounts.reduce(0) { $0 + $1.valueEUR } }

    // Kraken's Balance permission exposes current quantities, not historical cost basis/trades.
    // Never present a repriced current balance as portfolio P&L. Only IBKR contributes until
    // a true Kraken transaction/cost-basis source is available.
    var dayChangeEUR: Double { accounts.filter { $0.broker == .ibkr }.reduce(0) { $0 + $1.dayChangeEUR } }
    var dayChangePercent: Double {
        let ibkr = accounts.filter { $0.broker == .ibkr }
        let current = ibkr.reduce(0) { $0 + $1.valueEUR }
        let previous = current - dayChangeEUR
        return previous == 0 ? 0 : dayChangeEUR / previous * 100
    }
    var rankedPositions: [PortfolioPositionSnapshot] { positions.filter { $0.broker == .ibkr } }
    var top3: [PortfolioPositionSnapshot] { Array(rankedPositions.sorted { $0.dayChangePercent > $1.dayChangePercent }.prefix(3)) }
    var flop3: [PortfolioPositionSnapshot] { Array(rankedPositions.sorted { $0.dayChangePercent < $1.dayChangePercent }.prefix(3)) }

    static let preview = PortfolioSnapshot(
        accounts: [
            BrokerAccountSnapshot(broker: .ibkr, valueEUR: 13_420, dayChangeEUR: 107.36, dayChangePercent: 0.81),
            BrokerAccountSnapshot(broker: .kraken, valueEUR: 5_222, dayChangeEUR: 0, dayChangePercent: 0)
        ],
        positions: [
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "NVDA", valueEUR: 3850, dayChangePercent: 4.21),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "AAPL", valueEUR: 2710, dayChangePercent: 1.82),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "AMD", valueEUR: 1260, dayChangePercent: -1.32),
            PortfolioPositionSnapshot(broker: .ibkr, symbol: "TSLA", valueEUR: 1540, dayChangePercent: -2.14)
        ],
        chartValues: [17_850, 17_920, 17_880, 18_040, 18_110, 18_060, 18_240, 18_410, 18_360, 18_642],
        updatedAt: Date()
    )
}

struct DividendSnapshot: Codable, Hashable {
    let symbol: String
    let nextAmount: Double
    let nextCurrency: String
    let nextDate: Date
    let daysUntil: Int
    let receivedYTDEUR: Double
    let remainingYearEUR: Double
    let hasUpcoming: Bool

    static let preview = DividendSnapshot(
        symbol: "KO", nextAmount: 12.75, nextCurrency: "USD",
        nextDate: Calendar.current.date(byAdding: .day, value: 3, to: Date()) ?? Date(), daysUntil: 3,
        receivedYTDEUR: 84.20, remainingYearEUR: 41.60, hasUpcoming: true
    )
}
