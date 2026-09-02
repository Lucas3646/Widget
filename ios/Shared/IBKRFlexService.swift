import Foundation
import Security

struct IBKRPositionSnapshot: Codable, Hashable {
    let symbol: String
    let valueEUR: Double
    let periodChangeEUR: Double
    let periodChangePercent: Double
}

struct IBKRPortfolioSnapshot: Codable, Hashable {
    let totalEUR: Double
    let periodChangeEUR: Double
    let periodChangePercent: Double
    let positions: [IBKRPositionSnapshot]
    let updatedAt: Date
}

enum IBKRFlexKeychainStore {
    private static let service = "com.lucas.marketwidgets.ibkrflex"
    static func save(token: String, queryID: String) throws { try saveValue(token, account: "token"); try saveValue(queryID, account: "queryID") }
    static func credentials() -> (String, String)? { guard let token=value("token"), let query=value("queryID") else { return nil }; return (token,query) }
    static func clear() { ["token","queryID"].forEach { SecItemDelete([kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:$0] as CFDictionary) } }
    private static func saveValue(_ value:String, account:String) throws { let q:[CFString:Any]=[kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:account];SecItemDelete(q as CFDictionary);var i=q;i[kSecValueData]=Data(value.utf8);let s=SecItemAdd(i as CFDictionary,nil);guard s==errSecSuccess else{throw NSError(domain:NSOSStatusErrorDomain,code:Int(s))} }
    private static func value(_ account:String)->String?{let q:[CFString:Any]=[kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:account,kSecReturnData:true,kSecMatchLimit:kSecMatchLimitOne];var item:CFTypeRef?;guard SecItemCopyMatching(q as CFDictionary,&item)==errSecSuccess,let d=item as? Data else{return nil};return String(data:d,encoding:.utf8)}
}

private struct IBKRFlexPosition { let symbol:String; let quantity:Double; let markPrice:Double; let currency:String }
private struct IBKRQuote { let current:Double; let reference:Double }

final class IBKRFlexParser: NSObject, XMLParserDelegate {
    var positions:[IBKRFlexPosition]=[]
    var latestTotals:[String:(date:String,total:Double,currency:String)]=[:]
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes a: [String : String] = [:]) {
        if elementName == "OpenPosition", let symbol=a["symbol"], let q=Double(a["position"] ?? ""), let mark=Double(a["markPrice"] ?? ""), q != 0, mark > 0 { positions.append(.init(symbol:symbol,quantity:q,markPrice:mark,currency:a["currency"] ?? "EUR")) }
        if elementName == "EquitySummaryByReportDateInBase", let date=a["reportDate"], let total=Double(a["total"] ?? "") { let account=a["accountId"] ?? "default"; if latestTotals[account] == nil || date > latestTotals[account]!.date { latestTotals[account]=(date,total,a["currency"] ?? "EUR") } }
    }
}

