# Revert 澶勭悊璁捐

## 鏍稿績鍘熷垯

1. **澧為噺鍚屾鏄敮涓€蹇呴』渚濊禆鐨勬纭€т繚闅?*鈥斺€擲SE 鍜屾湰鍦板彂璧峰彧鏄綋楠屼紭鍖?2. **`session.updated` 涓殑 revert 淇℃伅蹇呴』绔嬪嵆澶勭悊**鈥斺€擴I 杩囨护涓嶈兘绛?3. **`fromId` 鍜?`toId` 閮藉湪澧為噺鍚屾澶勭悊鍒?`session.updated(revert)` 浜嬩欢鏃惰幏鍙?*鈥斺€斾繚璇佸閲忓悓姝ヨ兘鐙珛姝ｇ‘宸ヤ綔
4. **`toId` 鍦ㄥ鐞?`session.updated(revert)` 浜嬩欢鐨勫綋鏃朵粠 DB 鏌ヨ**鈥斺€斾笉鏄瓑 batch 瀹屾垚鍚庯紝涓嶆槸鍦?`applySyncResult` 涓?
## 涓や釜鐙珛闂

### 闂 1锛歎I 杩囨护锛堥殣钘忚 revert 鐨勬秷鎭級鈥斺€斿繀椤荤珛鍗冲鐞?
**鐩爣**锛氱敤鎴风湅鍒扮殑娑堟伅鍒楄〃搴旀帓闄や粠 revert 璧风偣寮€濮嬬殑鎵€鏈夊悗缁秷鎭€?
**澶勭悊鏃舵満**锛氭敹鍒?`session.updated` 鍖呭惈 revert 淇℃伅鏃讹紝**绔嬪嵆**澶勭悊銆?
**revert 淇℃伅鏉ユ簮**锛堟寜鏃舵晥鎺掑簭锛夛細

| 鏉ユ簮 | 鏃舵晥 | 璇存槑 |
|------|------|------|
| 鏈湴鍙戣捣 revert | 鏈€蹇?| 璋?API 鍚庣珛鍗宠 `_sessionRevert` |
| SSE `session.updated` | 蹇?| `SessionEventHandler` 鍐?DB 鈫?Room Flow 瑙﹀彂 |
| 澧為噺鍚屾涓殑 `session.updated` 浜嬩欢 | 鏈夊欢杩?| **鏈€鍙潬锛屽繀椤昏兘鐙珛宸ヤ綔** |
| `getSession`锛堟墦寮€浼氳瘽鏃讹級 | 涓€娆℃€?| 浠?`loadSession` 鏃惰皟鐢?|

**鍏抽敭**锛氬閲忓悓姝ヤ腑蹇呴』澶勭悊 `session.updated` 浜嬩欢鎻愬彇 revert 淇℃伅锛屽惁鍒?SSE 绂荤嚎鏈熼棿鐨?revert 浼氫涪澶便€?
### 闂 2锛欴B 娓呯悊锛堝垹闄よ revert 鐨勬秷鎭級鈥斺€斿繀椤讳緷璧栧閲忓悓姝?
**鐩爣**锛氬綋鐢ㄦ埛鍦?revert 鍚庡彂閫佹柊 prompt锛屾湇鍔＄浼?emit `message.removed` 浜嬩欢锛坄msg_` ID锛夛紝鏈湴闇€瑕佷粠 DB 鍒犻櫎瀵瑰簲鐨?`evt_` 璁板綍銆?
**鏍稿績闅剧偣**锛?- `message.removed` 浜嬩欢涓殑 ID 鏄?`msg_` 鏍煎紡锛屾湰鍦?DB 鏄?`evt_` 鏍煎紡锛屾棤娉曠洿鎺ュ尮閰?- 鏃跺簭闂锛歚session.updated(clearRevert)` 鍙兘鍦?`message.removed` 涔嬪墠鍒拌揪锛屾竻闄?revert 鐘舵€?- `fromId` 鍜?`toId` 蹇呴』鍦ㄦ纭殑鏃舵満鑾峰彇

## SessionEntity 鏂板瀛楁

