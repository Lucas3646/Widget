import AppIntents

enum PriceWidgetTimeframe: String, AppEnum {
    case oneDay = "1D"
    case fiveDays = "5D"
    case oneMonth = "1M"
    case threeMonths = "3M"
    case ytd = "YTD"
    case oneYear = "1Y"

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Timeframe")
    static var caseDisplayRepresentations: [PriceWidgetTimeframe: DisplayRepresentation] = [
        .oneDay: "1D", .fiveDays: "5D", .oneMonth: "1M", .threeMonths: "3M", .ytd: "YTD", .oneYear: "1Y"
    ]
}

struct AssetWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Actif"
    static var description = IntentDescription("Choisis le ticker et le timeframe à afficher.")

    @Parameter(title: "Ticker", default: "^NDX")
    var symbol: String

    @Parameter(title: "Timeframe", default: .oneDay)
    var timeframe: PriceWidgetTimeframe
}
