import Foundation
import CryptoKit
import Security

struct KrakenIOSPosition:Codable,Hashable{let symbol:String;let valueEUR:Double;let dayChangeEUR:Double;let dayChangePercent:Double}
struct KrakenIOSSnapshot:Codable,Hashable{let totalEUR:Double;let dayChangeEUR:Double;let dayChangePercent:Double;let balances:[String:Double];let positions:[KrakenIOSPosition];let updatedAt:Date}
private struct KrakenTickerSnapshot{let current:Double;let open:Double}
private struct CostState{var qty=0.0;var costUSD=0.0}

enum KrakenKeychainStore{
 private static let service="com.lucas.marketwidgets.kraken"
 static func save(apiKey:String,apiSecret:String)throws{try saveValue(apiKey,account:"apiKey");try saveValue(apiSecret,account:"apiSecret")}
 static func credentials()->(String,String)?{guard let k=value(account:"apiKey"),let s=value(account:"apiSecret")else{return nil};return(k,s)}
 static func clear(){["apiKey","apiSecret"].forEach{SecItemDelete([kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:$0] as CFDictionary)}}
 private static func saveValue(_ value:String,account:String)throws{let data=Data(value.utf8);let q:[CFString:Any]=[kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:account];SecItemDelete(q as CFDictionary);var i=q;i[kSecValueData]=data;let s=SecItemAdd(i as CFDictionary,nil);guard s==errSecSuccess else{throw NSError(domain:NSOSStatusErrorDomain,code:Int(s))}}
 private static func value(account:String)->String?{let q:[CFString:Any]=[kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:account,kSecReturnData:true,kSecMatchLimit:kSecMatchLimitOne];var item:CFTypeRef?;guard SecItemCopyMatching(q as CFDictionary,&item)==errSecSuccess,let d=item as? Data else{return nil};return String(data:d,encoding:.utf8)}
}

enum KrakenConnectionService{
 private static let base=URL(string:"https://api.kraken.com")!
 private static let cashLike:Set<String>=["EUR","USD","GBP","CHF","CAD","AUD","JPY","USDT","USDC"]

 static func refresh()async throws->KrakenIOSSnapshot{
  guard let(k,s)=KrakenKeychainStore.credentials()else{throw URLError(.userAuthenticationRequired)}
  let balances=try await balance(apiKey:k,secret:s).filter{abs($0.value)>0.00000001}
  let costs=try await costBasis(apiKey:k,secret:s)
  let usdToEUR=(try? await eurTicker(asset:"ZUSD").current) ?? 1
  var total=0.0;var pnlEUR=0.0;var investedEUR=0.0;var positions:[KrakenIOSPosition]=[]
  for(raw,amount)in balances{
   let symbol=normalize(raw)
   guard let eur=try? await eurTicker(asset:raw).current else{continue}
   let valueEUR=amount*eur;total+=valueEUR
   if !cashLike.contains(symbol),valueEUR>0.01,let state=costs[symbol],state.qty>0,state.costUSD>0,let currentUSD=try? await usdTicker(asset:raw).current,currentUSD>0{
    let costUSD=state.costUSD*max(0,amount/state.qty)
    if costUSD>0{
     let valueUSD=amount*currentUSD
     let changeUSD=valueUSD-costUSD
     let pct=changeUSD/costUSD*100
     let changeEUR=changeUSD*usdToEUR
     pnlEUR+=changeEUR;investedEUR+=costUSD*usdToEUR
     positions.append(KrakenIOSPosition(symbol:displayAsset(symbol),valueEUR:valueEUR,dayChangeEUR:changeEUR,dayChangePercent:pct))
    }
   }
  }
  return KrakenIOSSnapshot(totalEUR:total,dayChangeEUR:pnlEUR,dayChangePercent:investedEUR>0 ? pnlEUR/investedEUR*100:0,balances:balances,positions:positions,updatedAt:Date())
 }