鍦?session DB 琛ㄤ腑瀛樺偍澧為噺鍚屾鑾峰彇鐨?revert 娓呯悊淇℃伅锛?
| 瀛楁 | 绫诲瀷 | 璇存槑 |
|------|------|------|
| `revertMessageID` | String? | `msg_` ID锛堝凡鏈夛級 |
| `revertPartID` | String? | 锛堝凡鏈夛級 |
| `revertFrom` | String? | `evt_` ID锛屽嵆 `fromId`锛屽閲忓悓姝ヤ腑 `resolveEvtID` 鑾峰彇 |
| `revertTo` | String? | `evt_` ID锛屽嵆 `toId`锛屽閲忓悓姝ヤ腑浠?DB 鏌ヨ |

**鐢ㄩ€?*锛?- `revertFrom`锛歎I 杩囨护锛坄messages` combine flow 鐢?`id < revertFrom` 杩囨护锛?- `revertFrom` + `revertTo`锛欴B 娓呯悊锛坄handleV2MessageRemoval` 鍒犻櫎 `id >= fromId && id <= toId` 鐨勮褰曪級

## _pendingRevertCleanup 璁捐

璁板綍寰呭垹闄よ寖鍥?`(fromId: String, toId: String?)`锛?
- `fromId`锛歳evert 璧风偣鐨?`evt_` ID
- `toId`锛歳evert 鑼冨洿鍐呮渶鍚庝竴鏉℃秷鎭殑 `evt_` ID

### fromId 鍜?toId 鐨勮幏鍙栨椂鏈?
**閮藉湪澧為噺鍚屾澶勭悊鍒?`session.updated(revert)` 浜嬩欢鏃惰幏鍙?*锛?
1. 浠庝簨浠朵腑瑙ｆ瀽鍑?`revertMessageID`锛坄msg_` ID锛?2. 璋?`resolveEvtID` 灏?`msg_` ID 杞负 `evt_` ID 鈫?鍗?`fromId`
3. **绔嬪嵆**浠?DB 鏌ヨ `toId`锛歚SELECT id FROM session_message WHERE sessionId=? AND id >= fromId ORDER BY id DESC LIMIT 1`
4. 灏?`revertFrom`锛坒romId锛夊拰 `revertTo`锛坱oId锛夊啓鍏?session DB 琛?
涓轰粈涔堣繖鏄纭椂鏈猴細
- 澧為噺鍚屾鎸?seq 椤哄簭澶勭悊浜嬩欢锛宍EventReplayer` 鎸?seq 椤哄簭 replay
- 澶勭悊鍒?`session.updated(revert)` 浜嬩欢鏃讹紝璇ヤ簨浠朵箣鍓嶇殑鎵€鏈夋秷鎭簨浠跺凡 replay 鍒?DB
- 姝ゆ椂 DB 涓殑娑堟伅灏辨槸 revert 鏃跺埢鐨勫畬鏁村揩鐓?- 鍚庣画浜嬩欢锛堝鏂?prompt銆乣message.removed` 绛夛級杩樻病澶勭悊锛屼笉浼氭薄鏌?`toId`
- **蹇呴』鍦ㄥ鐞?`session.updated(revert)` 浜嬩欢鐨勫綋鏃舵煡 `toId`**锛屼笉鑳界瓑 batch 瀹屾垚鍚庡啀鏌?
### 闈炲閲忓悓姝ヨ矾寰勭殑澶勭悊

| 璺緞 | fromId | toId | 璇存槑 |
|------|--------|------|------|
| 澧為噺鍚屾涓?`session.updated(revert)` | `resolveEvtID` 浠?DB 鏌?鉁?| 浠?DB 鏌?鉁?| 鏈€鍙潬 |
| SSE `session.updated` 鈫?`observeSession` | `resolveEvtID` | null | SSE 鍒拌揪鏃?DB 鍙兘娌″悓姝ュ畬锛宼oId 涓嶅彲闈?|
| 鏈湴鍙戣捣 revert | 鏈湴宸茬煡 | null | 澧為噺鍚屾鍚庡啀琛ュ叏 |
| `getSession` 鈫?`observeSession` | `resolveEvtID` | null | 鍚屼笂 |

