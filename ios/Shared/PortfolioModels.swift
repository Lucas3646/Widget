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
    var dayChangeEUR: Double { accounts.reduce(0) { $0 + $1.dayChangeEUR } }
    var dayChangePercent: Double {
        let previous = totalEUR - dayChangeEUR
        return previous == 0 ? 0 : dayChangeEUR / previous * 100
    }
    var rankedPositions: [PortfolioPositionSnapshot] { positions.filter { $0.dayChangePercent.isFinite } }
    var top3: [PortfolioPositionSnapshot] { Array(rankedPositions.sorted { $0.dayChangePercent > $1.dayChangePercent }.prefix(3)) }
    var flop3: [PortfolioPositionSnapshot] { Array(rankedPositions.sorted { $0.dayChangePercent < $1.dayChangePercent }.prefix(3)) }
    static let preview = PortfolioSnapshot(accounts:[BrokerAccountSnapshot(broker:.ibkr,valueEUR:13420,dayChangeEUR:107.36,dayChangePercent:0.81),BrokerAccountSnapshot(broker:.kraken,valueEUR:5222,dayChangeEUR:620,dayChangePercent:13.5)],positions:[PortfolioPositionSnapshot(broker:.kraken,symbol:"BTC",valueEUR:4200,dayChangePercent:18.2),PortfolioPositionSnapshot(broker:.ibkr,symbol:"NVDA",valueEUR:3850,dayChangePercent:4.21),PortfolioPositionSnapshot(broker:.ibkr,symbol:"TSLA",valueEUR:1540,dayChangePercent:-2.14)],chartValues:[17850,17920,18040,18410,18642],updatedAt:Date())
}

struct DividendSnapshot: Codable, Hashable {
    let symbol:String; let nextAmount:Double; let nextCurrency:String; let nextDate:Date; let daysUntil:Int; let receivedYTDEUR:Double; let remainingYearEUR:Double; let hasUpcoming:Bool
    static let preview=DividendSnapshot(symbol:"KO",nextAmount:12.75,nextCurrency:"USD",nextDate:Calendar.current.date(byAdding:.day,value:3,to:Date()) ?? Date(),daysUntil:3,receivedYTDEUR:84.20,remainingYearEUR:41.60,hasUpcoming:true)
}
