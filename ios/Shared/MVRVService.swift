import Foundation
import WidgetKit

struct MVRVWidgetSnapshot: Codable, Hashable { let zScore:Double; let price:Double?; let updatedAt:Date }

enum MVRVService {
    private static let cacheKey="mvrvWidgetSnapshot"
    private static let bases=["https://bitcoin-data.com/v1","https://bitcoin-data.com/api/v1","https://api.bgeometrics.com/v1"]
    private static let cm="https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
    static func cached()->MVRVWidgetSnapshot?{guard let d=UserDefaults.standard.data(forKey:cacheKey)else{return nil};return try? JSONDecoder().decode(MVRVWidgetSnapshot.self,from:d)}
    static func refresh() async throws->MVRVWidgetSnapshot{
        var z:Double?; var price:Double?; var last:Error?
        let zKeys=Set(["mvrvzscore","zscore","value"])
        for base in bases { do { let root=try await fetchJSON("\(base)/mvrv-zscore/last"); if let n=findNumber(root,preferred:zKeys){z=n;break} } catch { last=error } }
        if z == nil { z=try? await coinMetrics("CapMVRVZ") }
        guard let z else { throw last ?? NSError(domain:"MVRV",code:2,userInfo:[NSLocalizedDescriptionKey:"MVRV indisponible"]) }
        for base in bases { if let root=try? await fetchJSON("\(base)/btc-price/last"),let n=findNumber(root,preferred:Set(["price","btcprice","close","value"])){price=n;break} }
        if price == nil { price=try? await coinMetrics("PriceUSD") }
        let s=MVRVWidgetSnapshot(zScore:z,price:price,updatedAt:Date());if let d=try? JSONEncoder().encode(s){UserDefaults.standard.set(d,forKey:cacheKey)};WidgetCenter.shared.reloadTimelines(ofKind:"MVRVWidget");return s
    }
    static func zone(_ z:Double)->String{if z<0{return "Sous-évalué"};if z<2{return "Basse"};if z<5{return "Neutre"};if z<7{return "Chaude"};return "Haute"}
    private static func coinMetrics(_ metric:String)async throws->Double{let u="\(cm)?assets=btc&metrics=\(metric)&frequency=1d&limit_per_asset=1&paging_from=end&ignore_forbidden_errors=true&ignore_unsupported_errors=true";let root=try await fetchJSON(u);guard let d=root as? [String:Any],let a=d["data"] as? [[String:Any]],let row=a.last else{throw URLError(.cannotParseResponse)};if let n=row[metric] as? NSNumber{return n.doubleValue};if let s=row[metric] as? String,let n=Double(s){return n};throw URLError(.cannotParseResponse)}
    private static func fetchJSON(_ url:String)async throws->Any{var r=URLRequest(url:URL(string:url)!);r.timeoutInterval=20;r.setValue("application/json",forHTTPHeaderField:"Accept");r.setValue("MarketWidgets/2.2 iOS",forHTTPHeaderField:"User-Agent");let(d,res)=try await URLSession.shared.data(for:r);guard let h=res as? HTTPURLResponse,(200..<300).contains(h.statusCode)else{throw URLError(.badServerResponse)};if let text=String(data:d,encoding:.utf8),let n=Double(text.trimmingCharacters(in:.whitespacesAndNewlines)){return n};return try JSONSerialization.jsonObject(with:d)}
    private static func norm(_ s:String)->String{s.lowercased().filter{$0.isLetter||$0.isNumber}}
    private static func findNumber(_ node:Any,preferred:Set<String>)->Double?{let p=Set(preferred.map(norm));if let d=node as? [String:Any]{for(k,v)in d where p.contains(norm(k)){if let n=v as? NSNumber{return n.doubleValue};if let s=v as? String,let n=Double(s.replacingOccurrences(of:",",with:"")){return n}};for v in d.values{if let n=findNumber(v,preferred:p){return n}}}else if let a=node as? [Any]{for v in a{if let n=findNumber(v,preferred:p){return n}}}else if let n=node as? NSNumber{return n.doubleValue};return nil}
}