**闈炲閲忓悓姝ヨ矾寰勫彧璁?`fromId`锛宍toId` 鐣?null銆?* 鍚庣画澧為噺鍚屾澶勭悊鍒?`session.updated(revert)` 鏃朵細鍐欏叆 session DB 琛紝Room Flow 瑙﹀彂 `observeSession` 鏃朵粠 DB 璇诲彇瀹屾暣鐨?`fromId` 鍜?`toId`銆?
### 鐢熷瓨鏈?
- 涓嶉殢 `_sessionRevert` 娓呴櫎鑰屾竻闄わ紙璺ㄨ繃 `clearRevert` 鏃跺簭闂撮殭锛?- `handleV2MessageRemoval` 瀹屾垚鍚庢竻闄?
## 澧為噺鍚屾涓鐞?session.updated 浜嬩欢

褰撳墠 `EventReplayer` 蹇界暐浜?`session.updated` 浜嬩欢銆傞渶瑕佸湪澧為噺鍚屾娴佺▼涓鍔犲鐞嗐€?
### 鏂规

鍦?`SessionMessageRepositoryImpl.incrementalSync()` 鐨勪簨浠跺惊鐜腑锛?
1. `EventReplayer` 鎸?seq 椤哄簭 replay 浜嬩欢锛岄亣鍒?`session.updated` 璺宠繃锛堜笉澶勭悊锛?2. replay 瀹屾垚鍚庯紝閬嶅巻褰撳墠 batch 鐨勪簨浠讹紝鏌ユ壘 `session.updated` 浜嬩欢
3. 瀵规瘡涓?`session.updated` 浜嬩欢锛?   a. 瑙ｆ瀽 `info.revert` 瀛楁
   b. 濡傛灉鍖呭惈 revert锛氳皟 `syncApiClient.resolveEvtID` 寰?`fromId`锛坄evt_` ID锛夛紝浠?DB 鏌?`toId`
   c. 灏?`revertMessageID`銆乣revertFrom`銆乣revertTo` 鍐欏叆 session DB 琛?   d. 濡傛灉 revert 涓?null锛坈learRevert锛夛細娓呴櫎 session DB 琛ㄤ腑鐨?revert 鐩稿叧瀛楁
4. Room Flow 瑙﹀彂 `observeSession` 鈫?浠?DB 璇诲彇 revert 淇℃伅 鈫?璁?`_sessionRevert` + `_pendingRevertCleanup`

### 鏃跺簭缁嗚妭

**`toId` 蹇呴』鍦ㄥ鐞?`session.updated(revert)` 浜嬩欢鐨勫綋鏃舵煡璇?*锛屼笉鏄瓑 batch 瀹屾垚鍚庛€傚洜涓猴細
- `EventReplayer` replay 鏃舵寜 seq 椤哄簭锛岄亣鍒?`session.updated(revert)` 鏃跺墠闈㈢殑浜嬩欢宸?replay
- 姝ゆ椂 DB 涓秷鎭槸瀹屽鐨?- 濡傛灉绛?batch 缁撴潫锛屽悗缁簨浠剁殑 replay 鍙兘淇敼 DB

**session DB 琛ㄧ殑鍐欏叆鍙互鍦?replay 瀹屾垚鍚庛€乥atch DB 浜嬪姟鎻愪氦鏃剁粺涓€鍋?*銆俁oom Flow 鏇存柊鏅氫竴鐐规病鍏崇郴锛孶I 杩囨护绛?Room Flow 瑙﹀彂 `observeSession` 鏃跺啀鍋氬嵆鍙€?
## 涓変釜鍦烘櫙鐨勫畬鏁存祦绋?
### 鍦烘櫙 A锛氭湰鍦板彂璧?revert

```
1. 鐢ㄦ埛鐐瑰嚮 revert 鈫?revertSession API
2. 绔嬪嵆璁?_sessionRevert锛圲I 绔嬪嵆杩囨护锛?3. 绔嬪嵆璁?_pendingRevertCleanup(fromId=鏈湴宸茬煡, toId=null)
4. 璋?incrementalSync 鈫?鏈嶅姟绔鏃跺凡鏈?revert 鐘舵€?5. 澧為噺鍚屾涓鐞?session.updated(revert)锛?   a. resolveEvtID 鈫?fromId
   b. 浠?DB 鏌?toId
   c. 鍐欏叆 session DB 琛紙revertFrom + revertTo锛?6. Room Flow 瑙﹀彂 observeSession 鈫?浠?DB 璇?fromId 鍜?toId 鈫?鏇存柊 _pendingRevertCleanup
7. 鍚庣画澧為噺鍚屾妫€娴嬪埌 hasV2MessageRemoval 鈫?handleV2MessageRemoval 鈫?鍒犻櫎 DB 璁板綍
```

