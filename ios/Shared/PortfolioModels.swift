import Foundation

struct BrokerAccountSnapshot: Codable, Hashable {
    enum Broker: String, Codable {
        case ibkr
        case kraken
    }

    let broker: Broker
    let valueEUR: Double
    let dayChangeEUR: Double
    let dayChangePercent: Double
}

struct PortfolioSnapshot: Codable, Hashable {
    let accounts: [BrokerAccountSnapshot]
    let updatedAt: Date

    var totalEUR: Double { accounts.reduce(0) { $0 + $1.valueEUR } }
    var dayChangeEUR: Double { accounts.reduce(0) { $0 + $1.dayChangeEUR } }
    var dayChangePercent: Double {
        let previous = totalEUR - dayChangeEUR
        return previous == 0 ? 0 : dayChangeEUR / previous * 100
    }

    static let preview = PortfolioSnapshot(
        accounts: [
            BrokerAccountSnapshot(broker: .ibkr, valueEUR: 13_420, dayChangeEUR: 107.36, dayChangePercent: 0.81),
            BrokerAccountSnapshot(broker: .kraken, valueEUR: 5_222, dayChangeEUR: 106.64, dayChangePercent: 2.08)
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
