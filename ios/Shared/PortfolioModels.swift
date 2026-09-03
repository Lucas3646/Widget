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

    // Aggregate broker-level returns rather than deriving portfolio return from current holdings.
    // IBKR can therefore supply its TWR (cash-flow adjusted); cash stays in totalEUR without becoming performance.
    private var brokerPerformance: (change: Double, base: Double) {
        accounts.reduce(into: (change: 0.0, base: 0.0)) { r, a in
            let factor = 1.0 + a.dayChangePercent / 100.0
            guard factor > 0.000001 else { return }
            let base = a.valueEUR / factor
            guard base.isFinite, base > 0 else { return }
            r.base += base
            r.change += a.valueEUR - base
        }
    }
    var dayChangeEUR: Double { brokerPerformance.change }
    var dayChangePercent: Double { let p=brokerPerformance; return p.base > 0 ? p.change/p.base*100 : 0 }
    var rankedPositions: [PortfolioPositionSnapshot] { positions }
    var top3: [PortfolioPositionSnapshot] { Array(rankedPositions.filter{$0.dayChangePercent > 0.0001}.sorted{$0.dayChangePercent > $1.dayChangePercent}.prefix(3)) }
    var flop3: [PortfolioPositionSnapshot] { Array(rankedPositions.filter{$0.dayChangePercent < -0.0001}.sorted{$0.dayChangePercent < $1.dayChangePercent}.prefix(3)) }
    static let preview = PortfolioSnapshot(accounts:[BrokerAccountSnapshot(broker:.ibkr,valueEUR:13420,dayChangeEUR:107.36,dayChangePercent:0.81),BrokerAccountSnapshot(broker:.kraken,valueEUR:5222,dayChangeEUR:620,dayChangePercent:13.5)],positions:[PortfolioPositionSnapshot(broker:.kraken,symbol:"BTC",valueEUR:4200,dayChangePercent:18.2),PortfolioPositionSnapshot(broker:.ibkr,symbol:"NVDA",valueEUR:3850,dayChangePercent:4.21),PortfolioPositionSnapshot(broker:.ibkr,symbol:"TSLA",valueEUR:1540,dayChangePercent:-2.14)],chartValues:[17850,17920,18040,18410,18642],updatedAt:Date())
}

struct DividendSnapshot: Codable, Hashable {
    let symbol:String; let nextAmount:Double; let nextCurrency:String; let nextDate:Date; let daysUntil:Int; let receivedYTDEUR:Double; let remainingYearEUR:Double; let hasUpcoming:Bool
    static let preview=DividendSnapshot(symbol:"KO",nextAmount:12.75,nextCurrency:"USD",nextDate:Calendar.current.date(byAdding:.day,value:3,to:Date()) ?? Date(),daysUntil:3,receivedYTDEUR:84.20,remainingYearEUR:41.60,hasUpcoming:true)
}
