import AppIntents

struct AssetWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Actif"
    static var description = IntentDescription("Choisis le ticker Yahoo Finance à afficher, par exemple AAPL, BTC-USD, QQQ ou ^NDX.")

    @Parameter(title: "Ticker", default: "^NDX")
    var symbol: String
}