 private static func costBasis(apiKey:String,secret:String)async throws->[String:CostState]{
  var all:[[String:Any]]=[];var ofs=0;var count=Int.max
  while ofs<count && ofs<10000{
   let json=try await privatePost(path:"/0/private/TradesHistory",params:["ofs":"\(ofs)"],apiKey:apiKey,secret:secret)
   guard let result=json["result"] as? [String:Any] else{break}
   count=result["count"] as? Int ?? 0
   let trades=result["trades"] as? [String:[String:Any]] ?? [:]
   if trades.isEmpty{break}
   all.append(contentsOf:trades.values);ofs+=trades.count
  }
  all.sort{($0["time"] as? Double ?? 0)<($1["time"] as? Double ?? 0)}
  let earliest=all.compactMap{$0["time"] as? Double}.min()
  let eurUSD=earliest.map{try? await dailyFXHistory(pair:"EURUSD",since:Int($0))} ?? nil
  let fxHistory=eurUSD ?? [:]
  var states:[String:CostState]=[:]
  for t in all{
   guard let pair=t["pair"] as? String,let type=t["type"] as? String,let vol=Double(t["vol"] as? String ?? ""),let cost=Double(t["cost"] as? String ?? "")else{continue}
   let asset=baseAsset(pair)
   let quote=quoteAsset(pair)
   let time=t["time"] as? Double ?? 0
   let fx=await quoteToUSD(quote,time:time,eurUSD:fxHistory)
   var st=states[asset] ?? CostState()
   if type=="buy"{
    st.qty+=vol
    st.costUSD+=cost*fx
   }else if type=="sell",st.qty>0{
    let sold=min(vol,st.qty)
    let avg=st.costUSD/st.qty
    st.costUSD=max(0,st.costUSD-avg*sold)
    st.qty=max(0,st.qty-sold)
    if st.qty<0.0000000001{st.qty=0;st.costUSD=0}
   }
   states[asset]=st
  }
  return states
 }

 private static func quoteToUSD(_ quote:String,time:Double,eurUSD:[Int:Double])async->Double{
  switch quote{
  case "USD","USDT","USDC":return 1
  case "EUR":
   let day=Int(floor(time/86400))
   if let v=eurUSD[day]{return v}
   if let nearest=eurUSD.min(by:{abs($0.key-day)<abs($1.key-day)})?.value{return nearest}
   return (try? await ticker(pair:"EURUSD").current) ?? 1
  default:return 1
  }
 }

 private static func dailyFXHistory(pair:String,since:Int)async throws->[Int:Double]{
  var c=URLComponents(url:base.appendingPathComponent("/0/public/OHLC"),resolvingAgainstBaseURL:false)!
  c.queryItems=[URLQueryItem(name:"pair",value:pair),URLQueryItem(name:"interval",value:"1440"),URLQueryItem(name:"since",value:String(since))]
  let(data,_)=try await URLSession.shared.data(from:c.url!)
  guard let j=try JSONSerialization.jsonObject(with:data) as? [String:Any],let result=j["result"] as? [String:Any],let rows=result.first(where:{$0.key != "last"})?.value as? [[Any]] else{return[:]}
  var out:[Int:Double]=[:]
  for row in rows{guard row.count>4,let ts=row[0] as? Double else{continue};let close=(row[4] as? String).flatMap(Double.init) ?? (row[4] as? Double) ?? 0;if close>0{out[Int(floor(ts/86400))]=close}}
  return out
 }