### 鍦烘櫙 B锛氬閮ㄥ彂璧?revert锛坵eb 绔瓑锛?
**B1锛歋SE 鍦ㄧ嚎**

```
1. SSE 鏀跺埌 session.updated(revert) 鈫?SessionEventHandler 鍐欏叆 DB 鈫?observeSession 瑙﹀彂
2. observeSession 妫€娴嬪埌 revert 鈫?resolveEvtID 鈫?璁?_sessionRevert锛圲I 杩囨护鐢熸晥锛?3. 璁?_pendingRevertCleanup(fromId=evtId, toId=null)
4. 鍚庣画澧為噺鍚屾涓鐞?session.updated(revert)锛?   a. resolveEvtID 鈫?fromId
   b. 浠?DB 鏌?toId
   c. 鍐欏叆 session DB 琛?5. Room Flow 瑙﹀彂 observeSession 鈫?浠?DB 璇?fromId 鍜?toId 鈫?鏇存柊 _pendingRevertCleanup
6. 鍚庣画澧為噺鍚屾妫€娴嬪埌 hasV2MessageRemoval 鈫?handleV2MessageRemoval 鈫?鍒犻櫎 DB 璁板綍
```

**B2锛歋SE 绂荤嚎**

```
1. 澶栭儴鍙戣捣 revert锛宎pp 鏈敹鍒?SSE
2. 杞 incrementalSync 鈫?鏀跺埌 session.updated(revert) 浜嬩欢
3. 澧為噺鍚屾涓鐞?session.updated(revert)锛?   a. resolveEvtID 鈫?fromId
   b. 浠?DB 鏌?toId
   c. 鍐欏叆 session DB 琛?4. Room Flow 瑙﹀彂 observeSession 鈫?浠?DB 璇?fromId 鍜?toId 鈫?璁?_sessionRevert + _pendingRevertCleanup
5. 鍚庣画澧為噺鍚屾妫€娴嬪埌 hasV2MessageRemoval 鈫?handleV2MessageRemoval 鈫?鍒犻櫎 DB 璁板綍
```

### 鍦烘櫙 C锛氭墦寮€宸叉湁 revert 鐨勪細璇?
```
1. loadSession 鈫?getSession 鈫?API 杩斿洖鍚?revert 鐨?session 鈫?鍐欏叆 DB
2. observeSession 瑙﹀彂 鈫?resolveEvtID 鈫?璁?_sessionRevert锛圲I 杩囨护鐢熸晥锛?3. 璁?_pendingRevertCleanup(fromId=evtId, toId=null)
4. 澧為噺鍚屾涓鐞?session.updated(revert)锛?   a. resolveEvtID 鈫?fromId
   b. 浠?DB 鏌?toId
   c. 鍐欏叆 session DB 琛?5. Room Flow 瑙﹀彂 observeSession 鈫?浠?DB 璇?fromId 鍜?toId 鈫?鏇存柊 _pendingRevertCleanup
6. 鍚庣画澧為噺鍚屾妫€娴嬪埌 hasV2MessageRemoval 鈫?handleV2MessageRemoval 鈫?鍒犻櫎 DB 璁板綍
```

## applySyncResult 涓殑閫昏緫

```
1. 搴旂敤鍚屾缁撴灉鍒?messageWindowState 鍜?_messages
2. 濡傛灉 result.hasV2MessageRemoval 鈫?璋?handleV2MessageRemoval()
```

**涓嶅湪 `applySyncResult` 涓鐞?fromId/toId**鈥斺€旇繖浜涗俊鎭凡閫氳繃澧為噺鍚屾鍐欏叆 session DB 琛紝Room Flow 鈫?`observeSession` 璐熻矗浼犻€掑埌 ViewModel銆?
## observeSession 涓殑閫昏緫

```
1. 浠?session 璇诲彇 revert 淇℃伅
2. 濡傛灉鏈?revert 涓?revertFrom != null锛堝閲忓悓姝ュ凡澶勭悊锛夛細
   鈫?璁?_sessionRevert(localMessageID = revertFrom)
   鈫?璁?_pendingRevertCleanup(fromId = revertFrom, toId = revertTo)
3. 濡傛灉鏈?revert 涓?revertFrom == null锛堥潪澧為噺鍚屾璺緞锛夛細
   鈫?resolveEvtID 鈫?璁?_sessionRevert(localMessageID = evtId)
   鈫?璁?_pendingRevertCleanup(fromId = evtId, toId = null)
4. 濡傛灉 revert 涓?null锛坈learRevert锛夛細
   鈫?娓呴櫎 _sessionRevert
   鈫?涓嶆竻闄?_pendingRevertCleanup锛堣法杩囨椂搴忛棿闅欙級
```

