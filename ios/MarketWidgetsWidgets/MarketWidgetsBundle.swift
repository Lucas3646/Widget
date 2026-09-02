import WidgetKit
import SwiftUI

@main
struct MarketWidgetsBundle: WidgetBundle {
    var body: some Widget {
        MarketPriceWidget()
        ATHDrawdownWidget()
        PortfolioWidget()
        DividendWidget()
        MVRVWidget()
        MacroWidget()
    }
}