enum IBKRFlexService {
    private static let base = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
    static func refresh(period: String = "1S") async throws -> IBKRPortfolioSnapshot {
        guard let (token,queryID)=IBKRFlexKeychainStore.credentials() else { throw NSError(domain:"IBKR",code:1,userInfo:[NSLocalizedDescriptionKey:"Token Flex ou Query ID absent"]) }
        let statement=try await fetchStatement(token:token,queryID:queryID)
        let parser=IBKRFlexParser();let xp=XMLParser(data:Data(statement.utf8));xp.delegate=parser;guard xp.parse() else{throw URLError(.cannotParseResponse)}
        var total=0.0
        for (_,v) in parser.latestTotals { total += v.total * (await fxToEUR(v.currency)) }
        var change=0.0;var rows:[IBKRPositionSnapshot]=[]
        for p in parser.positions { let quote=try? await yahooQuote(symbol:p.symbol,currency:p.currency,period:period);let fx=await fxToEUR(p.currency);let current=quote?.current ?? p.markPrice;let ref=quote?.reference ?? p.markPrice;let live=p.quantity*current*fx;total += live-p.quantity*p.markPrice*fx;let ch=p.quantity*(current-ref)*fx;change += ch;let pct=ref != 0 ? (current/ref-1)*100:0;rows.append(.init(symbol:p.symbol,valueEUR:live,periodChangeEUR:ch,periodChangePercent:pct)) }
        let previous=total-change
        return .init(totalEUR:total,periodChangeEUR:change,periodChangePercent:previous>0 ? change/previous*100:0,positions:rows,updatedAt:Date())
    }
    private static func fetchStatement(token:String,queryID:String) async throws -> String { let encToken=token.addingPercentEncoding(withAllowedCharacters:.urlQueryAllowed)!;let encQuery=queryID.addingPercentEncoding(withAllowedCharacters:.urlQueryAllowed)!;let send=try await get("\(base)/SendRequest?t=\(encToken)&q=\(encQuery)&v=3");guard let ref=tag(send,"ReferenceCode") else{throw NSError(domain:"IBKR",code:2,userInfo:[NSLocalizedDescriptionKey:tag(send,"ErrorMessage") ?? "ReferenceCode absent"])};for _ in 0..<6 { let body=try await get("\(base)/GetStatement?t=\(encToken)&q=\(ref)&v=3");if body.contains("<FlexQueryResponse"){return body};try await Task.sleep(nanoseconds:1_000_000_000) };throw NSError(domain:"IBKR",code:3,userInfo:[NSLocalizedDescriptionKey:"Rapport IBKR indisponible"]) }
    private static func get(_ url:String) async throws -> String { var r=URLRequest(url:URL(string:url)!);r.setValue("MarketWidgets/1.4 iOS",forHTTPHeaderField:"User-Agent");let(d,res)=try await URLSession.shared.data(for:r);guard let h=res as? HTTPURLResponse,h.statusCode<300 else{throw URLError(.badServerResponse)};return String(decoding:d,as:UTF8.self) }
    private static func tag(_ xml:String,_ name:String)->String?{guard let a=xml.range(of:"<\(name)>")?.upperBound,let b=xml.range(of:"</\(name)>",range:a..<xml.endIndex)?.lowerBound else{return nil};return String(xml[a..<b]).trimmingCharacters(in:.whitespacesAndNewlines)}
    private static func yahooQuote(symbol:String,currency:String,period:String) async throws -> IBKRQuote { let candidates=currency.uppercased()=="USD" ? [symbol] : currency.uppercased()=="GBP" ? ["\(symbol).L",symbol] : ["\(symbol).PA","\(symbol).AS","\(symbol).BR","\(symbol).DE","\(symbol).MI",symbol];let range=period=="1S" ? "1d" : period=="1M" ? "1mo" : period=="3M" ? "3mo" : period=="YTD" ? "ytd":"1y";let interval=period=="1S" ? "5m":"1d";for s in candidates { var c=URLComponents(string:"https://query1.finance.yahoo.com/v8/finance/chart/\(s.addingPercentEncoding(withAllowedCharacters:.urlPathAllowed)!)")!;c.queryItems=[.init(name:"range",value:range),.init(name:"interval",value:interval),.init(name:"includePrePost",value:"true")];if let(d,_)=try? await URLSession.shared.data(from:c.url!),let root=try? JSONSerialization.jsonObject(with:d) as? [String:Any],let chart=root["chart"] as? [String:Any],let result=(chart["result"] as? [[String:Any]])?.first,let meta=result["meta"] as? [String:Any],let current=meta["regularMarketPrice"] as? Double { var ref=current;if period=="1S" { ref=(meta["chartPreviousClose"] as? Double) ?? (meta["previousClose"] as? Double) ?? current } else if let ind=result["indicators"] as? [String:Any],let q=(ind["quote"] as? [[String:Any]])?.first,let closes=q["close"] as? [Any] { for v in closes { if let n=v as? Double { ref=n;break } } };return .init(current:current,reference:ref) } };throw URLError(.cannotParseResponse) }
    private static func fxToEUR(_ currency:String) async -> Double { let c=currency.uppercased();if c=="EUR" || c=="BASE_SUMMARY" || c.isEmpty{return 1};let symbol="EUR\(c)=X";var comp=URLComponents(string:"https://query1.finance.yahoo.com/v8/finance/chart/\(symbol.addingPercentEncoding(withAllowedCharacters:.urlPathAllowed)!)")!;comp.queryItems=[.init(name:"range",value:"1d"),.init(name:"interval",value:"5m")];guard let(d,_)=try? await URLSession.shared.data(from:comp.url!),let root=try? JSONSerialization.jsonObject(with:d) as? [String:Any],let chart=root["chart"] as? [String:Any],let r=(chart["result"] as? [[String:Any]])?.first,let meta=r["meta"] as? [String:Any],let rate=meta["regularMarketPrice"] as? Double,rate>0 else{return 1};return 1/rate }
}