## handleV2MessageRemoval 閫昏緫

```
1. 璇诲彇 _pendingRevertCleanup锛屽鏋?null 鍒?return
2. 濡傛灉 toId == null 鈫?杩樻病浠庡閲忓悓姝ヨ幏鍙栵紝璺宠繃锛堜笅娆″閲忓悓姝ュ啀璇曪級
3. 鍒犻櫎 _messages 涓?id >= fromId && id <= toId 鐨勬秷鎭?4. 浠?DB 鍒犻櫎瀵瑰簲璁板綍
5. 娓呴櫎 _pendingRevertCleanup
```

## 鍏抽敭绾︽潫

- `fromId` 鍜?`toId` 閮藉湪澧為噺鍚屾澶勭悊鍒?`session.updated(revert)` 浜嬩欢鏃惰幏鍙栵紝淇濊瘉澧為噺鍚屾鑳界嫭绔嬫纭伐浣?- `toId` 鍦ㄥ鐞嗕簨浠剁殑褰撴椂鏌ヨ锛屼笉绛?batch 瀹屾垚
- `toId` 鍐欏叆 session DB 琛紝`observeSession` 浠?DB 璇诲彇
- `_pendingRevertCleanup` 涓嶉殢 `_sessionRevert` 娓呴櫎鑰屾竻闄わ紙璺ㄨ繃 clearRevert 鏃跺簭闂撮殭锛?- 鍒犻櫎鑼冨洿鏄?`id >= fromId && id <= toId`锛堟湁鐣岃寖鍥撮槻姝㈣鍒犳柊娑堟伅锛?- `handleV2MessageRemoval` 瀹屾垚鍚庢竻闄?`_pendingRevertCleanup`
- 澧為噺鍚屾涓繀椤诲鐞?`session.updated` 浜嬩欢锛屽惁鍒欑绾挎湡闂寸殑 revert 浼氫涪澶?- session DB 琛ㄧ殑鍐欏叆鍙互鍦?batch replay 瀹屾垚鍚庣粺涓€鍋氾紝Room Flow 鏅氱偣瑙﹀彂娌￠棶棰?
## 鏁版嵁娴佹€昏

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹? revert 鏉ユ簮  鈹?鈹?(鏈湴/澶栭儴)   鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹?       鈹?       鈻?鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?session.revert    鈹?    鈹?message.removed  鈹?鈹?(msg_ ID)         鈹?    鈹?(msg_ ID)        鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?       鈹?                       鈹?       鈻?                       鈹?鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?   鈹?鈹?澧為噺鍚屾涓鐞?             鈹?   鈹?鈹?session.updated(revert)锛? 鈹?   鈹?鈹?1. resolveEvtID 鈫?fromId  鈹?   鈹?鈹?2. DB 鏌?鈫?toId           鈹?   鈹?鈹?3. 鍐欏叆 session DB 琛?     鈹?   鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?   鈹?       鈹?                       鈹?       鈻?                       鈹?鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?   鈹?鈹?Room Flow 鈫?observeSession鈹?   鈹?鈹?浠?DB 璇?fromId + toId    鈹?   鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?   鈹?       鈹?                       鈹?       鈻?                       鈹?鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?           鈹?鈹?_sessionRevert    鈹?           鈹?鈹?(UI 杩囨护, 绔嬪嵆)   鈹?           鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?           鈹?       鈹?                       鈹?       鈻?                       鈹?  messages combine              鈹?  (id < localID)                鈹?                                鈹?       鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?       鈻?鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?_pendingRevertCleanup         鈹?鈹?fromId: 澧為噺鍚屾鏃?           鈹?鈹?        resolveEvtID 鑾峰彇     鈹?鈹?toId: 澧為噺鍚屾鏃?             鈹?鈹?      澶勭悊 session.updated    鈹?鈹?      鏃朵粠 DB 鏌?             鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?       鈹?       鈻?  handleV2MessageRemoval
  鈫?DB 鍒犻櫎
```
