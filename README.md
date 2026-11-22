# Auto Camera 📷

依照**手機型號**與**現場環境**，建議你該用幾倍鏡頭拍、站在哪裡。

## 功能
- **直接讀取鏡頭硬體**：透過 Android Camera2 API 讀出每顆鏡頭的焦距、感光元件尺寸、光圈，自動換算成 0.6×／1×／3× 等倍數；被廠商隱藏的鏡頭再用機型資料庫補上。
- **情境建議**：人像、風景、建築、美食、寵物、商品、街拍、夜景、微距。
- **現場測光**：拍一張 1× 測試照，分析亮度、逆光、過曝、背景雜亂度、ISO／快門。
- **站位計算**：依距離與主體大小算出需要的焦距，告訴你往前或往後走多少、相機放多高。
- **取景框**：在照片上畫出建議倍數實際拍到的範圍。

## 下載 APK
到 [Releases](../../releases) 下載最新的 `AutoCamera-xx.apk`，在手機上開啟安裝。
每次推送到 `main`，GitHub Actions 都會自動重新編譯。

## 自己編譯
```bash
npm install
npm run vendor
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
```

## 結構
- `www/index.html`：整個 App（純 HTML/JS）
- `native/`：讀取相機硬體的 Android 原生外掛（編譯時複製進專案）
- `capacitor.config.json`：Capacitor 設定
- `.github/workflows/build-apk.yml`：自動編譯 APK

> 鏡頭規格為概略值，各地版本可能不同。
