import Foundation
import WidgetKit

struct MVRVWidgetSnapshot: Codable, Hashable { let zScore:Double; let price:Double?; let updatedAt:Date }

enum MVRVService {
    private static let cacheKey="mvrvWidgetSnapshot"
    private static let base="https://bitcoin-data.com/v1"
    static func cached()->MVRVWidgetSnapshot?{guard let d=UserDefaults.standard.data(forKey:cacheKey)else{return nil};return try? JSONDecoder().decode(MVRVWidgetSnapshot.self,from:d)}
    static func refresh() async throws->MVRVWidgetSnapshot{
        async let zAny=fetchJSON("\(base)/mvrv-zscore/last")
        async let pAny=fetchJSON("\(base)/btc-price/last")
        let zr=try await zAny
        let pr=try? await pAny
        let keys=Set(["mvrvzscore","zscore","value"])
        guard let z=findNumber(zr,preferred:keys) else {throw NSError(domain:"MVRV",code:2,userInfo:[NSLocalizedDescriptionKey:"BGeometrics MVRV: valeur absente"])}
        let price=pr.flatMap{findNumber($0,preferred:Set(["price","btcprice","close","value"]))}
        let s=MVRVWidgetSnapshot(zScore:z,price:price,updatedAt:Date());if let d=try? JSONEncoder().encode(s){UserDefaults.standard.set(d,forKey:cacheKey)};WidgetCenter.shared.reloadTimelines(ofKind:"MVRVWidget");return s
    }
    static func zone(_ z:Double)->String{if z<0{return "Sous-évalué"};if z<2{return "Basse"};if z<5{return "Neutre"};if z<7{return "Chaude"};return "Haute"}
    private static func fetchJSON(_ url:String)async throws->Any{var r=URLRequest(url:URL(string:url)!);r.setValue("application/json",forHTTPHeaderField:"Accept");r.setValue("MarketWidgets/2.1 iOS",forHTTPHeaderField:"User-Agent");let(d,res)=try await URLSession.shared.data(for:r);guard let h=res as? HTTPURLResponse,(200..<300).contains(h.statusCode)else{throw URLError(.badServerResponse)};if let text=String(data:d,encoding:.utf8),let n=Double(text.trimmingCharacters(in:.whitespacesAndNewlines)){return n};return try JSONSerialization.jsonObject(with:d)}
    private static func norm(_ s:String)->String{s.lowercased().filter{$0.isLetter||$0.isNumber}}
    private static func findNumber(_ node:Any,preferred:Set<String>)->Double?{let p=Set(preferred.map(norm));if let d=node as? [String:Any]{for(k,v)in d where p.contains(norm(k)){if let n=v as? NSNumber{return n.doubleValue};if let s=v as? String,let n=Double(s.replacingOccurrences(of:",",with:"")){return n}};for v in d.values{if let n=findNumber(v,preferred:p){return n}}}else if let a=node as? [Any]{for v in a{if let n=findNumber(v,preferred:p){return n}}}else if let n=node as? NSNumber{return n.doubleValue};return nil}
}