 private static func balance(apiKey:String,secret:String)async throws->[String:Double]{let j=try await privatePost(path:"/0/private/Balance",params:[:],apiKey:apiKey,secret:secret);guard let r=j["result"] as? [String:String]else{throw URLError(.cannotParseResponse)};return r.compactMapValues(Double.init)}
 private static func privatePost(path:String,params:[String:String],apiKey:String,secret:String)async throws->[String:Any]{let nonce=String(Int(Date().timeIntervalSince1970*1000));var parts=["nonce=\(nonce)"];parts+=params.map{"\($0.key)=\($0.value)"};let post=parts.joined(separator:"&");let sig=try sign(path:path,nonce:nonce,postData:post,secret:secret);var req=URLRequest(url:base.appendingPathComponent(path));req.httpMethod="POST";req.httpBody=Data(post.utf8);req.setValue(apiKey,forHTTPHeaderField:"API-Key");req.setValue(sig,forHTTPHeaderField:"API-Sign");req.setValue("application/x-www-form-urlencoded",forHTTPHeaderField:"Content-Type");let(data,response)=try await URLSession.shared.data(for:req);guard(response as? HTTPURLResponse)?.statusCode ?? 500<300 else{throw URLError(.badServerResponse)};let j=try JSONSerialization.jsonObject(with:data) as? [String:Any] ?? [:];if let e=j["error"] as? [String],let first=e.first,!first.isEmpty{throw NSError(domain:"Kraken",code:2,userInfo:[NSLocalizedDescriptionKey:first])};return j}
 private static func sign(path:String,nonce:String,postData:String,secret:String)throws->String{guard let sd=Data(base64Encoded:secret)else{throw URLError(.userAuthenticationRequired)};let digest=SHA256.hash(data:Data((nonce+postData).utf8));var msg=Data(path.utf8);msg.append(contentsOf:digest);return Data(HMAC<SHA512>.authenticationCode(for:msg,using:SymmetricKey(data:sd))).base64EncodedString()}
 private static func baseAsset(_ pair:String)->String{var p=pair.uppercased().replacingOccurrences(of:"/",with:"").replacingOccurrences(of:"-",with:"");for q in ["ZEUR","ZUSD","USDT","USDC","EUR","USD","GBP","BTC","XBT"]{if p.hasSuffix(q){p=String(p.dropLast(q.count));break}};return canonicalAsset(p)}
 private static func canonicalAsset(_ raw:String)->String{let a=raw.split(separator:".").first.map(String.init)?.uppercased() ?? raw.uppercased();switch a{case"XXBT","XBT","BTC":return"XBT";case"XETH","ETH":return"ETH";case"ZEUR","EUR":return"EUR";case"ZUSD","USD":return"USD";default:if (a.hasPrefix("X")||a.hasPrefix("Z")) && a.count>=4{return String(a.dropFirst())};return a}}
 private static func displayAsset(_ symbol:String)->String{symbol=="XBT" ? "BTC":symbol}
 private static func quoteAsset(_ pair:String)->String{let p=pair.uppercased().replacingOccurrences(of:"/",with:"").replacingOccurrences(of:"-",with:"");if p.hasSuffix("USDT"){return"USDT"};if p.hasSuffix("USDC"){return"USDC"};if p.hasSuffix("ZEUR")||p.hasSuffix("EUR"){return"EUR"};if p.hasSuffix("ZUSD")||p.hasSuffix("USD"){return"USD"};if p.hasSuffix("GBP"){return"GBP"};return"EUR"}
 private static func eurTicker(asset raw:String)async throws->KrakenTickerSnapshot{let a=normalize(raw);if a=="EUR"{return KrakenTickerSnapshot(current:1,open:1)};let candidates:[(String,Bool)]=a=="XBT" ? [("XBTEUR",false),("XXBTZEUR",false)] : a=="USD" ? [("EURUSD",true)] : [("\(a)EUR",false)];for(pair,inv)in candidates{if let t=try? await ticker(pair:pair),t.current>0{return inv ? KrakenTickerSnapshot(current:1/t.current,open:1/t.open):t}};throw URLError(.cannotParseResponse)}
 private static func usdTicker(asset raw:String)async throws->KrakenTickerSnapshot{let a=normalize(raw);if a=="USD"||a=="USDT"||a=="USDC"{return KrakenTickerSnapshot(current:1,open:1)};let candidates=a=="XBT" ? ["XBTUSD","XXBTZUSD"]:["\(a)USD"];for pair in candidates{if let t=try? await ticker(pair:pair),t.current>0{return t}};throw URLError(.cannotParseResponse)}
 private static func ticker(pair:String)async throws->KrakenTickerSnapshot{var c=URLComponents(url:base.appendingPathComponent("/0/public/Ticker"),resolvingAgainstBaseURL:false)!;c.queryItems=[URLQueryItem(name:"pair",value:pair)];let(data,_)=try await URLSession.shared.data(from:c.url!);let j=try JSONSerialization.jsonObject(with:data) as? [String:Any];guard let r=j?["result"] as? [String:Any],let first=r.values.first as? [String:Any],let close=first["c"] as? [String],let cur=close.first.flatMap(Double.init)else{throw URLError(.cannotParseResponse)};let open=(first["o"] as? String).flatMap(Double.init) ?? cur;return KrakenTickerSnapshot(current:cur,open:open)}
 private static func normalize(_ raw:String)->String{canonicalAsset(raw)}
}
